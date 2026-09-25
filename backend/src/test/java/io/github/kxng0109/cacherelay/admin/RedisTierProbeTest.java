package io.github.kxng0109.cacherelay.admin;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Properties;

import io.github.kxng0109.cacherelay.admin.dto.CacheTierStatsResponse;
import io.github.kxng0109.cacherelay.admin.dto.TierStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisServerCommands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Live tier telemetry: parsing, degradation, and memo behavior.
 */
@DisplayName("RedisTierProbe")
class RedisTierProbeTest {

	private static final Instant NOW = Instant.parse("2026-09-25T07:00:00Z");

	private RedisConnectionFactory factoryOf(Properties memory, Properties stats) {
		RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
		RedisConnection connection = mock(RedisConnection.class);
		RedisServerCommands commands = mock(RedisServerCommands.class);
		when(factory.getConnection()).thenReturn(connection);
		when(connection.serverCommands()).thenReturn(commands);
		when(commands.info("memory")).thenReturn(memory);
		when(commands.info("stats")).thenReturn(stats);
		return factory;
	}

	private static Properties properties(String... pairs) {
		Properties properties = new Properties();
		for (int index = 0; index < pairs.length; index += 2) {
			properties.setProperty(pairs[index], pairs[index + 1]);
		}
		return properties;
	}

	private RedisTierProbe probe(RedisConnectionFactory both) {
		return new RedisTierProbe(both, both, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	@DisplayName("snapshot parses both tiers with policy and hit rate inputs")
	void snapshotParsesBothTiers() {
		RedisConnectionFactory factory = factoryOf(
				properties("used_memory", "123456", "maxmemory", "402653184",
						"maxmemory_policy", "noeviction"),
				properties("evicted_keys", "7", "keyspace_hits", "90", "keyspace_misses", "10"));

		CacheTierStatsResponse snapshot = probe(factory).tiers();

		assertThat(snapshot.generatedAt()).as("stamp").isEqualTo(NOW);
		assertThat(snapshot.accounting().reachable()).as("reachable").isTrue();
		assertThat(snapshot.accounting().usedBytes()).as("used").isEqualTo(123456L);
		assertThat(snapshot.accounting().maxBytes()).as("max").isEqualTo(402653184L);
		assertThat(snapshot.accounting().usedPercent()).as("percent")
				.isCloseTo(0.0307, within(0.001));
		assertThat(snapshot.accounting().maxmemoryPolicy()).as("policy").isEqualTo("noeviction");
		assertThat(snapshot.accounting().evictedKeysTotal()).as("evicted").isEqualTo(7L);
		assertThat(snapshot.accounting().keyspaceHits()).as("hits").isEqualTo(90L);
		assertThat(snapshot.accounting().keyspaceMisses()).as("misses").isEqualTo(10L);
		assertThat(snapshot.cache().reachable()).as("cache reachable").isTrue();
	}

	@Test
	@DisplayName("connection failure degrades to unreachable without throwing")
	void connectionFailureDegrades() {
		RedisConnectionFactory failing = mock(RedisConnectionFactory.class);
		when(failing.getConnection()).thenThrow(new RuntimeException("down"));
		RedisConnectionFactory healthy = factoryOf(
				properties("used_memory", "1", "maxmemory", "2", "maxmemory_policy", "allkeys-lru"),
				properties("evicted_keys", "0", "keyspace_hits", "1", "keyspace_misses", "0"));
		RedisTierProbe probe =
				new RedisTierProbe(failing, healthy, Clock.fixed(NOW, ZoneOffset.UTC));

		CacheTierStatsResponse snapshot = probe.tiers();

		assertThat(snapshot.accounting().reachable()).as("dead tier").isFalse();
		assertThat(snapshot.accounting().usedBytes()).as("absent metric").isNull();
		assertThat(snapshot.cache().reachable()).as("live tier").isTrue();
	}

	@Test
	@DisplayName("null INFO sections degrade to unreachable")
	void nullSectionsDegrade() {
		RedisConnectionFactory factory = factoryOf(null, null);

		CacheTierStatsResponse snapshot = probe(factory).tiers();

		assertThat(snapshot.accounting().reachable()).as("reachable").isFalse();
	}

	@Test
	@DisplayName("malformed and absent fields read as absent metrics")
	void malformedFieldsAbsent() {
		RedisConnectionFactory factory = factoryOf(
				properties("used_memory", "not-a-number", "maxmemory_policy", "noeviction"),
				properties("evicted_keys", "", "keyspace_hits", "5"));

		TierStats tier = probe(factory).tiers().accounting();

		assertThat(tier.reachable()).as("reachable").isTrue();
		assertThat(tier.usedBytes()).as("malformed used").isNull();
		assertThat(tier.maxBytes()).as("absent max").isNull();
		assertThat(tier.usedPercent()).as("percent without inputs").isNull();
		assertThat(tier.maxmemoryPolicy()).as("policy").isEqualTo("noeviction");
		assertThat(tier.evictedKeysTotal()).as("blank evicted").isNull();
		assertThat(tier.keyspaceHits()).as("hits").isEqualTo(5L);
		assertThat(tier.keyspaceMisses()).as("absent misses").isNull();
	}

	@Test
	@DisplayName("unlimited maxmemory leaves percent absent")
	void unlimitedMaxLeavesPercentAbsent() {
		RedisConnectionFactory factory = factoryOf(
				properties("used_memory", "100", "maxmemory", "0", "maxmemory_policy", "noeviction"),
				properties());

		TierStats tier = probe(factory).tiers().accounting();

		assertThat(tier.usedPercent()).as("percent").isNull();
		assertThat(tier.maxBytes()).as("max").isEqualTo(0L);
	}

	@Test
	@DisplayName("second read inside the window reuses the memo")
	void memoReusedInsideWindow() {
		RedisConnectionFactory factory = factoryOf(properties("used_memory", "1"),
				properties());
		RedisTierProbe probe = probe(factory);

		probe.tiers();
		CacheTierStatsResponse second = probe.tiers();

		assertThat(second.generatedAt()).as("same stamp").isEqualTo(NOW);
		verify(factory, times(2)).getConnection();
	}

	@Test
	@DisplayName("read past the window refreshes")
	void memoRefreshesPastWindow() {
		RedisConnectionFactory factory = factoryOf(properties("used_memory", "1"),
				properties());
		RedisTierProbe probe = new RedisTierProbe(factory, factory,
				Clock.fixed(NOW, ZoneOffset.UTC));

		probe.tiers();
		RedisTierProbe later = new RedisTierProbe(factory, factory,
				Clock.fixed(NOW.plusSeconds(11), ZoneOffset.UTC));
		CacheTierStatsResponse refreshed = later.tiers();

		assertThat(refreshed.generatedAt()).as("new stamp").isEqualTo(NOW.plusSeconds(11));
		verify(factory, times(4)).getConnection();
	}
}
