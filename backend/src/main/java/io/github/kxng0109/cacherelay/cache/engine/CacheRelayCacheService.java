package io.github.kxng0109.cacherelay.cache.engine;

import io.github.kxng0109.cacherelay.cache.config.CacheRelayCacheProperties;
import io.github.kxng0109.cacherelay.cache.contracts.*;
import io.github.kxng0109.cacherelay.cache.engine.l0.InMemoryExactCache;
import io.github.kxng0109.cacherelay.cache.engine.l1.RedisExactCache;
import io.github.kxng0109.cacherelay.cache.engine.l2.RedisSemanticVectorCache;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.proxy.protocol.OpenAiChatRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * High-performance facade coordinating multi-tier (L0 in-memory -> L1 Redis exact -> L2 Redis vector) cache lookups,
 * single-flight stampede protection, asynchronous ingestion, and metrics collection.
 */
@Slf4j
@Service
public class CacheRelayCacheService {

	private final InMemoryExactCache l0Cache;
	private final RedisExactCache l1Cache;
	private final RedisSemanticVectorCache l2Cache;
	private final CacheKeyGenerator keyGenerator;
	private final CachePolicyEngine policyEngine;
	private final SingleFlightManager singleFlightManager;
	private final CacheRelayCacheProperties properties;
	private final MeterRegistry meterRegistry;

	public CacheRelayCacheService(
			InMemoryExactCache l0Cache,
			RedisExactCache l1Cache,
			RedisSemanticVectorCache l2Cache,
			CacheKeyGenerator keyGenerator,
			CachePolicyEngine policyEngine,
			SingleFlightManager singleFlightManager,
			CacheRelayCacheProperties properties,
			@Nullable MeterRegistry meterRegistry
	) {
		this.l0Cache = l0Cache;
		this.l1Cache = l1Cache;
		this.l2Cache = l2Cache;
		this.keyGenerator = keyGenerator;
		this.policyEngine = policyEngine;
		this.singleFlightManager = singleFlightManager;
		this.properties = properties;
		this.meterRegistry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
	}

	@PostConstruct
	void init() {
		if (properties.isEnabled() && properties.getSemantic().isEnabled()) {
			l2Cache.initializeIndex();
		}
	}

	/**
	 * Evaluates whether a valid cached completion exists across L0, L1, and L2 tiers.
	 *
	 * <p>Isolation scope comes exclusively from the authenticated key's server-side
	 * policy (SEC-01): {@code X-CacheRelay-Cache-Scope} only selects within the
	 * allowlist, and {@code X-User-Id} is ignored (no verified end-user claim exists).</p>
	 *
	 * @param request     parsed OpenAI chat completion request
	 * @param httpRequest servlet HTTP request with headers and auth attributes
	 * @param ownerId     tenant identifier
	 * @param apiKey      authenticated virtual key, or {@code null} to fail closed to TENANT
	 * @return CacheLookupResult indicating hit tier or miss
	 */
	public CacheLookupResult evaluateCache(
			OpenAiChatRequest request,
			HttpServletRequest httpRequest,
			@Nullable String ownerId,
			@Nullable VirtualApiKey apiKey
	) {
		if (!policyEngine.shouldEvaluateCache(request, httpRequest)) {
			recordMetric(CacheStatus.BYPASS, request.model());
			return CacheLookupResult.bypass();
		}

		String effectiveOwner = (ownerId == null || ownerId.isBlank()) ? "unknown" : ownerId;
		CacheScope scope = policyEngine.resolveScope(httpRequest, apiKey);
		CompoundCacheKey key = keyGenerator.generateKey(
				request,
				effectiveOwner,
				scope,
				null,
				properties.getSemantic().getMaxTurnCountback()
		);

		// Per-request embedding memo (PERF-01): the L2 lookup below embeds the prompt;
		// storeResponse reuses the vector from this memo instead of embedding twice.
		Map<String, float[]> embeddingMemo = new HashMap<>();
		httpRequest.setAttribute(EMBEDDING_MEMO_ATTRIBUTE, embeddingMemo);

		httpRequest.setAttribute(CACHE_KEY_ATTRIBUTE, key);

		long start = System.currentTimeMillis();

		// PERF-08: L0 is a local Caffeine get — serve it without joining a flight
		// (no map churn, no future allocation on the hottest path).
		CacheEntry l0Hit = l0Cache.get(key.exactHash());
		if (l0Hit != null) {
			long l0Duration = System.currentTimeMillis() - start;
			recordMetric(CacheStatus.HIT_L0, key.model());
			recordSavings(l0Hit);
			return CacheLookupResult.hit(CacheStatus.HIT_L0, l0Hit, 1.0f, l0Duration);
		}

		try {
			return singleFlightManager.execute(
					key.exactHash(),
					() -> doLookup(key, request.temperature(), start, embeddingMemo),
					properties.getSingleFlightTimeout());
		} catch (Exception ex) {
			log.warn("Cache evaluation failed non-fatally: {}", ex.getMessage());
			long duration = System.currentTimeMillis() - start;
			recordMetric(CacheStatus.MISS, key.model());
			return CacheLookupResult.miss(duration);
		}
	}

	/**
	 * Request attribute carrying the per-request embedding memo between lookup and store.
	 */
	static final String EMBEDDING_MEMO_ATTRIBUTE = "cacherelay.embeddingMemo";

	/**
	 * Request attribute carrying the compound cache key computed during evaluation so the
	 * store path reuses it instead of rebuilding hashes (PERF-07).
	 */
	static final String CACHE_KEY_ATTRIBUTE = "cacherelay.cacheKey";

	private CacheLookupResult doLookup(
			CompoundCacheKey key, Double temperature, long start, Map<String, float[]> embeddingMemo) {
		// Tier L1: Distributed Redis exact match (1-2ms). L0 is served before the flight
		// in evaluateCache; a race backfilling L0 concurrently simply hits L1 here.
		CacheEntry l1Hit = l1Cache.get(key);
		if (l1Hit != null) {
			long duration = System.currentTimeMillis() - start;
			l0Cache.put(key.exactHash(), l1Hit);
			recordMetric(CacheStatus.HIT_L1, key.model());
			recordSavings(l1Hit);
			return CacheLookupResult.hit(CacheStatus.HIT_L1, l1Hit, 1.0f, duration);
		}

		// 3. Tier L2: Distributed RediSearch Vector Similarity Search (10-25ms)
		CacheEntry l2Hit = l2Cache.findSemanticMatch(key, temperature, embeddingMemo);
		if (l2Hit != null) {
			long duration = System.currentTimeMillis() - start;
			l0Cache.put(key.exactHash(), l2Hit);
			recordMetric(CacheStatus.HIT_L2, key.model());
			recordSavings(l2Hit);
			return CacheLookupResult.hit(CacheStatus.HIT_L2, l2Hit, l2Hit.similarityScore(), duration);
		}

		long duration = System.currentTimeMillis() - start;
		recordMetric(CacheStatus.MISS, key.model());
		return CacheLookupResult.miss(duration);
	}

	/**
	 * Stores a completed response into L0 in-memory, L1 Redis exact, and L2 Redis semantic vector cache.
	 *
	 * <p>Same server-side scope policy as {@link #evaluateCache}: the store path
	 * must resolve identically to the lookup path, otherwise entries would be
	 * written under a namespace reads can never (or wrongly) match.</p>
	 *
	 * @param request          original chat request
	 * @param httpRequest      servlet HTTP request
	 * @param ownerId          tenant identifier
	 * @param apiKey           authenticated virtual key, or {@code null} to fail closed to TENANT
	 * @param responseJson     completion JSON payload
	 * @param promptTokens     prompt token count
	 * @param completionTokens completion token count
	 */
	public void storeResponse(
			OpenAiChatRequest request,
			HttpServletRequest httpRequest,
			@Nullable String ownerId,
			@Nullable VirtualApiKey apiKey,
			String responseJson,
			int promptTokens,
			int completionTokens
	) {
		if (!policyEngine.shouldStoreInCache(request, httpRequest)) {
			return;
		}

		String effectiveOwner = (ownerId == null || ownerId.isBlank()) ? "unknown" : ownerId;
		// PERF-07: reuse the key computed during evaluation (same request, same inputs —
		// generateKey is deterministic), avoiding a second triple-hash canonical build.
		CompoundCacheKey key = (CompoundCacheKey) httpRequest.getAttribute(CACHE_KEY_ATTRIBUTE);
		if (key == null) {
			CacheScope scope = policyEngine.resolveScope(httpRequest, apiKey);
			key = keyGenerator.generateKey(
					request,
					effectiveOwner,
					scope,
					null,
					properties.getSemantic().getMaxTurnCountback()
			);
		}

		String entryId = UUID.randomUUID().toString();
		Duration ttl = properties.getTtl();
		CacheEntry entry = new CacheEntry(
				entryId,
				key.ownerId(),
				key.scope(),
				key.model(),
				key.promptText(),
				key.systemPromptHash(),
				key.prefixHash(),
				responseJson,
				promptTokens,
				completionTokens,
				promptTokens + completionTokens,
				Instant.now(),
				1.0f,
				request.temperature()
		);

		// Store into L0 in-memory
		l0Cache.put(key.exactHash(), entry);

		// Store into L1 Redis exact match
		l1Cache.put(key, entry, ttl);

		// Store into L2 RediSearch vector index, reusing the lookup's memoized vector
		@SuppressWarnings("unchecked")
		Map<String, float[]> embeddingMemo =
				(Map<String, float[]>) httpRequest.getAttribute(EMBEDDING_MEMO_ATTRIBUTE);
		l2Cache.storeSemanticEntry(
				key,
				responseJson,
				promptTokens,
				completionTokens,
				promptTokens + completionTokens,
				ttl,
				request.temperature(),
				embeddingMemo
		);
	}

	/**
	 * Invalidates all local in-memory L0 cache entries.
	 */
	public void purgeLocalCache() {
		l0Cache.invalidateAll();
	}

	private void recordMetric(CacheStatus status, String model) {
		try {
			Counter.builder("cacherelay.cache.requests")
			       .tag("status", status.name().toLowerCase())
			       .tag("model", model != null ? model : "unknown")
			       .register(meterRegistry)
			       .increment();
		} catch (Exception ignored) {
		}
	}

	private void recordSavings(CacheEntry entry) {
		try {
			Counter.builder("cacherelay.cache.tokens.saved")
			       .tag("model", entry.model())
			       .register(meterRegistry)
			       .increment(entry.totalTokens());
		} catch (Exception ignored) {
		}
	}
}
