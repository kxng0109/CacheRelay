package io.github.kxng0109.cacherelay.admin.dto;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

import io.github.kxng0109.cacherelay.ledger.ModelQualityEntity;
import io.github.kxng0109.cacherelay.ledger.ModelQualityTier;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The curated quality rating of one model.
 *
 * @param modelId       exact model id in the quality catalog
 * @param tier          curated quality tier
 * @param benchmarkRefs benchmark references backing the tier, may be {@code null}
 * @param updatedAt     when the rating was written
 */
@Schema(name = "QualityResponse", description = "Curated quality rating of one model")
public record QualityResponse(
		@Schema(description = "Exact model id", example = "gpt-5.6-luna")
		String modelId,

		@Schema(description = "Curated quality tier", example = "FRONTIER")
		ModelQualityTier tier,

		@Schema(description = "Benchmark references backing the tier, null when unrecorded")
		@Nullable String benchmarkRefs,

		@Schema(description = "When the rating was written")
		Instant updatedAt
) {

	/**
	 * @param entity the persisted row
	 * @return the response form
	 */
	public static QualityResponse from(ModelQualityEntity entity) {
		return new QualityResponse(
				entity.getModelId(),
				entity.getTier(),
				entity.getBenchmarkRefs(),
				entity.getUpdatedAt());
	}
}
