package io.github.kxng0109.cacherelay.cache.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;

import io.github.kxng0109.cacherelay.SharedContainersBase;
import io.github.kxng0109.cacherelay.cache.config.CacheRelayCacheProperties;
import io.github.kxng0109.cacherelay.cache.contracts.CacheEntry;
import io.github.kxng0109.cacherelay.cache.contracts.CacheScope;
import io.github.kxng0109.cacherelay.cache.engine.l0.InMemoryExactCache;
import io.github.kxng0109.cacherelay.cache.engine.l1.RedisExactCache;
import io.github.kxng0109.cacherelay.cache.engine.l2.RedisSemanticVectorCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADM-B07 fleet convergence against real PostgreSQL: pod A purges and
 * notifies; pod B's listener flushes its L0 without a restart.
 */
@DisplayName("ADM-B07 purge convergence across two pods")
class PurgeConvergenceIT extends SharedContainersBase {

	private InMemoryExactCache podBL0;

	private CachePurgeInvalidationListener listener;

	@BeforeEach
	void setUp() {
		PGSimpleDataSource dataSource = SharedContainersBase.newDataSource();
		CacheRelayCacheProperties properties = new CacheRelayCacheProperties();
		podBL0 = new InMemoryExactCache(properties);
		CacheRelayCacheService podB = new CacheRelayCacheService(
				podBL0,
				mock(RedisExactCache.class),
				mock(RedisSemanticVectorCache.class),
				mock(CacheKeyGenerator.class),
				mock(CachePolicyEngine.class),
				mock(SingleFlightManager.class),
				properties,
				new SimpleMeterRegistry()
		);
		listener = new CachePurgeInvalidationListener(dataSource, podB);
		listener.start();
	}

	@AfterEach
	void tearDown() {
		if (listener != null) {
			listener.stop();
		}
	}

	@Test
	@DisplayName("pod B drops its L0 entry on pod A's purge")
	void podBDropsL0OnPodAPurge() {
		podBL0.put("exact-key-1", new CacheEntry(
				"entry-1", "tenant-corp", CacheScope.TENANT, "gpt-4o",
				"hello", "sys", "pre", "{\"ok\":true}",
				5, 7, 12, Instant.now(), 1.0f, null));
		assertThat(podBL0.get("exact-key-1")).isNotNull();

		// The listener has no readiness latch: a notify sent before its
		// LISTEN is active is lost (PG notifies active listeners only), so
		// retry the notify across poll windows instead of trusting one shot.
		boolean evicted = false;
		for (int attempt = 0; attempt < 3 && !evicted; attempt++) {
			new JdbcTemplate(SharedContainersBase.newDataSource())
					.execute("SELECT pg_notify('cache_purge_all', 'ALL')");
			evicted = pollForEviction("exact-key-1", Duration.ofSeconds(10));
		}

		assertThat(evicted)
				.as("pod B flushes L0 on pod A's purge")
				.isTrue();
	}

	private boolean pollForEviction(String key, Duration bound) {
		long deadline = System.nanoTime() + bound.toNanos();
		while (System.nanoTime() < deadline) {
			if (podBL0.get(key) == null) {
				return true;
			}
			try {
				Thread.sleep(100L);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return false;
	}
}
