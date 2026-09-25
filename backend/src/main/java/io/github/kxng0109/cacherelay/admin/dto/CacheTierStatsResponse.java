package io.github.kxng0109.cacherelay.admin.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Live telemetry for both Redis tiers behind one freshness stamp.
 *
 * @param generatedAt when the snapshot was taken, never {@code null}
 * @param accounting  spend-accounting tier ({@code noeviction}), never {@code null}
 * @param cache       cache tier L1/L2/replay-hot ({@code allkeys-lru}), never {@code null}
 */
@Schema(name = "CacheTierStatsResponse", description = "Live INFO telemetry for both Redis tiers")
public record CacheTierStatsResponse(
		@Schema(description = "Snapshot instant (UTC)")
		Instant generatedAt,

		@Schema(description = "Spend-accounting tier telemetry")
		TierStats accounting,

		@Schema(description = "Cache tier telemetry")
		TierStats cache
) {
}
