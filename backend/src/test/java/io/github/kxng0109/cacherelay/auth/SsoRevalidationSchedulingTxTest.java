package io.github.kxng0109.cacherelay.auth;

import java.time.Instant;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Proves the SSO revalidation sweep runs outside any ambient transaction,
 * exactly like the production {@code @Scheduled} entry points.
 */
@Testcontainers
@SpringBootTest
@DisplayName("SsoRevalidationService scheduling transaction")
class SsoRevalidationSchedulingTxTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES =
			new PostgreSQLContainer(DockerImageName.parse("postgres:16.15-alpine"));

	@Container
	@ServiceConnection
	static final RedisContainer REDIS =
			new RedisContainer(DockerImageName.parse("redis:8.10.1-alpine3.23"));

	@Autowired
	private SsoRevalidationService service;

	@Test
	@DisplayName("revalidateBatch runs without an ambient transaction")
	void revalidateBatchWithoutAmbientTransaction() {
		assertThatNoException()
				.as("scheduled sweep path must open its own transaction")
				.isThrownBy(() -> service.revalidateBatch(Instant.now(), 10));
	}
}
