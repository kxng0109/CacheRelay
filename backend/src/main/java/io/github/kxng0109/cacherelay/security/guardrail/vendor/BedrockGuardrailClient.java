package io.github.kxng0109.cacherelay.security.guardrail.vendor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import io.github.kxng0109.cacherelay.security.SsrfValidator;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AWS Bedrock Guardrails screening via {@code ApplyGuardrail} (INPUT source).
 *
 * <p>Enabled only when {@code gateway.guardrails.vendor=bedrock} alongside the
 * master vendor flag: the bean does not exist otherwise, so payloads never
 * leave the boundary by accident. Authentication rides the ambient AWS
 * credential chain (instance role preferred); the endpoint is SSRF-validated
 * once at construction. Every failure throws so the filter fails closed to
 * the local engines. Verdicts and latency are metered for spend audit; raw
 * payload text is never logged.</p>
 */
@Component
@ConditionalOnProperty(name = "gateway.guardrails.vendor", havingValue = "bedrock")
public class BedrockGuardrailClient implements GuardrailVendorClient {

	private static final Logger log = LoggerFactory.getLogger(BedrockGuardrailClient.class);

	private final HttpClient httpClient;

	private final SsrfValidator ssrfValidator;

	private final ObjectMapper objectMapper;

	private final URI endpoint;

	private final String guardrailId;

	private final String guardrailVersion;

	private final Duration timeout;

	private final @Nullable MeterRegistry meterRegistry;

	/**
	 * Creates the client.
	 *
	 * @param httpClient    shared proxy client (virtual threads, never redirects)
	 * @param ssrfValidator SSRF validator for the endpoint
	 * @param objectMapper  Jackson mapper
	 * @param endpoint      regional {@code ApplyGuardrail} base URL (same-region profile only)
	 * @param guardrailId   guardrail identifier
	 * @param guardrailVersion guardrail version
	 * @param timeoutMillis per-request bound in milliseconds
	 * @param meterRegistry telemetry registry, or {@code null}
	 */
	public BedrockGuardrailClient(
			@Qualifier("proxyHttpClient") HttpClient httpClient,
			SsrfValidator ssrfValidator,
			ObjectMapper objectMapper,
			@Value("${gateway.guardrails.vendor-endpoint:}") String endpoint,
			@Value("${gateway.guardrails.vendor-guardrail-id:}") String guardrailId,
			@Value("${gateway.guardrails.vendor-guardrail-version:DRAFT}") String guardrailVersion,
			@Value("${gateway.guardrails.vendor-timeout-millis:5000}") long timeoutMillis,
			@Nullable MeterRegistry meterRegistry
	) {
		this.httpClient = httpClient;
		this.ssrfValidator = ssrfValidator;
		this.objectMapper = objectMapper;
		this.endpoint = URI.create(endpoint == null ? "" : endpoint.trim());
		this.guardrailId = guardrailId == null ? "" : guardrailId.trim();
		this.guardrailVersion = guardrailVersion == null || guardrailVersion.isBlank()
				? "DRAFT"
				: guardrailVersion.trim();
		this.timeout = Duration.ofMillis(Math.max(500L, timeoutMillis));
		this.meterRegistry = meterRegistry;
		ssrfValidator.validate(this.endpoint);
		if (this.guardrailId.isBlank()) {
			throw new IllegalStateException(
					"gateway.guardrails.vendor-guardrail-id is required for bedrock screening");
		}
	}

	@Override
	public String vendorId() {
		return "bedrock";
	}

	@Override
	public VendorVerdict screen(String textPayload) {
		Instant start = Instant.now();
		String body;
		try {
			body = objectMapper.writeValueAsString(new ApplyRequest(
					List.of(new ContentBlock(new TextBlock(truncate(textPayload)))),
					"INPUT"));
		} catch (Exception serializationFailed) {
			throw new IllegalStateException("vendor request serialization failed", serializationFailed);
		}
		HttpRequest request = HttpRequest.newBuilder(
				endpoint.resolve("/guardrail/" + guardrailId + "/version/" + guardrailVersion
						+ "/apply"))
				.timeout(timeout)
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
		HttpResponse<String> response;
		try {
			response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (Exception transportFailed) {
			throw new IllegalStateException("vendor screening transport failed", transportFailed);
		}
		Duration latency = Duration.between(start, Instant.now());
		recordSpend(latency, response.statusCode());
		if (response.statusCode() != 200) {
			throw new IllegalStateException(
					"vendor screening answered " + response.statusCode());
		}
		JsonNode root;
		try {
			root = objectMapper.readTree(response.body());
		} catch (Exception unparsable) {
			throw new IllegalStateException("vendor screening response unparsable", unparsable);
		}
		String action = root.path("action").asString("");
		if ("GUARDRAIL_INTERVENED".equals(action)) {
			String reason = firstIntervention(root);
			log.warn("Bedrock screening intervened: {}", reason);
			return new VendorVerdict(true, "bedrock", reason, latency);
		}
		return VendorVerdict.clean("bedrock", latency);
	}

	private void recordSpend(Duration latency, int status) {
		MeterRegistry registry = this.meterRegistry;
		if (registry == null) {
			return;
		}
		registry.counter("guardrail_vendor_requests_total", "vendor", "bedrock",
				"status", String.valueOf(status)).increment();
		registry.timer("guardrail_vendor_latency", "vendor", "bedrock")
				.record(latency);
	}

	private static String truncate(String text) {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		if (bytes.length <= 100_000) {
			return text;
		}
		int end = 100_000;
		while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
			end--;
		}
		return new String(bytes, 0, end, StandardCharsets.UTF_8);
	}

	private static String firstIntervention(JsonNode root) {
		JsonNode assessments = root.path("assessments");
		if (assessments.isArray()) {
			for (JsonNode assessment : assessments) {
				for (String policy : List.of("contentPolicy", "topicPolicy", "wordPolicy",
						"sensitiveInformationPolicy")) {
					JsonNode node = assessment.path(policy);
					if (!node.isMissingNode() && !node.path("filters").isMissingNode()) {
						for (JsonNode filter : node.path("filters")) {
							String type = filter.path("type").asString("");
							if (!type.isBlank()) {
								return policy + ":" + type;
							}
						}
					}
				}
			}
		}
		return "intervened";
	}

	private record ApplyRequest(List<ContentBlock> content, String source) {
	}

	private record ContentBlock(TextBlock text) {
	}

	private record TextBlock(String text) {
	}

	/**
	 * Computes the SHA-256 hex fingerprint of a payload for spend-audit
	 * correlation without retaining the payload itself.
	 *
	 * @param payload screened text, never {@code null}
	 * @return hex fingerprint, never {@code null}
	 */
	static String fingerprint(String payload) {
		try {
			java.security.MessageDigest digest =
					java.security.MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(
					digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException missing) {
			throw new IllegalStateException("SHA-256 unavailable", missing);
		}
	}
}
