package io.github.kxng0109.cacherelay.cache.engine.l0;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.kxng0109.cacherelay.cache.config.CacheRelayCacheProperties;
import io.github.kxng0109.cacherelay.cache.contracts.CacheEntry;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * L0 Process-Local In-Memory Cache backed by Caffeine for ultra-low-latency (&lt;0.1ms) exact match hits.
 */
@Component
public class InMemoryExactCache {

	private final Cache<String, CacheEntry> cache;

	public InMemoryExactCache(CacheRelayCacheProperties properties) {
		this.cache = Caffeine.newBuilder()
		                     .maximumWeight(properties.getExact().getL0MaxBytes())
		                     .weigher(InMemoryExactCache::weighEntry)
		                     .expireAfterWrite(properties.getExact().getL0InMemoryTtl())
		                     .build();
	}

	/**
	 * Estimates one entry's heap weight in bytes (PERF-11): UTF-16 chars count two
	 * bytes each plus a fixed per-entry overhead for the key, entry, and map node.
	 * Deliberately an over-estimate — the cap is a safety bound, not an accounting ledger.
	 *
	 * @param exactKey cache key
	 * @param entry    cached entry
	 * @return estimated weight in bytes
	 */
	static int weighEntry(String exactKey, CacheEntry entry) {
		long weight = 512L;
		weight += 2L * chars(exactKey);
		if (entry != null) {
			weight += 2L * chars(entry.promptText());
			weight += 2L * chars(entry.responsePayloadJson());
			weight += 2L * chars(entry.systemPromptHash());
			weight += 2L * chars(entry.prefixHash());
		}
		return (int) Math.min(weight, Integer.MAX_VALUE);
	}

	private static int chars(String value) {
		return value == null ? 0 : value.length();
	}

	/**
	 * Retrieves an entry from local memory.
	 *
	 * @param exactKey exact compound hash key
	 * @return cached entry or null
	 */
	public @Nullable CacheEntry get(String exactKey) {
		return cache.getIfPresent(exactKey);
	}

	/**
	 * Stores an entry in local memory.
	 *
	 * @param exactKey exact compound hash key
	 * @param entry    cached entry
	 */
	public void put(String exactKey, CacheEntry entry) {
		cache.put(exactKey, entry);
	}

	/**
	 * Invalidates a specific key in local memory.
	 *
	 * @param exactKey exact compound hash key
	 */
	public void invalidate(String exactKey) {
		cache.invalidate(exactKey);
	}

	/**
	 * Evicts all entries from local memory.
	 */
	public void invalidateAll() {
		cache.invalidateAll();
	}
}
