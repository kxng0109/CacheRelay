package io.github.kxng0109.cacherelay.cache.engine.l2;

import io.github.kxng0109.cacherelay.cache.config.CacheRelayCacheProperties;
import io.github.kxng0109.cacherelay.cache.contracts.CacheEntry;
import io.github.kxng0109.cacherelay.cache.contracts.CacheScope;
import io.github.kxng0109.cacherelay.cache.contracts.CompoundCacheKey;
import io.github.kxng0109.cacherelay.cache.engine.CacheGuardrails;
import io.github.kxng0109.cacherelay.proxy.embeddings.EmbeddingService;
import io.github.kxng0109.cacherelay.proxy.embeddings.OnnxLocalEmbedder;
import io.github.kxng0109.cacherelay.proxy.embeddings.VectorEncodingUtils;
import io.github.kxng0109.cacherelay.proxy.embeddings.dto.EmbeddingData;
import io.github.kxng0109.cacherelay.proxy.embeddings.dto.EmbeddingResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("RedisSemanticVectorCache")
class RedisSemanticVectorCacheTest {

	private final RediSearchVectorClient vectorClient = mock(RediSearchVectorClient.class);
	private final EmbeddingService embeddingService = mock(EmbeddingService.class);
	private final CacheGuardrails guardrails = new CacheGuardrails();
	private final CacheRelayCacheProperties properties = new CacheRelayCacheProperties();
	private RedisSemanticVectorCache cache;

	@BeforeEach
	void setUp() {
		properties.getSemantic().setEnabled(true);
		properties.getSemantic().setSimilarityThreshold(0.90);
		cache = new RedisSemanticVectorCache(vectorClient, embeddingService, guardrails, properties);
	}

	@Test
	@DisplayName("lookup miss plus store for one prompt computes the embedding once (PERF-01)")
	void embeddingComputedOncePerRequest() {
		CompoundCacheKey key = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "", "", "Hello"
		);

		EmbeddingResponse mockEmbedding = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, new float[]{0.1f, 0.2f})), "text-embedding-3-small", null
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(mockEmbedding);
		when(vectorClient.searchKnn(anyString(), anyString(), any(), eq(2))).thenReturn(List.of());

		Map<String, float[]> memo = new HashMap<>();
		assertThat(cache.findSemanticMatch(key, 0.0, memo)).isNull();
		cache.storeSemanticEntry(key, "{}", 1, 1, 2, Duration.ofMinutes(5), 0.0, memo);

		verify(embeddingService, times(1)).processEmbedding(any(), eq("tenant1"));
		verify(vectorClient).saveVectorDocument(anyString(), anyMap(), any());
	}

	@Test
	@DisplayName("findSemanticMatch returns valid CacheEntry when similarity exceeds threshold and guardrails pass")
	void findSemanticMatchSuccess() {
		CompoundCacheKey key = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "", "", "How to reset password"
		);

		EmbeddingResponse mockEmbedding = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, new float[]{0.1f, 0.2f})), "text-embedding-3-small", null
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(mockEmbedding);

		VectorSearchResult match = new VectorSearchResult(
				"cacherelay:cache:doc:tenant1:doc1",
				0.05, // Distance = 0.05 -> Sim = 0.95 >= 0.90
				Map.of(
						"prompt_text", "I forgot my password",
						"response_json", "{\"content\":\"Click reset password\"}",
						"prompt_tokens", "10",
						"completion_tokens", "20",
						"total_tokens", "30"
				)
		);
		when(vectorClient.searchKnn(anyString(), anyString(), any(), eq(2))).thenReturn(List.of(match));

		CacheEntry entry = cache.findSemanticMatch(key, 0.0);
		assertThat(entry).isNotNull();
		assertThat(entry.promptText()).isEqualTo("I forgot my password");
		assertThat(entry.similarityScore()).isBetween(0.949f, 0.951f);
		assertThat(entry.promptTokens()).isEqualTo(10);
	}

	@Test
	@DisplayName("findSemanticMatch returns null when similarity is below threshold")
	void findSemanticMatchBelowThreshold() {
		CompoundCacheKey key = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "", "", "What is Python?"
		);

		EmbeddingResponse mockEmbedding = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, new float[]{0.1f, 0.2f})), "text-embedding-3-small", null
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(mockEmbedding);

		VectorSearchResult lowMatch = new VectorSearchResult(
				"cacherelay:cache:doc:tenant1:doc2",
				0.25, // Distance = 0.25 -> Sim = 0.75 < 0.90
				Map.of("prompt_text", "What is Java?", "response_json", "{}")
		);
		when(vectorClient.searchKnn(anyString(), anyString(), any(), eq(2))).thenReturn(List.of(lowMatch));

		CacheEntry entry = cache.findSemanticMatch(key, 0.0);
		assertThat(entry).isNull();
	}

	@Test
	@DisplayName("storeSemanticEntry generates embedding and writes document with fields to Redis")
	void storeSemanticEntry() {
		CompoundCacheKey key = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "prefixHash", "sysHash", "How to deploy"
		);

		EmbeddingResponse mockEmbedding = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, new float[]{0.1f, 0.2f})), "text-embedding-3-small", null
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(mockEmbedding);

		cache.storeSemanticEntry(key, "{\"choices\":[]}", 15, 30, 45, Duration.ofHours(1), 0.0);

		verify(vectorClient).saveVectorDocument(
				startsWith("cacherelay:cache:doc:tenant1:"),
				anyMap(),
				eq(Duration.ofHours(1))
		);
	}

	@Test
	@DisplayName("findSemanticMatch supports Base64 and List embedding vectors")
	void embeddingVectorFormatSupport() {
		CompoundCacheKey key = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "", "", "Hello"
		);

		// Base64 embedding
		String b64 = VectorEncodingUtils.encodeToBase64(new float[]{0.5f, -0.5f});
		EmbeddingResponse b64Embedding = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, b64)), "text-embedding-3-small", null
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(b64Embedding);

		VectorSearchResult match = new VectorSearchResult(
				"doc1",
				0.02,
				Map.of(
						"prompt_text",
						"Hello",
						"created_at",
						"2026-09-01T12:00:00Z"
				)
		);
		when(vectorClient.searchKnn(anyString(), anyString(), any(), eq(2))).thenReturn(List.of(match));

		CacheEntry entry = cache.findSemanticMatch(key, 0.0);
		assertThat(entry).isNotNull();
		assertThat(entry.similarityScore()).isBetween(0.979f, 0.981f);

		// List of numbers embedding
		EmbeddingResponse listEmbedding = new EmbeddingResponse(
				"list",
				List.of(new EmbeddingData("embedding", 0, List.of(0.1, "not-number"))),
				"text-embedding-3-small",
				null
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(listEmbedding);
		CacheEntry entry2 = cache.findSemanticMatch(key, 0.0);
		assertThat(entry2).isNotNull();

		// Null embedding object in EmbeddingData
		EmbeddingResponse nullEmbedding = new EmbeddingResponse(
				"list", List.of(new EmbeddingData("embedding", 0, null)), "text-embedding-3-small", null
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(nullEmbedding);
		assertThat(cache.findSemanticMatch(key, 0.0)).isNull();
	}

	@Test
	@DisplayName("initializeIndex migrates a pre-temperature schema once (PERF-14)")
	void initializeIndexMigratesSchema() {
		float[] vector768 = new float[768];
		Arrays.fill(vector768, 0.1f);
		EmbeddingResponse probe = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, vector768)), "text-embedding-3-small", null);
		when(embeddingService.processEmbedding(any(), any())).thenReturn(probe);
		when(vectorClient.vectorDimensionOf(RedisSemanticVectorCache.INDEX_NAME)).thenReturn(768);
		when(vectorClient.indexSchemaFields(RedisSemanticVectorCache.INDEX_NAME))
				.thenReturn(Set.of("owner_id", "model", "prefix_hash", "system_prompt_hash", "embedding"));

		cache.initializeIndex();

		verify(vectorClient).dropIndex(RedisSemanticVectorCache.INDEX_NAME, true);
		verify(vectorClient).createIndexIfNotExists(
				eq(RedisSemanticVectorCache.INDEX_NAME), eq(RedisSemanticVectorCache.PREFIX), eq(768));
	}

	@Test
	@DisplayName("initializeIndex keeps a current schema untouched (PERF-14)")
	void initializeIndexKeepsCurrentSchema() {
		float[] vector768 = new float[768];
		Arrays.fill(vector768, 0.1f);
		EmbeddingResponse probe = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, vector768)), "text-embedding-3-small", null);
		when(embeddingService.processEmbedding(any(), any())).thenReturn(probe);
		when(vectorClient.vectorDimensionOf(RedisSemanticVectorCache.INDEX_NAME)).thenReturn(768);
		when(vectorClient.indexSchemaFields(RedisSemanticVectorCache.INDEX_NAME)).thenReturn(
				Set.of("owner_id", "model", "prefix_hash", "system_prompt_hash", "temperature", "embedding"));

		cache.initializeIndex();

		verify(vectorClient, never()).dropIndex(anyString(), anyBoolean());
		verify(vectorClient).createIndexIfNotExists(
				eq(RedisSemanticVectorCache.INDEX_NAME), eq(RedisSemanticVectorCache.PREFIX), eq(768));
	}

	@Test
	@DisplayName("initializeIndex creates at the mapped dimension when the probe is unavailable")
	void initializeIndexCreatesFromMapWithoutProbe() {
		properties.getSemantic().setEmbeddingModel("nomic-embed-text");
		try {
			when(embeddingService.processEmbedding(any(), any())).thenReturn(null);
			when(vectorClient.vectorDimensionOf(RedisSemanticVectorCache.INDEX_NAME)).thenReturn(-1);
			when(vectorClient.indexSchemaFields(RedisSemanticVectorCache.INDEX_NAME)).thenReturn(Set.of());

			cache.initializeIndex();

			verify(vectorClient, never()).dropIndex(anyString(), anyBoolean());
			verify(vectorClient).createIndexIfNotExists(
					eq(RedisSemanticVectorCache.INDEX_NAME), eq(RedisSemanticVectorCache.PREFIX), eq(768));
		} finally {
			properties.getSemantic().setEmbeddingModel("text-embedding-3-small");
		}
	}

	@Test
	@DisplayName("initializeIndex keeps a present index when the probe is unavailable")
	void initializeIndexKeepsPresentIndexWithoutProbe() {
		properties.getSemantic().setEmbeddingModel("totally-unknown-model");
		try {
			when(embeddingService.processEmbedding(any(), any())).thenReturn(null);
			when(vectorClient.vectorDimensionOf(RedisSemanticVectorCache.INDEX_NAME)).thenReturn(768);
			when(vectorClient.indexSchemaFields(RedisSemanticVectorCache.INDEX_NAME)).thenReturn(
					Set.of("owner_id", "model", "prefix_hash", "system_prompt_hash", "temperature", "embedding"));

			cache.initializeIndex();

			verify(vectorClient, never()).dropIndex(anyString(), anyBoolean());
			verify(vectorClient, never()).createIndexIfNotExists(anyString(), anyString(), anyInt());
		} finally {
			properties.getSemantic().setEmbeddingModel("text-embedding-3-small");
		}
	}

	@Test
	@DisplayName("null temperature bypasses lookup and store without embedding (PERF-14)")
	void nullTemperatureBypassesTier() {
		CompoundCacheKey key = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "", "", "Hello"
		);

		assertThat(cache.findSemanticMatch(key, null)).isNull();
		cache.storeSemanticEntry(key, "{}", 1, 1, 2, Duration.ofMinutes(5), null);

		verify(embeddingService, never()).processEmbedding(any(), any());
		verify(vectorClient, never()).searchKnn(anyString(), anyString(), any(), anyInt());
		verify(vectorClient, never()).saveVectorDocument(anyString(), anyMap(), any());
	}

	@Test
	@DisplayName("findSemanticMatch builds filter query with prefixHash and systemPromptHash and parses invalid dates safely")
	void filterQueryWithHashesAndInvalidDates() {
		CompoundCacheKey fullKey = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "pref123", "sys123", "Hello"
		);

		EmbeddingResponse mockEmbedding = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, new float[]{0.1f, 0.2f})), "text-embedding-3-small", null
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(mockEmbedding);

		VectorSearchResult match = new VectorSearchResult(
				"doc1", 0.01,
				Map.of(
						"prompt_text", "Hello",
						"created_at", "invalid-date-format",
						"prompt_tokens", "not-int",
						"completion_tokens", "not-int"
				)
		);
		when(vectorClient.searchKnn(anyString(), contains("prefix_hash"), any(), eq(2))).thenReturn(List.of(match));

		CacheEntry entry = cache.findSemanticMatch(fullKey, 0.0);
		assertThat(entry).isNotNull();
		assertThat(entry.promptTokens()).isZero();
	}

	@Test
	@DisplayName("edge cases: disabled, blank prompt, empty search results, and guardrail failures")
	void edgeCases() {
		CompoundCacheKey key = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "", "", "Hello"
		);

		// Disabled semantic cache
		properties.getSemantic().setEnabled(false);
		assertThat(cache.findSemanticMatch(key)).isNull();

		// Blank prompt
		properties.getSemantic().setEnabled(true);
		CompoundCacheKey blankKey = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "", "", "   "
		);
		assertThat(cache.findSemanticMatch(blankKey)).isNull();

		// Empty results
		when(vectorClient.searchKnn(anyString(), anyString(), any(), eq(2))).thenReturn(List.of());
		assertThat(cache.findSemanticMatch(key)).isNull();

		// Guardrail mismatch (e.g. enable vs disable)
		CompoundCacheKey enableKey = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "", "", "How to enable 2FA"
		);
		EmbeddingResponse mockEmbedding = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, new float[]{0.1f, 0.2f})), "text-embedding-3-small", null
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(mockEmbedding);
		VectorSearchResult mismatch = new VectorSearchResult("doc2", 0.01, Map.of("prompt_text", "How to disable 2FA"));
		when(vectorClient.searchKnn(anyString(), anyString(), any(), eq(2))).thenReturn(List.of(mismatch));
		assertThat(cache.findSemanticMatch(enableKey)).isNull();
	}

	@Test
	@DisplayName("upstream embedding failure fails soft: lookup returns null and store is skipped")
	void upstreamEmbeddingFailureFailsSoft() {
		CompoundCacheKey key = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "", "", "How to reset password"
		);

		// The real 401/502 shape: processEmbedding throws (no key, provider down).
		// Semantic caching must degrade to a MISS, never propagate the failure.
		when(embeddingService.processEmbedding(any(), eq("tenant1")))
				.thenThrow(new RuntimeException("Upstream embedding provider returned HTTP 401"));

		assertThat(cache.findSemanticMatch(key)).isNull();
		verify(vectorClient, never()).searchKnn(anyString(), anyString(), any(), anyInt());

		// storeSemanticEntry must also short-circuit instead of throwing.
		cache.storeSemanticEntry(key, "{\"content\":\"Click reset\"}", 10, 20, 30, Duration.ofHours(1), 0.0);
		verify(vectorClient, never()).saveVectorDocument(anyString(), anyMap(), any());
	}

	@Test
	@DisplayName("initializeIndex uses the live probe dimension when embedding succeeds")
	void initializeIndexUsesProbeDimension() {
		// 768-dim probe vector (nomic-embed-text shape) — index must be created at 768.
		EmbeddingResponse probe = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, new float[768])), "local-embed", null
		);
		when(embeddingService.processEmbedding(any(), eq("system"))).thenReturn(probe);

		cache.initializeIndex();

		verify(vectorClient).createIndexIfNotExists(
				eq(RedisSemanticVectorCache.INDEX_NAME),
				eq(RedisSemanticVectorCache.PREFIX),
				eq(768)
		);
	}

	@Test
	@DisplayName("initializeIndex falls back to the verified map when the probe yields nothing")
	void initializeIndexFallsBackToMap() {
		// Probe returns no vector (provider down at boot) -> map resolves nomic-embed-text = 768.
		properties.getSemantic().setEmbeddingModel("nomic-embed-text");
		EmbeddingResponse empty = new EmbeddingResponse("list", List.of(), "local-embed", null);
		when(embeddingService.processEmbedding(any(), eq("system"))).thenReturn(empty);

		cache.initializeIndex();

		verify(vectorClient).createIndexIfNotExists(
				eq(RedisSemanticVectorCache.INDEX_NAME),
				eq(RedisSemanticVectorCache.PREFIX),
				eq(768)
		);
	}

	@Test
	@DisplayName("initializeIndex falls back to the safe default when probe and map both fail")
	void initializeIndexFallsBackToDefault() {
		// Unknown model -> map UNKNOWN -> 1536 safe default.
		properties.getSemantic().setEmbeddingModel("totally-unknown-model");
		when(embeddingService.processEmbedding(any(), eq("system")))
				.thenThrow(new RuntimeException("Upstream embedding provider returned HTTP 401"));

		cache.initializeIndex();

		verify(vectorClient).createIndexIfNotExists(
				eq(RedisSemanticVectorCache.INDEX_NAME),
				eq(RedisSemanticVectorCache.PREFIX),
				eq(1536)
		);
	}

	@Test
	@DisplayName("initializeIndex drops and recreates a stale index whose dimension no longer matches")
	void initializeIndexRecreatesStaleIndex() {
		// Existing index is at 1536 (OpenAI default); the probe yields 768 (nomic).
		EmbeddingResponse probe = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, new float[768])), "local-embed", null
		);
		when(embeddingService.processEmbedding(any(), eq("system"))).thenReturn(probe);
		when(vectorClient.vectorDimensionOf(RedisSemanticVectorCache.INDEX_NAME)).thenReturn(1536);

		cache.initializeIndex();

		verify(vectorClient).dropIndex(eq(RedisSemanticVectorCache.INDEX_NAME), eq(true));
		verify(vectorClient).createIndexIfNotExists(
				eq(RedisSemanticVectorCache.INDEX_NAME),
				eq(RedisSemanticVectorCache.PREFIX),
				eq(768)
		);
	}

	@Test
	@DisplayName("initializeIndex never drops a live index when the probe is unavailable")
	void initializeIndexKeepsLiveIndexWhenProbeUnavailable() {
		// Probe down + unknown model would resolve the 1536 fallback: a live 768 index must survive,
		// and no create-if-absent call may run against the guessed dimension either.
		properties.getSemantic().setEmbeddingModel("totally-unknown-model");
		when(embeddingService.processEmbedding(any(), eq("system")))
				.thenThrow(new RuntimeException("Upstream embedding provider returned HTTP 401"));
		when(vectorClient.vectorDimensionOf(RedisSemanticVectorCache.INDEX_NAME)).thenReturn(768);

		cache.initializeIndex();

		verify(vectorClient, never()).dropIndex(anyString(), anyBoolean());
		verify(vectorClient, never()).createIndexIfNotExists(anyString(), anyString(), anyInt());
	}
	@Test
	@DisplayName("initializeIndex leaves a matching index alone (no drop, no recreate)")
	void initializeIndexKeepsMatchingIndex() {
		EmbeddingResponse probe = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, new float[768])), "local-embed", null
		);
		when(embeddingService.processEmbedding(any(), eq("system"))).thenReturn(probe);
		when(vectorClient.vectorDimensionOf(RedisSemanticVectorCache.INDEX_NAME)).thenReturn(768);

		cache.initializeIndex();

		verify(vectorClient, never()).dropIndex(anyString(), anyBoolean());
		verify(vectorClient).createIndexIfNotExists(
				eq(RedisSemanticVectorCache.INDEX_NAME),
				eq(RedisSemanticVectorCache.PREFIX),
				eq(768)
		);
	}

	@Test
	@DisplayName("local ONNX embedder serves the query vector without touching the upstream provider")
	void localOnnxEmbedderBypassesUpstream() throws Exception {
		OnnxLocalEmbedder local = mock(OnnxLocalEmbedder.class);
		cache.setOnnxLocalEmbedder(local);
		when(local.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
		when(vectorClient.searchKnn(anyString(), anyString(), any(), eq(2))).thenReturn(List.of());

		CompoundCacheKey key = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "", "", "How to reset password"
		);
		cache.findSemanticMatch(key, 0.0);

		verify(local).embed("How to reset password");
		verify(embeddingService, never()).processEmbedding(any(), anyString());
	}

	@Test
	@DisplayName("a failing local ONNX embedder falls back to the upstream provider")
	void localOnnxFailureFallsBackToUpstream() throws Exception {
		OnnxLocalEmbedder local = mock(OnnxLocalEmbedder.class);
		cache.setOnnxLocalEmbedder(local);
		when(local.embed(anyString())).thenThrow(new IllegalStateException("native backend gone"));
		when(vectorClient.searchKnn(anyString(), anyString(), any(), eq(2))).thenReturn(List.of());
		EmbeddingResponse upstreamEmbedding = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, new float[]{0.1f, 0.2f})), "local-embed", null
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(upstreamEmbedding);

		CompoundCacheKey key = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "", "", "How to reset password"
		);
		cache.findSemanticMatch(key, 0.0);

		verify(local).embed("How to reset password");
		verify(embeddingService).processEmbedding(any(), eq("tenant1"));
	}

	@Test
	@DisplayName("a zero-length local vector falls back to the upstream provider")
	void localOnnxZeroLengthFallsBackToUpstream() throws Exception {
		OnnxLocalEmbedder local = mock(OnnxLocalEmbedder.class);
		cache.setOnnxLocalEmbedder(local);
		when(local.embed(anyString())).thenReturn(new float[0]);
		when(vectorClient.searchKnn(anyString(), anyString(), any(), eq(2))).thenReturn(List.of());
		EmbeddingResponse upstreamEmbedding = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, new float[]{0.1f, 0.2f})), "local-embed", null
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(upstreamEmbedding);

		CompoundCacheKey key = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "", "", "How to reset password"
		);
		cache.findSemanticMatch(key, 0.0);

		verify(embeddingService).processEmbedding(any(), eq("tenant1"));
	}

	@Test
	@DisplayName("initializeIndex falls back to the map when the probe yields an empty vector")
	void initializeIndexFallsBackOnEmptyProbeVector() {
		when(embeddingService.processEmbedding(any(), eq("system"))).thenReturn(new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, new float[0])), "text-embedding-3-small", null));
		when(vectorClient.vectorDimensionOf(RedisSemanticVectorCache.INDEX_NAME)).thenReturn(768);
		when(vectorClient.indexSchemaFields(RedisSemanticVectorCache.INDEX_NAME)).thenReturn(
				Set.of("owner_id", "model", "prefix_hash", "system_prompt_hash", "temperature", "embedding"));

		cache.initializeIndex();

		verify(vectorClient, never()).dropIndex(anyString(), anyBoolean());
		verify(vectorClient, never()).createIndexIfNotExists(anyString(), anyString(), anyInt());
	}

	@Test
	@DisplayName("initializeIndex reports initialized when creating an absent index without a probe")
	void initializeIndexInitializesAbsentIndexWithoutProbe() {
		properties.getSemantic().setEmbeddingModel("nomic-embed-text");
		try {
			when(embeddingService.processEmbedding(any(), any())).thenReturn(null);
			when(vectorClient.vectorDimensionOf(RedisSemanticVectorCache.INDEX_NAME)).thenReturn(-1);
			when(vectorClient.indexSchemaFields(RedisSemanticVectorCache.INDEX_NAME)).thenReturn(Set.of());
			when(vectorClient.createIndexIfNotExists(
					eq(RedisSemanticVectorCache.INDEX_NAME),
					eq(RedisSemanticVectorCache.PREFIX),
					eq(768))).thenReturn(true);

			cache.initializeIndex();

			verify(vectorClient, never()).dropIndex(anyString(), anyBoolean());
			verify(vectorClient).createIndexIfNotExists(
					eq(RedisSemanticVectorCache.INDEX_NAME), eq(RedisSemanticVectorCache.PREFIX), eq(768));
		} finally {
			properties.getSemantic().setEmbeddingModel("text-embedding-3-small");
		}
	}

	@Test
	@DisplayName("initializeIndex falls back when the probe response carries no data")
	@SuppressWarnings("DataFlowIssue")
	void initializeIndexFallsBackOnNullProbeData() {
		properties.getSemantic().setEmbeddingModel("nomic-embed-text");
		try {
			when(embeddingService.processEmbedding(any(), eq("system")))
					.thenReturn(new EmbeddingResponse("list", null, "nomic-embed-text", null));
			when(vectorClient.vectorDimensionOf(RedisSemanticVectorCache.INDEX_NAME)).thenReturn(-1);
			when(vectorClient.indexSchemaFields(RedisSemanticVectorCache.INDEX_NAME)).thenReturn(Set.of());

			cache.initializeIndex();

			verify(vectorClient).createIndexIfNotExists(
					eq(RedisSemanticVectorCache.INDEX_NAME), eq(RedisSemanticVectorCache.PREFIX), eq(768));
		} finally {
			properties.getSemantic().setEmbeddingModel("text-embedding-3-small");
		}
	}

	@Test
	@DisplayName("findSemanticMatch ranks two candidates and returns the best match")
	void findSemanticMatchRanksTwoCandidates() {
		CompoundCacheKey key = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "", "", "How to reset password"
		);

		EmbeddingResponse mockEmbedding = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, new float[]{0.1f, 0.2f})), "text-embedding-3-small", null
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(mockEmbedding);

		VectorSearchResult best = new VectorSearchResult(
				"doc-best", 0.05,
				Map.of("prompt_text", "I forgot my password", "response_json", "{\"content\":\"reset\"}"));
		VectorSearchResult runnerUp = new VectorSearchResult(
				"doc-second", 0.08,
				Map.of("prompt_text", "I forgot my password", "response_json", "{\"content\":\"reset\"}"));
		when(vectorClient.searchKnn(anyString(), anyString(), any(), eq(2)))
				.thenReturn(List.of(best, runnerUp));

		CacheEntry entry = cache.findSemanticMatch(key, 0.0);
		assertThat(entry).as("ranked entry").isNotNull();
		assertThat(entry.id()).as("best doc key").isEqualTo("doc-best");
		assertThat(entry.similarityScore()).as("best similarity").isBetween(0.949f, 0.951f);
	}

	@Test
	@DisplayName("findSemanticMatch rejects polarity-inverted candidates when a temperature is set")
	void findSemanticMatchRejectsPolarityMismatch() {
		CompoundCacheKey enableKey = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "", "", "How to enable 2FA"
		);

		EmbeddingResponse mockEmbedding = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, new float[]{0.1f, 0.2f})), "text-embedding-3-small", null
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(mockEmbedding);
		VectorSearchResult mismatch =
				new VectorSearchResult("doc2", 0.01, Map.of("prompt_text", "How to disable 2FA"));
		when(vectorClient.searchKnn(anyString(), anyString(), any(), eq(2))).thenReturn(List.of(mismatch));

		assertThat(cache.findSemanticMatch(enableKey, 0.0)).as("polarity mismatch").isNull();
		verify(vectorClient, times(1)).searchKnn(anyString(), anyString(), any(), eq(2));
	}

	@Test
	@DisplayName("private two-arg generateEmbedding delegates to the memoized path")
	void twoArgGenerateEmbeddingDelegates() throws Exception {
		EmbeddingResponse mockEmbedding = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, new float[]{0.1f, 0.2f})), "text-embedding-3-small", null
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(mockEmbedding);

		Method delegate =
				RedisSemanticVectorCache.class.getDeclaredMethod("generateEmbedding", String.class, String.class);
		delegate.setAccessible(true);
		float[] vector = (float[]) delegate.invoke(cache, "Hello", "tenant1");

		assertThat(vector).as("delegated vector").containsExactly(0.1f, 0.2f);
	}

	@Test
	@DisplayName("an empty memoized vector is recomputed instead of reused")
	void emptyMemoizedVectorRecomputes() {
		CompoundCacheKey key = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "", "", "Hello"
		);

		EmbeddingResponse mockEmbedding = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, new float[]{0.1f, 0.2f})), "text-embedding-3-small", null
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(mockEmbedding);
		when(vectorClient.searchKnn(anyString(), anyString(), any(), eq(2))).thenReturn(List.of());

		Map<String, float[]> memo = new HashMap<>();
		memo.put("Hello", new float[0]);
		assertThat(cache.findSemanticMatch(key, 0.0, memo)).as("lookup miss").isNull();
		verify(embeddingService, times(1)).processEmbedding(any(), eq("tenant1"));
		assertThat(memo.get("Hello")).as("recomputed memo").containsExactly(0.1f, 0.2f);
	}

	@Test
	@DisplayName("a failed embedding with a memo leaves the memo untouched")
	void failedEmbeddingSkipsMemoPut() {
		CompoundCacheKey key = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "", "", "How to reset password"
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1")))
				.thenThrow(new RuntimeException("provider down"));

		Map<String, float[]> memo = new HashMap<>();
		assertThat(cache.findSemanticMatch(key, 0.0, memo)).as("failed lookup").isNull();
		assertThat(memo).as("untouched memo").isEmpty();
	}

	@Test
	@DisplayName("an empty computed vector is never memoized")
	void emptyComputedVectorSkipsMemoPut() {
		CompoundCacheKey key = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "", "", "Hello"
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, new float[0])), "text-embedding-3-small", null));

		Map<String, float[]> memo = new HashMap<>();
		assertThat(cache.findSemanticMatch(key, 0.0, memo)).as("empty-vector lookup").isNull();
		verify(vectorClient, never()).searchKnn(anyString(), anyString(), any(), anyInt());
		assertThat(memo).as("unmemoized empty vector").isEmpty();
	}

	@Test
	@DisplayName("findSemanticMatch falls back on blank numeric and temperature fields")
	void findSemanticMatchFallsBackOnBlankFields() {
		CompoundCacheKey key = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "", "", "Hello"
		);

		EmbeddingResponse mockEmbedding = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, new float[]{0.1f, 0.2f})), "text-embedding-3-small", null
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(mockEmbedding);

		VectorSearchResult match = new VectorSearchResult(
				"doc1", 0.02,
				Map.of(
						"prompt_text", "Hello",
						"response_json", "{}",
						"prompt_tokens", "",
						"completion_tokens", "   ",
						"temperature", ""
				)
		);
		when(vectorClient.searchKnn(anyString(), anyString(), any(), eq(2))).thenReturn(List.of(match));

		CacheEntry entry = cache.findSemanticMatch(key, 0.0);
		assertThat(entry).as("entry with blank fields").isNotNull();
		assertThat(entry.promptTokens()).as("blank prompt tokens").isZero();
		assertThat(entry.completionTokens()).as("blank completion tokens").isZero();
		assertThat(entry.temperature()).as("blank temperature").isNull();
	}

	@Test
	@DisplayName("findSemanticMatch falls back on an unparsable temperature field")
	void findSemanticMatchFallsBackOnInvalidTemperature() {
		CompoundCacheKey key = new CompoundCacheKey(
				"tenant1", CacheScope.TENANT, "gpt-4o", "exactHash", "", "", "Hello"
		);

		EmbeddingResponse mockEmbedding = new EmbeddingResponse(
				"list", List.of(EmbeddingData.of(0, new float[]{0.1f, 0.2f})), "text-embedding-3-small", null
		);
		when(embeddingService.processEmbedding(any(), eq("tenant1"))).thenReturn(mockEmbedding);

		VectorSearchResult match = new VectorSearchResult(
				"doc1", 0.02,
				Map.of("prompt_text", "Hello", "response_json", "{}", "temperature", "boiling")
		);
		when(vectorClient.searchKnn(anyString(), anyString(), any(), eq(2))).thenReturn(List.of(match));

		CacheEntry entry = cache.findSemanticMatch(key, 0.0);
		assertThat(entry).as("entry with invalid temperature").isNotNull();
		assertThat(entry.temperature()).as("invalid temperature").isNull();
	}
}
