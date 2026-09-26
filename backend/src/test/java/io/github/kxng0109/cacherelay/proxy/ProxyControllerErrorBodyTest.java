package io.github.kxng0109.cacherelay.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ProxyController error serialization")
class ProxyControllerErrorBodyTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("quoted client input stays well-formed JSON in error bodies")
	void quotedModelParsesAsJson() throws Exception {
		String body = ProxyController.errorBody(objectMapper, "unknown model: a\"b\\c");

		JsonNode parsed = objectMapper.readTree(body.getBytes(StandardCharsets.UTF_8));

		assertThat(parsed.path("error").path("message").asString())
				.isEqualTo("unknown model: a\"b\\c");
	}

	@Test
	@DisplayName("error-body serializer failures degrade to a static body, never throw")
	void errorBodyFailureDegrades() throws Exception {
		ObjectMapper failing = mock(ObjectMapper.class);
		ObjectMapper real = new ObjectMapper();
		when(failing.createObjectNode()).thenAnswer(invocation -> real.createObjectNode());
		when(failing.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));

		String body = ProxyController.errorBody(failing, "x");

		assertThat(objectMapper.readTree(body.getBytes(StandardCharsets.UTF_8))
				.path("error").path("message").asString()).isEqualTo("request failed");
	}

	@Test
	@DisplayName("completion bodies serialize the full contract with escaped values")
	void completionBodyContract() throws Exception {
		String body = ProxyController.completionBody(objectMapper, "a\"b", "hi", 3, 5);

		JsonNode parsed = objectMapper.readTree(body.getBytes(StandardCharsets.UTF_8));

		assertThat(parsed.path("model").asString()).isEqualTo("a\"b");
		assertThat(parsed.path("choices").get(0).path("message").path("content").asString())
				.isEqualTo("hi");
		assertThat(parsed.path("usage").path("total_tokens").asInt()).isEqualTo(8);
	}

	@Test
	@DisplayName("completion serializer failures reject with 500")
	void completionBodyFailureRejects() {
		ObjectMapper failing = mock(ObjectMapper.class);
		ObjectMapper real = new ObjectMapper();
		when(failing.createObjectNode()).thenAnswer(invocation -> real.createObjectNode());
		when(failing.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));

		assertThatThrownBy(() -> ProxyController.completionBody(failing, "m", "c", 1, 2))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}
}
