package io.github.kxng0109.cacherelay;

import io.github.kxng0109.cacherelay.ledger.LedgerExecutorProperties;
import io.github.kxng0109.cacherelay.proxy.embeddings.EmbeddingProperties;
import io.github.kxng0109.cacherelay.proxy.sse.SseCapacityProperties;
import io.github.kxng0109.cacherelay.security.ratelimit.RateLimitProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the {@code high-throughput} profile loads, merges over the base document, and overrides capacity ceilings
 * while leaving everything else at defaults.
 */
@SpringBootTest
@ActiveProfiles("high-throughput")
@DisplayName("high-throughput profile activation")
class HighThroughputProfileTest extends SharedContainersBase {

	@DynamicPropertySource
	static void sharedContainers(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", SharedContainersBase::postgresJdbcUrl);
		registry.add("spring.datasource.username", SharedContainersBase::postgresUsername);
		registry.add("spring.datasource.password", SharedContainersBase::postgresPassword);
		registry.add("spring.data.redis.host", SharedContainersBase::redisHost);
		registry.add("spring.data.redis.port", SharedContainersBase::redisPort);
	}

	@Autowired
	private LedgerExecutorProperties ledgerExecutor;

	@Autowired
	private SseCapacityProperties sseCapacity;

	@Autowired
	private RateLimitProperties rateLimit;

	@Autowired
	private EmbeddingProperties embeddings;

	@Value("${gateway.ledger.queue.capacity:65536}")
	private int queueCapacity;

	@Test
	@DisplayName("profile overrides take effect")
	void profileOverridesTakeEffect() {
		assertThat(ledgerExecutor.corePoolSize()).isEqualTo(8);
		assertThat(ledgerExecutor.maxPoolSize()).isEqualTo(16);
		assertThat(ledgerExecutor.queueCapacity()).isEqualTo(10_000);
		assertThat(sseCapacity.maxConnections()).isEqualTo(60_000);
		assertThat(rateLimit.keyCacheMaximumSize()).isEqualTo(100_000);
		assertThat(embeddings.maxConcurrentSubRequests()).isEqualTo(8);
		assertThat(queueCapacity).isEqualTo(262_144);
	}

	@Test
	@DisplayName("unlisted keys keep base defaults (merge, not replace)")
	void unlistedKeysKeepBaseDefaults() {
		assertThat(ledgerExecutor.awaitTerminationSeconds()).isEqualTo(10);
		assertThat(sseCapacity.tickPeriodMs()).isEqualTo(10L);
		assertThat(rateLimit.windowMillis()).isEqualTo(60_000L);
		assertThat(embeddings.maxBatchItems()).isEqualTo(2_048);
	}
}
