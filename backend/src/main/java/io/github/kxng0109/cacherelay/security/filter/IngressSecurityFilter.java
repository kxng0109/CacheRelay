package io.github.kxng0109.cacherelay.security.filter;

import io.github.kxng0109.cacherelay.security.guardrail.common.GuardrailMode;
import io.github.kxng0109.cacherelay.security.guardrail.common.GuardrailProperties;
import io.github.kxng0109.cacherelay.security.guardrail.injection.InjectionScanResult;
import io.github.kxng0109.cacherelay.security.guardrail.injection.PromptInjectionException;
import io.github.kxng0109.cacherelay.security.guardrail.injection.PromptInjectionScanner;
import io.github.kxng0109.cacherelay.security.guardrail.pii.EphemeralPiiVault;
import io.github.kxng0109.cacherelay.security.guardrail.pii.PiiAnonymizer;
import io.github.kxng0109.cacherelay.security.guardrail.secret.IngressSecretScanner;
import io.github.kxng0109.cacherelay.security.guardrail.secret.SecretLeakageException;
import io.github.kxng0109.cacherelay.security.guardrail.secret.SecretScanResult;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Ingress servlet filter executing real-time secret leakage scanning, prompt injection defense, and PII anonymization
 * prior to upstream model execution.
 *
 * <p>Registered at order {@value #ORDER}, running directly after {@link KeyAuthFilter}. One posture covers every
 * ingress surface: chat and embeddings bodies run the identical three stages, so a secret smuggled in an embedding
 * input is rejected or anonymized exactly like one in a chat prompt.</p>
 */
public class IngressSecurityFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(IngressSecurityFilter.class);

	public static final int ORDER = 2;
	public static final String TARGET_PATH_CHAT = "/v1/chat/completions";
	public static final String TARGET_PATH_EMBEDDINGS = "/v1/embeddings";
	public static final String PII_VAULT_ATTRIBUTE = "cacherelay.piiVault";

	private final IngressSecretScanner secretScanner;
	private final PromptInjectionScanner injectionScanner;
	private final PiiAnonymizer piiAnonymizer;
	private final GuardrailProperties properties;
	private final ObjectMapper objectMapper;

	private volatile @Nullable MeterRegistry meterRegistry;

	public IngressSecurityFilter(
			IngressSecretScanner secretScanner,
			PromptInjectionScanner injectionScanner,
			PiiAnonymizer piiAnonymizer,
			GuardrailProperties properties,
			ObjectMapper objectMapper
	) {
		this.secretScanner = secretScanner;
		this.injectionScanner = injectionScanner;
		this.piiAnonymizer = piiAnonymizer;
		this.properties = properties != null ? properties : new GuardrailProperties();
		this.objectMapper = objectMapper;
	}

	/**
	 * Wires Micrometer telemetry when present. Optional on purpose: unit-constructed
	 * filters keep working with failure counting silently skipped.
	 *
	 * @param meterRegistry the registry, if available
	 */
	@Autowired(required = false)
	public void setMeterRegistry(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	/**
	 * Whether the request path carries a guardrailed ingress body.
	 *
	 * @param path request URI, possibly {@code null}
	 * @return {@code true} for chat and embeddings
	 */
	static boolean isGuarded(@Nullable String path) {
		return TARGET_PATH_CHAT.equals(path) || TARGET_PATH_EMBEDDINGS.equals(path);
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain
	) throws ServletException, IOException {
		if (!HttpMethod.POST.matches(request.getMethod()) || !isGuarded(request.getRequestURI())) {
			filterChain.doFilter(request, response);
			return;
		}

		CachedBodyHttpServletRequest wrapper =
				WebUtils.getNativeRequest(request, CachedBodyHttpServletRequest.class);
		byte[] bodyBytes = wrapper != null ? wrapper.getContentAsByteArray() : new byte[0];
		if (bodyBytes.length == 0) {
			filterChain.doFilter(request, response);
			return;
		}

		String textPayload = new String(bodyBytes, StandardCharsets.UTF_8);

		// 1. Ingress Secret & Credential Leakage Scanner
		if (properties.isSecretScanningEnabled()) {
			SecretScanResult secretResult;
			try {
				secretResult = secretScanner.scan(bodyBytes, textPayload);
			} catch (RuntimeException failed) {
				failClosed(response, "secret", failed);
				return;
			}
			if (secretResult.detected()) {
				if (properties.getMode() == GuardrailMode.ENFORCE) {
					log.warn(
							"Ingress secret leakage blocked: rule={}, path={}",
							secretResult.ruleId(),
							secretResult.jsonPath()
					);
					writeProblemDetail(
							response,
							new SecretLeakageException(secretResult).toProblemDetail(request.getRequestURI())
					);
					return;
				} else {
					log.warn("Ingress secret leakage detected (AUDIT_ONLY): rule={}", secretResult.ruleId());
					request.setAttribute("cacherelay.guardrail.secretLeakage", secretResult);
				}
			}
		}

		// 2. Prompt Injection & Jailbreak Defense
		if (properties.isPromptInjectionDefenseEnabled()) {
			InjectionScanResult injectionResult;
			try {
				injectionResult = injectionScanner.scan(textPayload);
			} catch (RuntimeException failed) {
				failClosed(response, "injection", failed);
				return;
			}
			if (injectionResult.detected()) {
				if (properties.getMode() == GuardrailMode.ENFORCE) {
					log.warn(
							"Prompt injection blocked: category={}, matched={}",
							injectionResult.category(),
							injectionResult.matchedPattern()
					);
					writeProblemDetail(
							response,
							new PromptInjectionException(injectionResult).toProblemDetail(request.getRequestURI())
					);
					return;
				} else {
					log.warn("Prompt injection detected (AUDIT_ONLY): category={}", injectionResult.category());
					request.setAttribute("cacherelay.guardrail.promptInjection", injectionResult);
				}
			}
		}

		// 3. PII Anonymization & Ephemeral Vault
		if (properties.isPiiAnonymizationEnabled()) {
			EphemeralPiiVault vault = new EphemeralPiiVault();
			String anonymized;
			try {
				anonymized = piiAnonymizer.anonymize(textPayload, vault);
			} catch (RuntimeException failed) {
				vault.close();
				failClosed(response, "pii", failed);
				return;
			}

			if (!vault.isEmpty()) {
				request.setAttribute(PII_VAULT_ATTRIBUTE, vault);
				byte[] anonymizedBytes = anonymized.getBytes(StandardCharsets.UTF_8);
				HttpServletRequest anonymizedRequest = new AnonymizedBodyHttpServletRequest(request, anonymizedBytes);
				filterChain.doFilter(anonymizedRequest, response);
				return;
			} else {
				vault.close();
			}
		}

		filterChain.doFilter(request, response);
	}

	/**
	 * Denies the request when a guardrail stage itself fails: a scanner that throws
	 * must never wave traffic through unscanned. Counts the failure for alerting.
	 *
	 * @param response servlet response
	 * @param stage    failing stage ({@code secret}, {@code injection}, or {@code pii})
	 * @param failure  scanner failure, logged server-side only
	 */
	private void failClosed(HttpServletResponse response, String stage, RuntimeException failure)
			throws IOException {
		log.warn("Guardrail {} scan failed closed: {}", stage, failure.getMessage());
		MeterRegistry registry = this.meterRegistry;
		if (registry != null) {
			registry.counter("guardrail_scan_failures_total", "stage", stage).increment();
		}
		ProblemDetail problem = ProblemDetail.forStatus(500);
		problem.setTitle("Guardrail evaluation failure");
		problem.setDetail("Guardrail evaluation failed; request denied.");
		writeProblemDetail(response, problem);
	}

	private void writeProblemDetail(HttpServletResponse response, ProblemDetail problem) throws IOException {
		response.setStatus(problem.getStatus());
		response.setContentType("application/problem+json");
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		objectMapper.writeValue(response.getOutputStream(), problem);
	}
}
