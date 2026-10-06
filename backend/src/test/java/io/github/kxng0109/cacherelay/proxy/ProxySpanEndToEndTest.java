package io.github.kxng0109.cacherelay.proxy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.kxng0109.cacherelay.SharedContainersBase;
import io.github.kxng0109.cacherelay.auth.UserAccount;
import io.github.kxng0109.cacherelay.auth.UserAccountRepository;
import io.github.kxng0109.cacherelay.security.ratelimit.KeyManagementService;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves custom span attributes end to end against a live server: a real
 * request's finished server span carries the routing facts the controller
 * recorded (alias, outcome, error reason). Spans are captured with an
 * in-memory exporter; the OTLP endpoint stays default (no collector in
 * tests, export fails open and cannot affect the request).
 */
@DisplayName("Proxy span attributes end to end")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProxySpanEndToEndTest extends SharedContainersBase {

	@DynamicPropertySource
	static void sharedContainers(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", SharedContainersBase::postgresJdbcUrl);
		registry.add("spring.datasource.username", SharedContainersBase::postgresUsername);
		registry.add("spring.datasource.password", SharedContainersBase::postgresPassword);
		registry.add("spring.data.redis.host", SharedContainersBase::redisHost);
		registry.add("spring.data.redis.port", SharedContainersBase::redisPort);
		// Flush spans fast: the OTLP batch default (5s) would stall the test.
		registry.add("management.opentelemetry.tracing.export.schedule-delay", () -> "100ms");
	}

	@LocalServerPort
	private int port;

	@Autowired
	private UserAccountRepository accounts;

	@Autowired
	private KeyManagementService keys;

	private String apiKey;

	@BeforeEach
	void drainExporterAndMintKey() {
		RecordingExporterConfig.SPANS.clear();
		UserAccount account = accounts.save(new UserAccount("span-e2e", null, null, false));
		apiKey = keys.createKey("span-e2e", "span-e2e-key", 0, 0,
				Set.of(), Set.of(), Set.of(), Set.of(),
				Set.of(), Set.of(), Set.of(), Set.of(),
				null, null, null, null, account.getId()).plaintextKey();
	}

	@Test
	@DisplayName("unknown-model 404 span carries model, outcome, and reason")
	void unknownModelSpanIsTagged() throws Exception {
		String body = "{\"model\":\"ghost-model-xyz\",\"messages\":["
				+ "{\"role\":\"user\",\"content\":\"hi\"}]}";

		int status = post("/v1/chat/completions", body);

		assertThat(status).isEqualTo(404);
		// No alias resolves on this path, so the model (not the alias) is recorded.
		SpanData span = awaitSpanWith("cacherelay.model", "ghost-model-xyz");
		assertThat(span.getAttributes().get(AttributeKey.stringKey("cacherelay.model")))
				.isEqualTo("ghost-model-xyz");
		assertThat(span.getAttributes().get(AttributeKey.stringKey("cacherelay.outcome")))
				.isEqualTo("error");
		assertThat(span.getAttributes().get(AttributeKey.stringKey("cacherelay.error.reason")))
				.isEqualTo("unknown_model");
	}

	private int post(String path, String body) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://127.0.0.1:" + port + path))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + apiKey)
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.timeout(Duration.ofSeconds(20))
				.build();
		HttpResponse<Void> response = HttpClient.newHttpClient()
				.send(request, HttpResponse.BodyHandlers.discarding());
		return response.statusCode();
	}

	private static SpanData awaitSpanWith(String key, String value) throws InterruptedException {
		AttributeKey<String> attribute = AttributeKey.stringKey(key);
		long deadline = System.currentTimeMillis() + 15_000L;
		while (System.currentTimeMillis() < deadline) {
			for (SpanData span : RecordingExporterConfig.SPANS) {
				if (value.equals(span.getAttributes().get(attribute))) {
					return span;
				}
			}
			Thread.sleep(250L);
		}
		throw new AssertionError("no span carried " + key + "=" + value + " within 15s (saw "
				+ RecordingExporterConfig.SPANS.size() + " spans: "
				+ RecordingExporterConfig.SPANS.stream()
						.map(span -> span.getName() + span.getAttributes().asMap().keySet())
						.toList()
				+ ")");
	}

	/**
	 * In-memory span sink for tests (the OTLP exporter stays configured but
	 * collector-less, proving fail-open export alongside capture).
	 */
	@TestConfiguration
	static class RecordingExporterConfig {

		static final List<SpanData> SPANS = new CopyOnWriteArrayList<>();

		@Bean
		SpanExporter recordingExporter() {
			return new SpanExporter() {
				@Override
				public CompletableResultCode export(Collection<SpanData> spans) {
					SPANS.addAll(spans);
					return CompletableResultCode.ofSuccess();
				}

				@Override
				public CompletableResultCode shutdown() {
					return CompletableResultCode.ofSuccess();
				}

				@Override
				public CompletableResultCode flush() {
					return CompletableResultCode.ofSuccess();
				}
			};
		}
	}
}
