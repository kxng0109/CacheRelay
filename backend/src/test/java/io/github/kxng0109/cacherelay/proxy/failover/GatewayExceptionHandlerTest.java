package io.github.kxng0109.cacherelay.proxy.failover;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link GatewayExceptionHandler}: the mapping of the failover exception to 502, 503, 504, and the
 * passthrough of a specific upstream status, always with generic client safe messages inside the standard error
 * envelope.
 */
@DisplayName("GatewayExceptionHandler")
class GatewayExceptionHandlerTest {

	private final GatewayExceptionHandler handler = new GatewayExceptionHandler();

	@Test
	@DisplayName("generic failure maps to 502 Bad Gateway")
	void mapsGenericFailureTo502() {
		ResponseEntity<Map<String, Object>> response =
				handler.handleUpstreamUnavailable(new UpstreamUnavailableException("all failed", null, false, false));

		assertEquals(502, response.getStatusCode().value());
		assertEquals("no upstream provider could serve this request", errorMessage(response));
	}

	@Test
	@DisplayName("service unavailable maps to 503")
	void mapsUnavailableTo503() {
		ResponseEntity<Map<String, Object>> response =
				handler.handleUpstreamUnavailable(
						new UpstreamUnavailableException("nothing reachable", null, true, false));

		assertEquals(503, response.getStatusCode().value());
		assertEquals("upstream service unavailable", errorMessage(response));
	}

	@Test
	@DisplayName("timeout maps to 504 Gateway Timeout")
	void mapsTimeoutTo504() {
		ResponseEntity<Map<String, Object>> response =
				handler.handleUpstreamUnavailable(new UpstreamUnavailableException("slow", null, false, true));

		assertEquals(504, response.getStatusCode().value());
		assertEquals("upstream request timed out", errorMessage(response));
	}

	@Test
	@DisplayName("an explicit upstream status is passed through")
	void passesThroughUpstreamStatus() {
		ResponseEntity<Map<String, Object>> response =
				handler.handleUpstreamUnavailable(
						new UpstreamUnavailableException("rejected", null, false, false, 401));

		assertEquals(401, response.getStatusCode().value());
		assertEquals("the upstream provider rejected the request", errorMessage(response));
	}

	@Test
	@DisplayName("timeout wins over the low priority flags when both are set")
	void timeoutTakesPrecedenceOverUnavailable() {
		ResponseEntity<Map<String, Object>> response =
				handler.handleUpstreamUnavailable(new UpstreamUnavailableException("mixed", null, true, true));

		assertEquals(504, response.getStatusCode().value());
	}

	@Test
	@DisplayName("event-stream Accept still renders the JSON error instead of 406")
	void eventStreamAcceptRendersJsonError() throws Exception {
		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new FailingController())
				.setControllerAdvice(handler)
				.build();

		mockMvc.perform(get("/probe")
						.accept(MediaType.parseMediaType("text/event-stream")))
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.error.message").value("upstream service unavailable"));
	}

	@RestController
	static class FailingController {
		@GetMapping("/probe")
		Map<String, Object> probe() {
			throw new UpstreamUnavailableException("nothing reachable", null, true, false);
		}
	}

	@SuppressWarnings("unchecked")
	private static String errorMessage(ResponseEntity<Map<String, Object>> response) {
		Map<String, Object> error = (Map<String, Object>) response.getBody().get("error");
		return (String) error.get("message");
	}
}