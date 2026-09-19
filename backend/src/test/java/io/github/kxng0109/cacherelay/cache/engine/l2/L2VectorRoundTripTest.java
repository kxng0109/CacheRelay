package io.github.kxng0109.cacherelay.cache.engine.l2;

import io.github.kxng0109.cacherelay.cache.config.CacheRelayCacheProperties;
import io.github.kxng0109.cacherelay.cache.contracts.CacheEntry;
import io.github.kxng0109.cacherelay.cache.contracts.CacheScope;
import io.github.kxng0109.cacherelay.cache.contracts.CompoundCacheKey;
import io.github.kxng0109.cacherelay.cache.engine.CacheGuardrails;
import io.github.kxng0109.cacherelay.proxy.embeddings.EmbeddingService;
import io.github.kxng0109.cacherelay.proxy.embeddings.dto.EmbeddingData;
import io.github.kxng0109.cacherelay.proxy.embeddings.dto.EmbeddingResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("L2 store-to-search round trip against real RediSearch (PERF-14)")
class L2VectorRoundTripTest {

	@Container
	static final GenericContainer<?> REDIS_STACK =
			new GenericContainer<>(DockerImageName.parse("redis/redis-stack-server:latest"))
					.withExposedPorts(6379);

	private static LettuceConnectionFactory connectionFactory;
	private static RediSearchVectorClient vectorClient;
	private static RedisSemanticVectorCache l2;

	@BeforeAll
	static void setUpIndex() {
		connectionFactory = new LettuceConnectionFactory(
				REDIS_STACK.getHost(), REDIS_STACK.getMappedPort(6379));
		connectionFactory.afterPropertiesSet();
		vectorClient = new RediSearchVectorClient(connectionFactory, new SimpleMeterRegistry());

		CacheRelayCacheProperties properties = new CacheRelayCacheProperties();
		properties.getSemantic().setEnabled(true);
		properties.getSemantic().setSimilarityThreshold(0.80);

		EmbeddingService embeddingService = mock(EmbeddingService.class);
		float[] vector = new float[]{0.25f, -0.5f, 0.75f, 0.125f};
		EmbeddingResponse probe = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, vector)), "local-embed", null);
		when(embeddingService.processEmbedding(any(), any())).thenReturn(probe);

		l2 = new RedisSemanticVectorCache(
				vectorClient, embeddingService, new CacheGuardrails(), properties);
		l2.initializeIndex();

		assertThat(vectorClient.indexSchemaFields(RedisSemanticVectorCache.INDEX_NAME))
				.contains("temperature");
	}

	@AfterAll
	static void closeFactory() {
		connectionFactory.destroy();
	}

	private static CompoundCacheKey key(String owner) {
		return new CompoundCacheKey(
				owner, CacheScope.TENANT, "gpt-4o", "exactHash", "", "", "How to reset password");
	}

	@Test
	@DisplayName("stored entries are searchable under the same temperature only")
	void temperatureRegimeIsolation() {
		l2.storeSemanticEntry(
				key("tenant1"), "{\"ok\":true}", 5, 5, 10, Duration.ofMinutes(5), 0.0);

		CacheEntry sameRegime = l2.findSemanticMatch(key("tenant1"), 0.0);
		assertThat(sameRegime).isNotNull();
		assertThat(sameRegime.similarityScore()).isGreaterThan(0.99f);

		assertThat(l2.findSemanticMatch(key("tenant1"), 0.9)).isNull();
		assertThat(l2.findSemanticMatch(key("tenant2"), 0.0)).isNull();
		assertThat(l2.findSemanticMatch(key("tenant1"), null)).isNull();
	}
}
