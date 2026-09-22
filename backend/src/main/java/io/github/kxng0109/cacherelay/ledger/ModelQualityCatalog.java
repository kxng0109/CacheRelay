package io.github.kxng0109.cacherelay.ledger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Read side of the curated model quality catalog.
 *
 * <p>The table is tiny and curated rarely, so the catalog loads it wholesale
 * into a Caffeine cache with a short expiry and matches in memory. A miss
 * reads as unrated (empty), never as a default tier.</p>
 */
@Component
public class ModelQualityCatalog {

	private final ModelQualityRepository repository;
	private final Cache<String, Map<String, ModelQualityEntry>> snapshotCache;

	/**
	 * @param repository          the quality repository
	 * @param snapshotTtlMinutes  snapshot TTL in minutes
	 * @param snapshotMaximumSize snapshot cache maximum entries
	 */
	@Autowired
	public ModelQualityCatalog(
			ModelQualityRepository repository,
			@Value("${gateway.pricing.snapshot-ttl-minutes:15}") long snapshotTtlMinutes,
			@Value("${gateway.pricing.snapshot-maximum-size:1}") int snapshotMaximumSize
	) {
		this.repository = repository;
		this.snapshotCache = Caffeine.newBuilder()
		                             .maximumSize(Math.max(1, snapshotMaximumSize))
		                             .expireAfterWrite(Duration.ofMinutes(Math.max(1L, snapshotTtlMinutes)))
		                             .build();
	}

	/**
	 * Creates the catalog with default snapshot ceilings (tests).
	 *
	 * @param repository the quality repository
	 */
	public ModelQualityCatalog(ModelQualityRepository repository) {
		this(repository, 15L, 1);
	}

	/**
	 * Looks up the curated quality of a model id.
	 *
	 * @param modelId the model id to look up
	 * @return the curated entry, or an empty optional when the model is unrated
	 */
	public Optional<ModelQualityEntry> qualityOf(String modelId) {
		if (modelId == null || modelId.isBlank()) {
			return Optional.empty();
		}
		Map<String, ModelQualityEntry> byModelId = snapshotCache.get("quality", key -> load());
		return Optional.ofNullable(byModelId.get(modelId));
	}

	/**
	 * Drops the cached snapshot so the next lookup sees fresh rows. Called after curation writes.
	 */
	public void invalidate() {
		snapshotCache.invalidateAll();
	}

	private Map<String, ModelQualityEntry> load() {
		return repository.findAll().stream().collect(Collectors.toMap(
				ModelQualityEntity::getModelId, ModelQualityEntry::from, (first, second) -> second));
	}
}
