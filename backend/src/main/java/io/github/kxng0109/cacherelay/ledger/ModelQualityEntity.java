package io.github.kxng0109.cacherelay.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * One curated quality row: the admin-assigned tier of a model with the benchmark
 * references backing it.
 *
 * <p>The table is curated independently of the pricing sync, so daily price
 * refreshes never clobber tiers. A model without a row is unrated, which the
 * read side reports as empty rather than a default tier.</p>
 */
@Entity
@Table(name = "model_quality")
@Getter
public class ModelQualityEntity {

	@Id
	@Column(name = "model_id", length = 128)
	private String modelId;

	@Enumerated(EnumType.STRING)
	@Column(name = "quality_tier", nullable = false, length = 16)
	private ModelQualityTier tier;

	@Column(name = "benchmark_refs", length = 2000)
	private @Nullable String benchmarkRefs;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/**
	 * No argument constructor required by the JPA specification.
	 */
	protected ModelQualityEntity() {
	}

	/**
	 * @param modelId       exact model id in the quality catalog
	 * @param tier          curated quality tier, never {@code null}
	 * @param benchmarkRefs benchmark references backing the tier, may be {@code null}
	 * @param updatedAt     when the row was written
	 */
	public ModelQualityEntity(
			String modelId,
			ModelQualityTier tier,
			@Nullable String benchmarkRefs,
			Instant updatedAt
	) {
		this.modelId = modelId;
		this.tier = tier;
		this.benchmarkRefs = benchmarkRefs;
		this.updatedAt = updatedAt;
	}
}
