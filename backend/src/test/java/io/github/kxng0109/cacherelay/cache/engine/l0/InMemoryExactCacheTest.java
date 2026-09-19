package io.github.kxng0109.cacherelay.cache.engine.l0;

import io.github.kxng0109.cacherelay.cache.config.CacheRelayCacheProperties;
import io.github.kxng0109.cacherelay.cache.contracts.CacheEntry;
import io.github.kxng0109.cacherelay.cache.contracts.CacheScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InMemoryExactCache")
class InMemoryExactCacheTest {

	@Test
	@DisplayName("entry weight counts payload bytes plus overhead (PERF-11)")
	void weighEntryCountsPayload() {
		CacheEntry entry = new CacheEntry(
				"id1",
				"tenant1",
				CacheScope.TENANT,
				"gpt-4o",
				"prompt",
				"sys",
				"prefix",
				"{}",
				10,
				20,
				30,
				Instant.now(),
				1.0f,
				null
		);

		int weight = InMemoryExactCache.weighEntry("key1", entry);

		assertThat(weight).isEqualTo(512 + 2 * ("key1".length() + "prompt".length()
				+ "{}".length() + "sys".length() + "prefix".length()));
		assertThat(InMemoryExactCache.weighEntry(null, null)).isEqualTo(512);
	}

	@Test
	@DisplayName("get, put, invalidate, and invalidateAll operate properly on Caffeine L0 cache")
	void cacheOperations() {
		CacheRelayCacheProperties props = new CacheRelayCacheProperties();
		InMemoryExactCache cache = new InMemoryExactCache(props);

		CacheEntry entry = new CacheEntry(
				"id1",
				"tenant1",
				CacheScope.TENANT,
				"gpt-4o",
				"prompt",
				"sys",
				"prefix",
				"{}",
				10,
				20,
				30,
				Instant.now(),
				1.0f,
				null
		);

		cache.put("key1", entry);
		assertThat(cache.get("key1")).isEqualTo(entry);
		assertThat(cache.get("missing")).isNull();

		cache.invalidate("key1");
		assertThat(cache.get("key1")).isNull();

		cache.put("key2", entry);
		cache.put("key3", entry);
		cache.invalidateAll();
		assertThat(cache.get("key2")).isNull();
		assertThat(cache.get("key3")).isNull();
	}
}
