package io.github.kxng0109.cacherelay.ledger;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * The curated quality of one model, in the form routing policy consumes.
 *
 * <p>This record exists so the catalog can hand out immutable value objects
 * without exposing the JPA entity. Absence of a row reads as empty rather than
 * a default tier: unrated models are never substituted on quality grounds.</p>
 *
 * @param modelId       exact model id in the quality catalog
 * @param tier          curated quality tier
 * @param benchmarkRefs benchmark references backing the tier, may be {@code null}
 * @param updatedAt     when the row was written
 */
public record ModelQualityEntry(
		String modelId,
		ModelQualityTier tier,
		@Nullable String benchmarkRefs,
		Instant updatedAt
) {

	/**
	 * @param entity the persisted row
	 * @return the immutable value form
	 */
	static ModelQualityEntry from(ModelQualityEntity entity) {
		return new ModelQualityEntry(
				entity.getModelId(),
				entity.getTier(),
				entity.getBenchmarkRefs(),
				entity.getUpdatedAt()
		);
	}
}
