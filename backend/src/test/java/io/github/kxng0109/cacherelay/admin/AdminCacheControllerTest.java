package io.github.kxng0109.cacherelay.admin;

import io.github.kxng0109.cacherelay.admin.dto.CachePurgeResponse;
import io.github.kxng0109.cacherelay.admin.dto.CacheStatsResponse;
import io.github.kxng0109.cacherelay.admin.dto.CacheTierStatsResponse;
import io.github.kxng0109.cacherelay.admin.dto.TierStats;
import io.github.kxng0109.cacherelay.cache.config.CacheRelayCacheProperties;
import io.github.kxng0109.cacherelay.cache.engine.CachePurgeNotifier;
import io.github.kxng0109.cacherelay.cache.engine.CacheRelayCacheService;
import io.github.kxng0109.cacherelay.cache.engine.l2.RediSearchVectorClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.Set;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("AdminCacheController")
class AdminCacheControllerTest {

	private final CacheRelayCacheService cacheService = mock(CacheRelayCacheService.class);
	private final CacheRelayCacheProperties properties = new CacheRelayCacheProperties();
	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
	private final RediSearchVectorClient vectorClient = mock(RediSearchVectorClient.class);
	private final RedisTierProbe tierProbe = mock(RedisTierProbe.class);
	private AdminCacheController controller;

	@BeforeEach
	void setUp() {
		controller = new AdminCacheController(cacheService, properties, redisTemplate, vectorClient, tierProbe);
	}

	@Test
	@DisplayName("getCacheStats returns current cache configuration parameters")
	void getCacheStats() {
		ResponseEntity<CacheStatsResponse> response = controller.getCacheStats();
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		CacheStatsResponse stats = response.getBody();
		assertThat(stats).isNotNull();
		assertThat(stats.enabled()).isTrue();
		assertThat(stats.similarityThreshold()).isEqualTo(0.80);
		assertThat(stats.embeddingModel()).isEqualTo("text-embedding-3-small");
	}

	@Test
	@DisplayName("getTierStats serves the probe snapshot")
	void getTierStats() {
		CacheTierStatsResponse snapshot = new CacheTierStatsResponse(java.time.Instant.EPOCH,
				TierStats.unreachable(), TierStats.unreachable());
		when(tierProbe.tiers()).thenReturn(snapshot);

		ResponseEntity<CacheTierStatsResponse> response = controller.getTierStats();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isSameAs(snapshot);
		verify(tierProbe).tiers();
	}

	@Test
	@DisplayName("purgeCache executes global and tenant-level purges with SCAN (never KEYS)")
	void purgeCacheOperations() {
		when(redisTemplate.scan(any(ScanOptions.class)))
				.thenAnswer(invocation -> cursorOf("key1", "key2"));
		when(vectorClient.vectorDimensionOf(anyString())).thenReturn(768);

		// Tenant purge
		ResponseEntity<CachePurgeResponse> tenantRes = controller.purgeCache("tenant-abc");
		assertThat(tenantRes.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(tenantRes.getBody()).isNotNull();
		assertThat(tenantRes.getBody().evictedScope()).isEqualTo("tenant-abc");
		verify(cacheService, times(1)).purgeLocalCache();

		// Global purge rebuilds the index at the live dimension (never hardcoded)
		ResponseEntity<CachePurgeResponse> globalRes = controller.purgeCache(null);
		assertThat(globalRes.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(globalRes.getBody()).isNotNull();
		assertThat(globalRes.getBody().evictedScope()).isEqualTo("ALL");
		verify(vectorClient).dropIndex(anyString(), eq(false));
		verify(vectorClient, atLeastOnce())
				.createIndexIfNotExists(anyString(), anyString(), eq(768));

		// Blank ownerId should trigger global purge
		ResponseEntity<CachePurgeResponse> blankOwnerRes = controller.purgeCache("   ");
		assertThat(blankOwnerRes.getBody().evictedScope()).isEqualTo("ALL");

		// Unreadable dimension skips the index rebuild instead of breaking L2
		when(vectorClient.vectorDimensionOf(anyString())).thenReturn(-1);
		clearInvocations(vectorClient);
		ResponseEntity<CachePurgeResponse> unknownDimsRes = controller.purgeCache(null);
		assertThat(unknownDimsRes.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(vectorClient, never()).dropIndex(anyString(), anyBoolean());

		// Exception during index recreation in global purge
		when(vectorClient.vectorDimensionOf(anyString())).thenReturn(768);
		doThrow(new RuntimeException("recreation err")).when(vectorClient)
		                                               .createIndexIfNotExists(anyString(), anyString(), anyInt());
		ResponseEntity<CachePurgeResponse> recreateErrRes = controller.purgeCache(null);
		assertThat(recreateErrRes.getStatusCode()).isEqualTo(HttpStatus.OK);

		// Empty scan and scan failure stay 200
		when(redisTemplate.scan(any(ScanOptions.class)))
				.thenAnswer(invocation -> cursorOf());
		controller.purgeCache("tenant-emptykeys");

		when(redisTemplate.scan(any(ScanOptions.class)))
				.thenThrow(new RuntimeException("Redis scan failure"));
		ResponseEntity<CachePurgeResponse> errorRes = controller.purgeCache("tenant-err");
		assertThat(errorRes.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("ADM-B06: glob metacharacters in ownerId are rejected before any purge")
	void purgeRejectsGlobOwnerId() {
		ResponseEntity<CachePurgeResponse> response = controller.purgeCache("*");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().success()).isFalse();
		verify(redisTemplate, never()).scan(any(ScanOptions.class));
		verify(cacheService, never()).purgeLocalCache();
	}

	@ParameterizedTest
	@ValueSource(strings = {"te?nant", "te[nant", "ten]ant", "te\\nant", "a*b?c[d]e\\f"})
	@DisplayName("ADM-B06: every glob metacharacter class is rejected before any purge")
	void purgeRejectsEveryGlobClass(String ownerId) {
		ResponseEntity<CachePurgeResponse> response = controller.purgeCache(ownerId);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().evictedScope()).isEqualTo("INVALID");
		verify(redisTemplate, never()).scan(any(ScanOptions.class));
	}

	@Test
	@DisplayName("ADM-B06: control characters in ownerId purge normally with sanitized logging")
	void purgeToleratesControlCharacters() {
		when(redisTemplate.scan(any(ScanOptions.class)))
				.thenAnswer(invocation -> cursorOf("key1"));
		when(redisTemplate.delete(anyCollection())).thenReturn(1L);

		ResponseEntity<CachePurgeResponse> response = controller.purgeCache("ten\u007Fant");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().evictedScope()).isEqualTo("ten\u007Fant");
	}

	@Test
	@DisplayName("ADM-B06: wired purge notifier receives tenant and global scopes")
	void purgeNotifiesWiredListener() {
		CachePurgeNotifier notifier = mock(CachePurgeNotifier.class);
		controller.setPurgeNotifier(notifier);
		when(redisTemplate.scan(any(ScanOptions.class)))
				.thenAnswer(invocation -> cursorOf("key1"));
		when(redisTemplate.delete(anyCollection())).thenReturn(1L);

		controller.purgeCache("tenant-abc");
		controller.purgeCache(null);

		verify(notifier).notifyPurge("tenant:tenant-abc");
		verify(notifier).notifyPurge("ALL");
	}

	@Test
	@DisplayName("ADM-B06: null and failing batch deletes degrade to zero counts")
	void purgeBatchDeleteDegrades() {
		when(redisTemplate.scan(any(ScanOptions.class)))
				.thenAnswer(invocation -> cursorOf("key1"))
				.thenAnswer(invocation -> cursorOf("key1"));
		when(redisTemplate.delete(anyCollection())).thenReturn(null);

		ResponseEntity<CachePurgeResponse> nullRes = controller.purgeCache("tenant-null");
		assertThat(nullRes.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(nullRes.getBody().evictedKeys()).isZero();

		when(redisTemplate.delete(anyCollection())).thenThrow(new RuntimeException("Redis down"));
		ResponseEntity<CachePurgeResponse> errorRes = controller.purgeCache("tenant-err");
		assertThat(errorRes.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(errorRes.getBody().evictedKeys()).isZero();
	}

	@Test
	@DisplayName("ADM-B06: tenant purge reports the deleted key count")
	void purgeReportsDeletedCounts() {
		when(redisTemplate.scan(any(ScanOptions.class)))
				.thenAnswer(invocation -> cursorOf("key1", "key2"));
		when(redisTemplate.delete(anyCollection())).thenReturn(2L);

		ResponseEntity<CachePurgeResponse> response = controller.purgeCache("tenant-abc");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().evictedScope()).isEqualTo("tenant-abc");
		assertThat(response.getBody().evictedKeys()).isEqualTo(4L);
	}

	@Test
	@DisplayName("purge deletes oversized key sets in batches")
	void purgeDeletesInBatches() {
		String[] manyKeys = new String[501];
		Arrays.fill(manyKeys, "key");
		for (int i = 0; i < manyKeys.length; i++) {
			manyKeys[i] = "key" + i;
		}
		when(redisTemplate.scan(any(ScanOptions.class)))
				.thenAnswer(invocation -> cursorOf(manyKeys));
		when(vectorClient.vectorDimensionOf(anyString())).thenReturn(768);
		when(redisTemplate.delete(anyCollection())).thenReturn(501L);

		ResponseEntity<CachePurgeResponse> response = controller.purgeCache(null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(redisTemplate, atLeast(2)).delete(anyCollection());
	}

	@SuppressWarnings("unchecked")
	private static Cursor<String> cursorOf(String... keys) {
		Cursor<String> cursor = mock(Cursor.class);
		if (keys.length == 0) {
			when(cursor.hasNext()).thenReturn(false);
			return cursor;
		}
		Boolean[] hasNext = new Boolean[keys.length + 1];
		Arrays.fill(hasNext, 0, keys.length, Boolean.TRUE);
		hasNext[keys.length] = Boolean.FALSE;
		when(cursor.hasNext()).thenReturn(true, hasNext);
		when(cursor.next()).thenReturn(keys[0], Arrays.copyOfRange(keys, 1, keys.length));
		return cursor;
	}
}
