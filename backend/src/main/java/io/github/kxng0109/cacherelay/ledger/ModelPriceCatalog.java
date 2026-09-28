package io.github.kxng0109.cacherelay.ledger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.kxng0109.cacherelay.contracts.ProviderType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * Read side of the pricing catalog.
 *
 * <p>The full table is small (a few thousand rows at most), so the catalog
 * loads it wholesale into a Caffeine cache with a short expiry and matches in memory. The daily sync refreshes the
 * database; the cache picks the refresh up on its next reload. A miss never fails the request: the cost calculator gets
 * an empty optional and records zero.</p>
 *
 * <p>Matching follows the LiteLLM convention of exact ids with fallbacks:</p>
 * <ol>
 *   <li>the exact model id, preferring a row whose provider matches;</li>
 *   <li>the {@code provider/model} composite key;</li>
 *   <li>the longest registered model id that is a prefix of the reported one,
 *       which covers dated model ids such as {@code claude-sonnet-5-20251001}.</li>
 * </ol>
 */
@Slf4j
@Component
public class ModelPriceCatalog {

	private final ModelPricingRepository repository;
	private final Cache<String, Map<String, List<ModelPricingEntry>>> snapshotCache;
	private final MeterRegistry meterRegistry;

	/**
	 * @param repository          the pricing repository
	 * @param snapshotTtlMinutes  snapshot TTL in minutes
	 * @param snapshotMaximumSize snapshot cache maximum entries
	 */
	@Autowired
	public ModelPriceCatalog(
			ModelPricingRepository repository,
			@Value("${gateway.pricing.snapshot-ttl-minutes:15}") long snapshotTtlMinutes,
			@Value("${gateway.pricing.snapshot-maximum-size:1}") int snapshotMaximumSize,
			MeterRegistry meterRegistry
	) {
		this.repository = repository;
		this.snapshotCache = Caffeine.newBuilder()
		                             .maximumSize(Math.max(1, snapshotMaximumSize))
		                             .expireAfterWrite(Duration.ofMinutes(Math.max(1L, snapshotTtlMinutes)))
		                             .build();
		this.meterRegistry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
	}

	/**
	 * Creates the catalog with default snapshot ceilings (tests).
	 *
	 * @param repository the pricing repository
	 */
	public ModelPriceCatalog(ModelPricingRepository repository) {
		this(repository, 15L, 1, new SimpleMeterRegistry());
	}

	/**
	 * Creates the catalog with default snapshot ceilings and an explicit registry (tests).
	 *
	 * @param repository    the pricing repository
	 * @param meterRegistry registry for fallback-billing telemetry
	 */
	public ModelPriceCatalog(ModelPricingRepository repository, MeterRegistry meterRegistry) {
		this(repository, 15L, 1, meterRegistry);
	}

	/**
	 * Looks up the price for a provider dialect and model id.
	 *
	 * @param type  the provider dialect that served the request
	 * @param model the model id reported by that provider
	 * @return the best matching price, or an empty optional when unknown
	 */
	public Optional<ModelPricingEntry> lookup(ProviderType type, String model) {
		if (model == null || model.isBlank()) {
			return Optional.empty();
		}
		Map<String, List<ModelPricingEntry>> byModelId = snapshotCache.get("catalog", key -> load());
		String provider = litellmProvider(type);
		return match(byModelId, provider, model);
	}

	/**
	 * Drops the cached snapshot so the next lookup sees fresh rows. Called after a successful pricing sync.
	 */
	public void invalidate() {
		snapshotCache.invalidateAll();
	}

	/**
	 * Searches the cached catalog snapshot for model suggestions (admin model catalog).
	 *
	 * <p>Reuses the same in-memory snapshot as {@link #lookup(ProviderType, String)}, so a
	 * search never touches the database unless the snapshot has expired. Results are
	 * ordered by model id and then by provider for stable pagination.</p>
	 *
	 * @param provider optional catalog provider filter (for example {@code openai},
	 *                 {@code together_ai}); blank means every provider
	 * @param query    optional case-insensitive substring of the model id; blank means
	 *                 every model
	 * @param limit    maximum entries to return; clamped defensively to 1 through 1000
	 * @return matching entries, never {@code null}
	 */
	public List<ModelPricingEntry> search(String provider, String query, int limit) {
		Map<String, List<ModelPricingEntry>> byModelId = snapshotCache.get("catalog", key -> load());
		String providerFilter = provider == null ? "" : provider.trim();
		String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		int bounded = Math.max(1, Math.min(limit, 1000));
		return byModelId.values().stream()
		                 .flatMap(List::stream)
		                 .filter(entry -> providerFilter.isEmpty()
				                 || entry.provider().equalsIgnoreCase(providerFilter))
		                 .filter(entry -> needle.isEmpty()
				                 || entry.modelId().toLowerCase(Locale.ROOT).contains(needle))
		                 .sorted(Comparator.comparing(ModelPricingEntry::modelId)
				                 .thenComparing(ModelPricingEntry::provider))
		                 .limit(bounded)
		                 .toList();
	}

	private Map<String, List<ModelPricingEntry>> load() {
		Map<String, List<ModelPricingEntry>> byModelId = new LinkedHashMap<>();
		for (ModelPricingEntity entity : repository.findAll()) {
			byModelId.computeIfAbsent(entity.getModelId(), key -> new ArrayList<>())
			         .add(ModelPricingEntry.from(entity));
		}
		log.debug("Loaded {} pricing rows into the catalog", byModelId.size());
		return byModelId;
	}

	private Optional<ModelPricingEntry> match(
			Map<String, List<ModelPricingEntry>> byModelId,
			String provider,
			String model
	) {
		List<ModelPricingEntry> exact = byModelId.get(model);
		if (exact != null && !exact.isEmpty()) {
			Optional<ModelPricingEntry> sameProvider = exact.stream()
			                                                .filter(entry -> provider.equals(entry.provider()))
			                                                .findFirst();
			if (sameProvider.isPresent()) {
				return sameProvider;
			}
			countFallback("provider-mismatch", provider);
			return Optional.of(exact.getFirst());
		}

		List<ModelPricingEntry> composite = byModelId.get(provider + "/" + model);
		if (composite != null && !composite.isEmpty()) {
			countFallback("composite", provider);
			return Optional.of(composite.getFirst());
		}

		String longestPrefix = byModelId.keySet().stream()
		                                .filter(model::startsWith)
		                                .max(Comparator.comparingInt(String::length))
		                                .orElse(null);
		if (longestPrefix != null) {
			countFallback("prefix", provider);
			return Optional.of(byModelId.get(longestPrefix).getFirst());
		}

		return Optional.empty();
	}

	/**
	 * Records one fallback-priced billing (FIN-B28): prefix, composite, and
	 * cross-provider matches bill at another row's rate, so each must be visible
	 * in telemetry. Tags stay low-cardinality (strategy and mapped provider only —
	 * never the raw model id).
	 *
	 * @param strategy fallback strategy taken
	 * @param provider mapped provider name
	 */
	private void countFallback(String strategy, String provider) {
		Counter.builder("cacherelay.pricing.fallback.total")
		       .description("Billing priced by a fallback catalog row instead of an exact match")
		       .tag("strategy", strategy)
		       .tag("provider", provider)
		       .register(meterRegistry)
		       .increment();
	}

	private static String litellmProvider(ProviderType type) {
		return switch (type) {
			case OPENAI -> "openai";
			case ANTHROPIC -> "anthropic";
			case GEMINI -> "gemini";
			case VERTEX_AI -> "vertex_ai";
			case DEEPSEEK -> "deepseek";
			case OLLAMA -> "ollama";
		};
	}
}