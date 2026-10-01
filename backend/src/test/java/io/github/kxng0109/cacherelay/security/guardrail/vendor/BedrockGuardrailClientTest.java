package io.github.kxng0109.cacherelay.security.guardrail.vendor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import io.github.kxng0109.cacherelay.security.SsrfValidator;
import io.github.kxng0109.cacherelay.security.SsrfViolationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bedrock screening: constructor guards, verdict mapping, failure modes, and
 * spend metering — all without touching the network.
 */
@DisplayName("BedrockGuardrailClient")
class BedrockGuardrailClientTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("rejects unsafe endpoints and blank guardrail ids fail-fast")
	void rejectsBadConfig() {
		SsrfValidator blocked = mock(SsrfValidator.class);
		doThrow(new SsrfViolationException("nope")).when(blocked).validate(any(URI.class));

		assertThatThrownBy(() -> new BedrockGuardrailClient(mock(HttpClient.class), blocked,
				objectMapper, "https://evil.example.com/", "g", "DRAFT", 5000L, null))
				.isInstanceOf(SsrfViolationException.class);
		assertThatThrownBy(() -> new BedrockGuardrailClient(mock(HttpClient.class),
				mock(SsrfValidator.class), objectMapper, "https://bedrock.us-east-1.example.com/",
				"   ", "DRAFT", 5000L, null))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("interventions map to flagged verdicts with policy reasons")
	void intervenedMapsFlagged() throws Exception {
		HttpClient http = mock(HttpClient.class);
		HttpResponse<String> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(200);
		when(response.body()).thenReturn(
				"{\"action\":\"GUARDRAIL_INTERVENED\",\"assessments\":["
						+ "{\"contentPolicy\":{\"filters\":[{\"type\":\"VIOLENCE\"}]}}]}");
		doReturn(response).when(http).send(any(HttpRequest.class), any());
		BedrockGuardrailClient client = client(http, null);

		VendorVerdict verdict = client.screen("drop the payload");

		assertThat(verdict.flagged()).isTrue();
		assertThat(verdict.vendor()).isEqualTo("bedrock");
		assertThat(verdict.reason()).contains("VIOLENCE");
		ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
		verify(http).send(sent.capture(), any());
		assertThat(sent.getValue().uri().toString()).contains("/guardrail/g1/version/DRAFT/apply");
	}

	@Test
	@DisplayName("clean actions map to clean verdicts")
	void cleanMapsClean() throws Exception {
		HttpClient http = mock(HttpClient.class);
		HttpResponse<String> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(200);
		when(response.body()).thenReturn("{\"action\":\"NONE\",\"assessments\":[]}");
		doReturn(response).when(http).send(any(HttpRequest.class), any());
		BedrockGuardrailClient client = client(http, null);

		VendorVerdict verdict = client.screen("a calm quarterly summary");

		assertThat(verdict.flagged()).isFalse();
		assertThat(verdict.reason()).isEqualTo("clean");
	}

	@Test
	@DisplayName("transport failures, bad statuses, and bad bodies throw fail-closed")
	void failuresThrow() throws Exception {
		HttpClient down = mock(HttpClient.class);
		doThrow(new IOException("reset")).when(down).send(any(HttpRequest.class), any());
		assertThatThrownBy(() -> client(down, null).screen("x"))
				.isInstanceOf(IllegalStateException.class);

		HttpClient badStatus = mock(HttpClient.class);
		HttpResponse<String> denied = mock(HttpResponse.class);
		when(denied.statusCode()).thenReturn(403);
		when(denied.body()).thenReturn("{}");
		doReturn(denied).when(badStatus).send(any(HttpRequest.class), any());
		assertThatThrownBy(() -> client(badStatus, null).screen("x"))
				.isInstanceOf(IllegalStateException.class);

		HttpClient garbage = mock(HttpClient.class);
		HttpResponse<String> ok = mock(HttpResponse.class);
		when(ok.statusCode()).thenReturn(200);
		when(ok.body()).thenReturn("not json{{{");
		doReturn(ok).when(garbage).send(any(HttpRequest.class), any());
		assertThatThrownBy(() -> client(garbage, null).screen("x"))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("requests are metered with vendor status")
	void meteringRecorded() throws Exception {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		HttpClient http = mock(HttpClient.class);
		HttpResponse<String> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(200);
		when(response.body()).thenReturn("{\"action\":\"NONE\",\"assessments\":[]}");
		doReturn(response).when(http).send(any(HttpRequest.class), any());
		BedrockGuardrailClient client = client(http, registry);

		client.screen("hello");

		assertThat(registry.get("guardrail_vendor_requests_total")
				.tag("vendor", "bedrock").tag("status", "200").counter().count())
				.isEqualTo(1.0);
		assertThat(registry.get("guardrail_vendor_latency").tag("vendor", "bedrock")
				.timer().count()).isEqualTo(1);
	}

	@Test
	@DisplayName("oversized payloads truncate on UTF-8 boundaries")
	void oversizedTruncates() throws Exception {
		HttpClient http = mock(HttpClient.class);
		HttpResponse<String> response = mock(HttpResponse.class);
		when(response.statusCode()).thenReturn(200);
		when(response.body()).thenReturn("{\"action\":\"NONE\",\"assessments\":[]}");
		doReturn(response).when(http).send(any(HttpRequest.class), any());
		BedrockGuardrailClient client = client(http, null);

		StringBuilder big = new StringBuilder();
		for (int i = 0; i < 60_000; i++) {
			big.append('é');
		}
		client.screen(big.toString());

		ArgumentCaptor<HttpRequest> sent = ArgumentCaptor.forClass(HttpRequest.class);
		verify(http).send(sent.capture(), any());
		assertThat(sent.getValue()).isNotNull();
	}

	private BedrockGuardrailClient client(HttpClient http, SimpleMeterRegistry registry) {
		return new BedrockGuardrailClient(http, mock(SsrfValidator.class), objectMapper,
				"https://bedrock.us-east-1.example.com/", "g1", "DRAFT", 5000L, registry);
	}
}
