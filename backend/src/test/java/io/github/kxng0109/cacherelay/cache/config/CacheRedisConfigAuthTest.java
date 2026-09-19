package io.github.kxng0109.cacherelay.cache.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CacheRedisConfig cache-tier AUTH")
class CacheRedisConfigAuthTest {

	@Test
	@DisplayName("blank password stays unauthenticated")
	void blankPasswordUnauthenticated() {
		RedisStandaloneConfiguration configuration =
				CacheRedisConfig.standaloneConfiguration(new CacheRedisProperties("localhost", 6380, ""));
		assertThat(configuration.getPassword().isPresent()).isFalse();
		assertThat(configuration.getHostName()).isEqualTo("localhost");
		assertThat(configuration.getPort()).isEqualTo(6380);
	}

	@Test
	@DisplayName("null password stays unauthenticated")
	@SuppressWarnings("DataFlowIssue")
	void nullPasswordUnauthenticated() {
		RedisStandaloneConfiguration configuration = CacheRedisConfig.standaloneConfiguration(
				new CacheRedisProperties("localhost", 6380, null));
		assertThat(configuration.getPassword().isPresent()).isFalse();
	}

	@Test
	@DisplayName("configured password is applied")
	void configuredPasswordApplied() {
		RedisStandaloneConfiguration configuration = CacheRedisConfig.standaloneConfiguration(
				new CacheRedisProperties("cache-host", 6380, "test-cache-password"));
		assertThat(configuration.getPassword().isPresent()).isTrue();
		assertThat(new String(configuration.getPassword().get())).isEqualTo("test-cache-password");
	}
}
