package io.github.kxng0109.cacherelay.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;

import io.github.kxng0109.cacherelay.admin.dto.CacheTierStatsResponse;
import io.github.kxng0109.cacherelay.admin.dto.TierStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Live Redis tier telemetry behind a short memo.
 *
 * <p>Reads {@code INFO memory} plus {@code INFO stats} (both documented
 * {@code O(1)}, section-filtered so loaded instances never serve a full
 * dump) from each tier no more than once per ten seconds; every caller inside
 * the window shares one snapshot. A dead tier degrades to
 * {@link TierStats#unreachable()} instead of throwing, so the admin read
 * stays HTTP 200 while flagging the outage.</p>
 */
@Slf4j
@Component
public class RedisTierProbe {

	private static final Duration MEMO_TTL = Duration.ofSeconds(10);

	private final RedisConnectionFactory accountingFactory;

	private final RedisConnectionFactory cacheFactory;

	private final Clock clock;

	private volatile Memoized memoized;

	/**
	 * Creates the probe against both tier factories.
	 *
	 * @param accountingFactory spend-accounting tier factory, never {@code null}
	 * @param cacheFactory      cache tier factory, never {@code null}
	 * @param clock             clock for memo freshness, never {@code null}
	 */
	public RedisTierProbe(
			@Qualifier("redisConnectionFactory") RedisConnectionFactory accountingFactory,
			@Qualifier("cacheRedisConnectionFactory") RedisConnectionFactory cacheFactory,
			Clock clock) {
		this.accountingFactory = accountingFactory;
		this.cacheFactory = cacheFactory;
		this.clock = clock;
	}

	/**
	 * Returns the current snapshot, refreshing at most once per memo window.
	 *
	 * @return tier telemetry with its stamp, never {@code null}
	 */
	public CacheTierStatsResponse tiers() {
		Memoized hit = memoized;
		Instant now = clock.instant();
		if (hit != null && Duration.between(hit.at(), now).compareTo(MEMO_TTL) < 0) {
			return hit.snapshot();
		}
		synchronized (this) {
			hit = memoized;
			if (hit != null && Duration.between(hit.at(), clock.instant()).compareTo(MEMO_TTL) < 0) {
				return hit.snapshot();
			}
			Instant stamped = clock.instant();
			CacheTierStatsResponse snapshot = new CacheTierStatsResponse(stamped,
					probeTier("accounting", accountingFactory),
					probeTier("cache", cacheFactory));
			memoized = new Memoized(snapshot, stamped);
			return snapshot;
		}
	}

	private TierStats probeTier(String name, RedisConnectionFactory factory) {
		try (RedisConnection connection = factory.getConnection()) {
			Properties memory = connection.serverCommands().info("memory");
			Properties stats = connection.serverCommands().info("stats");
			if (memory == null || stats == null) {
				return TierStats.unreachable();
			}
			Long used = parseLong(memory.getProperty("used_memory"));
			Long max = parseLong(memory.getProperty("maxmemory"));
			Double percent = used != null && max != null && max > 0
					? used * 100.0 / max
					: null;
			return new TierStats(true, used, max, percent,
					memory.getProperty("maxmemory_policy"),
					parseLong(stats.getProperty("evicted_keys")),
					parseLong(stats.getProperty("keyspace_hits")),
					parseLong(stats.getProperty("keyspace_misses")));
		} catch (RuntimeException failed) {
			log.debug("Tier '{}' INFO unavailable: {}", name, String.valueOf(failed.getMessage()));
			return TierStats.unreachable();
		}
	}

	private static Long parseLong(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return Long.valueOf(raw.trim());
		} catch (NumberFormatException malformed) {
			return null;
		}
	}

	private record Memoized(CacheTierStatsResponse snapshot, Instant at) {
	}
}
