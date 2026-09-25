package io.github.kxng0109.cacherelay.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * Live point-in-time telemetry for one Redis tier, read from {@code INFO}.
 *
 * <p>Metrics are {@code null} when the tier is unreachable; callers must check
 * {@link #reachable()} first. Percentages are absent when the tier sets no
 * {@code maxmemory} bound.</p>
 *
 * @param reachable         whether the tier answered {@code INFO}
 * @param usedBytes         bytes allocated, or {@code null} when unknown
 * @param maxBytes          configured {@code maxmemory} bound in bytes, or {@code null}
 * @param usedPercent       {@code usedBytes} share of the bound, or {@code null}
 * @param maxmemoryPolicy   live eviction policy (for example {@code noeviction}),
 *                          or {@code null} when unknown
 * @param evictedKeysTotal  keys evicted under memory pressure, or {@code null}
 * @param keyspaceHits      successful key lookups, or {@code null}
 * @param keyspaceMisses    failed key lookups, or {@code null}
 */
@Schema(name = "TierStats", description = "Live INFO telemetry for one Redis tier")
public record TierStats(
		@Schema(description = "Whether the tier answered INFO", example = "true")
		boolean reachable,

		@Schema(description = "Bytes allocated", example = "123456")
		@Nullable Long usedBytes,

		@Schema(description = "Configured maxmemory bound in bytes", example = "402653184")
		@Nullable Long maxBytes,

		@Schema(description = "Used share of the bound in percent", example = "12.5")
		@Nullable Double usedPercent,

		@Schema(description = "Live eviction policy", example = "allkeys-lru")
		@Nullable String maxmemoryPolicy,

		@Schema(description = "Keys evicted under memory pressure", example = "7")
		@Nullable Long evictedKeysTotal,

		@Schema(description = "Successful key lookups", example = "90")
		@Nullable Long keyspaceHits,

		@Schema(description = "Failed key lookups", example = "12")
		@Nullable Long keyspaceMisses
) {

	/**
	 * Creates the unreachable marker with every metric absent.
	 *
	 * @return unreachable tier stats, never {@code null}
	 */
	public static TierStats unreachable() {
		return new TierStats(false, null, null, null, null, null, null, null);
	}
}
