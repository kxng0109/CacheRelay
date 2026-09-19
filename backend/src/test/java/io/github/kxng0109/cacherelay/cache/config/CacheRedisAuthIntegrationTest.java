package io.github.kxng0109.cacherelay.cache.config;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Cache-tier Redis AUTH against a password-protected instance")
class CacheRedisAuthIntegrationTest {

	private static final String PASSWORD = "test-cache-password-32b-minimum!!";

	@Container
	static final RedisContainer REDIS = new RedisContainer(
			DockerImageName.parse("redis:8.10.1-alpine3.23"))
			.withCommand("redis-server", "--requirepass", PASSWORD);

	private CacheRedisTemplate templateFor(String password) {
		CacheRedisProperties properties =
				new CacheRedisProperties(REDIS.getHost(), REDIS.getMappedPort(6379), password);
		LettuceConnectionFactory factory =
				new CacheRedisConfig().cacheRedisConnectionFactory(properties);
		CacheRedisTemplate template = new CacheRedisTemplate(factory);
		template.afterPropertiesSet();
		return template;
	}

	@Test
	@DisplayName("correct password performs cache reads and writes")
	void authenticatedRoundTrip() {
		CacheRedisTemplate template = templateFor(PASSWORD);
		template.opsForValue().set("cacherelay:test:auth", "ok");
		assertThat(template.opsForValue().get("cacherelay:test:auth")).isEqualTo("ok");
	}

	@Test
	@DisplayName("missing password is rejected by the protected instance")
	void missingPasswordRejected() {
		CacheRedisTemplate template = templateFor("");
		assertThatThrownBy(() -> template.opsForValue().set("cacherelay:test:auth", "ok"))
				.isInstanceOf(RuntimeException.class);
	}

	@Test
	@DisplayName("wrong password is rejected by the protected instance")
	void wrongPasswordRejected() {
		CacheRedisTemplate template = templateFor("wrong-password");
		assertThatThrownBy(() -> template.opsForValue().set("cacherelay:test:auth", "ok"))
				.isInstanceOf(RuntimeException.class);
	}
}
