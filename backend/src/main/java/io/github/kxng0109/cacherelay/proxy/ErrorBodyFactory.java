package io.github.kxng0109.cacherelay.proxy;

import io.github.kxng0109.cacherelay.budget.BudgetDecision;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single factory for {@code {"error":{...}}} response envelopes.
 *
 * <p>Three shapes coexist on the wire and must stay byte-identical: code-first (gateway handler), message-first
 * (model catalog 401), and message-only (budget denials, streaming errors). LinkedHashMap preserves each shape's key
 * order; callers pick the shape, never rebuild it.</p>
 */
public final class ErrorBodyFactory {

	private ErrorBodyFactory() {
	}

	/**
	 * Builds a code-first envelope for gateway handler responses.
	 *
	 * @param message client-facing message
	 * @param code machine-readable code, omitted when null or blank (legacy codeless shape)
	 * @return {@code {"error":{"code","message"}}} body
	 */
	public static Map<String, Object> mapCoded(String message, @Nullable String code) {
		Map<String, Object> error = new LinkedHashMap<>();
		if (code != null && !code.isBlank()) {
			error.put("code", code);
		}
		error.put("message", message);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", error);
		return body;
	}

	/**
	 * Builds a message-first envelope for the model catalog 401.
	 *
	 * @param message client-facing message
	 * @param code machine-readable code
	 * @return {@code {"error":{"message","code"}}} body
	 */
	public static Map<String, Object> mapMessageFirst(String message, String code) {
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("message", message);
		error.put("code", code);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", error);
		return body;
	}

	/**
	 * Builds a message-only envelope for budget denials.
	 *
	 * @param message client-facing message
	 * @return {@code {"error":{"message"}}} body
	 */
	public static Map<String, Object> mapMessage(String message) {
		return Map.of("error", Map.of("message", message));
	}

	/**
	 * Serializes a streaming error body with the mapper, never by concatenation: messages frequently embed client
	 * input and raw interpolation would let a quote break the JSON shape. A serialization failure degrades to a
	 * static body — the failure path itself never throws.
	 *
	 * @param mapper Jackson mapper, never {@code null}
	 * @param message client-facing message
	 * @return serialized error JSON
	 */
	public static String streamMessage(ObjectMapper mapper, String message) {
		try {
			ObjectNode error = mapper.createObjectNode();
			error.putObject("error").put("message", message);
			return mapper.writeValueAsString(error);
		} catch (RuntimeException failed) {
			return "{\"error\":{\"message\":\"request failed\"}}";
		}
	}

	/**
	 * Renders the shared budget-denial message.
	 *
	 * @param denied denial carrying level and window
	 * @return {@code budget exhausted (LEVEL WINDOW)} text
	 */
	public static String denyMessage(BudgetDecision.Denied denied) {
		return "budget exhausted (" + denied.level() + " " + denied.window() + ")";
	}

	/**
	 * Builds the shared budget-denial headers.
	 *
	 * @param denied denial carrying level and window
	 * @param retryAfter seconds for {@code Retry-After}, already floored to at least 1
	 * @return headers with JSON type, retry, and the {@code X-Budget-*} family
	 */
	public static HttpHeaders denyHeaders(BudgetDecision.Denied denied, long retryAfter) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
		headers.set("X-Budget-Remaining", "0");
		headers.set("X-Budget-Reset", Long.toString(System.currentTimeMillis() / 1000L + retryAfter));
		headers.set("X-Budget-Level", denied.level());
		headers.set("X-Budget-Window", denied.window());
		return headers;
	}
}
