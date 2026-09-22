package io.github.kxng0109.cacherelay.admin.dto;

import org.jspecify.annotations.Nullable;

import io.github.kxng0109.cacherelay.ledger.ModelQualityTier;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request payload for curating a model's quality tier.
 *
 * @param tier          curated quality tier, never {@code null}
 * @param benchmarkRefs benchmark references backing the tier in
 *                      {@code INDEX:score@YYYY-MM-DD} segments separated by {@code ;},
 *                      may be {@code null}; blank reads as {@code null}
 */
@Schema(name = "SetQualityRequest", description = "Payload for curating a model quality tier")
public record SetQualityRequest(
		@Schema(description = "Curated quality tier", example = "FRONTIER",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "tier must not be null")
		ModelQualityTier tier,

		@Schema(description = "Benchmark references, INDEX:score@YYYY-MM-DD segments separated by ;",
				example = "AA-Index:72.3@2026-09-01")
		@Nullable
		@Size(max = 2000, message = "benchmarkRefs must be at most 2000 characters")
		@Pattern(regexp = "[\\w\\s:;@.,+\\-/%()]*", message = "benchmarkRefs contains illegal characters")
		String benchmarkRefs
) {
}
