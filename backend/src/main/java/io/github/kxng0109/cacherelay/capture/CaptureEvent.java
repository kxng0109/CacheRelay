package io.github.kxng0109.cacherelay.capture;

import java.time.Instant;
import java.util.UUID;

/**
 * One immutable capture candidate built on the completing thread. Payload
 * strings travel uncapped here; the writer truncates before redaction so
 * scan cost stays bounded.
 *
 * @param requestId        stable request id (sampling key), never {@code null}
 * @param ownerId          owning tenant, or {@code null} when unknown
 * @param keyHash          calling key hash for allowlist matching, or {@code null}
 * @param model            requested model, never {@code null}
 * @param provider         winning provider, or {@code null} on total failure
 * @param promptJson       request JSON as seen by the proxy, never {@code null}
 * @param outputJson       completion JSON (or assembled stream), or {@code null}
 * @param promptTokens     prompt tokens, or {@code null} when unknown
 * @param completionTokens completion tokens, or {@code null} when unknown
 * @param streaming        whether the output streamed
 * @param capturedAt       completion time, never {@code null}
 */
public record CaptureEvent(
		UUID requestId,
		String ownerId,
		String keyHash,
		String model,
		String provider,
		String promptJson,
		String outputJson,
		Long promptTokens,
		Long completionTokens,
		boolean streaming,
		Instant capturedAt
) {
}
