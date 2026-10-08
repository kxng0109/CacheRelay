package io.github.kxng0109.cacherelay.admin;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared Alertmanager batch validation for both webhook receivers.
 *
 * <p>Batch shape, bound, rejection body, and acknowledgment stay identical across the authenticated and the
 * secret-guarded receiver. Secret handling and empty 404/401 responses stay in the controllers.</p>
 */
public final class AlertBatch {

	private AlertBatch() {
	}

	/**
	 * Checks whether a batch must be rejected.
	 *
	 * @param body webhook JSON, possibly {@code null}
	 * @return true when the batch is missing, not an array, empty, or oversized
	 */
	public static boolean isBad(@Nullable JsonNode body) {
		return body == null || !body.isArray() || body.isEmpty()
				|| body.size() > AlertBatchLimits.MAX_ALERTS_PER_BATCH;
	}

	/**
	 * Builds the shared 400 rejection body.
	 *
	 * @param objectMapper JSON codec, never {@code null}
	 * @return {@code {"error":"batch must be a non-empty array of at most 100 alerts"}}
	 */
	public static JsonNode errorBody(ObjectMapper objectMapper) {
		return objectMapper.createObjectNode()
				.put("error", "batch must be a non-empty array of at most "
						+ AlertBatchLimits.MAX_ALERTS_PER_BATCH + " alerts");
	}
}
