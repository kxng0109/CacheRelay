package io.github.kxng0109.cacherelay.cache.engine;

import io.github.kxng0109.cacherelay.cache.config.CacheRelayCacheProperties;
import io.github.kxng0109.cacherelay.cache.contracts.CacheScope;
import io.github.kxng0109.cacherelay.cache.contracts.CompoundCacheKey;
import io.github.kxng0109.cacherelay.cache.engine.l0.InMemoryExactCache;
import io.github.kxng0109.cacherelay.cache.engine.l1.RedisExactCache;
import io.github.kxng0109.cacherelay.cache.engine.l2.RedisSemanticVectorCache;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.proxy.protocol.OpenAiChatRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Cache scope isolation (SEC-01)")
class CacheScopeIsolationTest {

	private final InMemoryExactCache l0Cache = mock(InMemoryExactCache.class);
	private final RedisExactCache l1Cache = mock(RedisExactCache.class);
	private final RedisSemanticVectorCache l2Cache = mock(RedisSemanticVectorCache.class);
	private final CacheKeyGenerator keyGenerator = new CacheKeyGenerator();
	private final CacheRelayCacheProperties properties = new CacheRelayCacheProperties();
	private final CachePolicyEngine policyEngine = new CachePolicyEngine(properties);
	private final SingleFlightManager singleFlightManager = new SingleFlightManager();
	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
	private final ObjectMapper objectMapper = new ObjectMapper();

	private CacheRelayCacheService cacheService;

	@BeforeEach
	void setUp() {
		cacheService = new CacheRelayCacheService(
				l0Cache, l1Cache, l2Cache, keyGenerator, policyEngine, singleFlightManager, properties, meterRegistry
		);
	}

	private static VirtualApiKey key(String owner, Set<CacheScope> scopes) {
		return new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-isolation-test-key-0123456789ab"),
				"gw-",
				owner,
				"isolation-test",
				120,
				500000,
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				true,
				true,
				Instant.parse("2026-09-01T00:00:00Z"),
				scopes
		);
	}

	private OpenAiChatRequest chatRequest() {
		return new OpenAiChatRequest(
				"gpt-4o",
				List.of(new OpenAiChatRequest.Message("user", objectMapper.valueToTree("Hello"))),
				0.0, null, null, null, null, true, null
		);
	}

	@Test
	@DisplayName("forged GLOBAL header never produces a global owner id for a TENANT-only key")
	void forgedGlobalHeaderStaysTenant() {
		MockHttpServletRequest httpReq = new MockHttpServletRequest();
		httpReq.addHeader("X-CacheRelay-Cache-Scope", "GLOBAL");

		when(l0Cache.get(any())).thenReturn(null);
		when(l1Cache.get(any())).thenReturn(null);
		when(l2Cache.findSemanticMatch(any(), any())).thenReturn(null);

		cacheService.evaluateCache(chatRequest(), httpReq, "tenant-a",
				key("tenant-a", Set.of(CacheScope.TENANT)));

		ArgumentCaptor<CompoundCacheKey> keys = ArgumentCaptor.forClass(CompoundCacheKey.class);
		verify(l1Cache).get(keys.capture());
		assertThat(keys.getValue().scope()).isEqualTo(CacheScope.TENANT);
		assertThat(keys.getValue().ownerId()).isEqualTo("tenant-a");
	}

	@Test
	@DisplayName("forged victim user id never leaks into the cache namespace")
	void forgedUserIdIgnored() {
		MockHttpServletRequest httpReq = new MockHttpServletRequest();
		httpReq.addHeader("X-CacheRelay-Cache-Scope", "USER");
		httpReq.addHeader("X-User-Id", "victim-user");

		CacheScope scope = policyEngine.resolveScope(
				httpReq, key("tenant-a", Set.of(CacheScope.TENANT, CacheScope.USER)));
		CompoundCacheKey cacheKey =
				keyGenerator.generateKey(chatRequest(), "tenant-a", scope, null, 4);
		assertThat(cacheKey.scope()).isEqualTo(CacheScope.TENANT);
		assertThat(cacheKey.ownerId()).doesNotContain("victim-user");
	}

	@Test
	@DisplayName("identical prompts from two tenants produce disjoint cache keys")
	void tenantsNeverShareKeys() {
		CompoundCacheKey keyA = keyGenerator.generateKey(
				chatRequest(), "tenant-a", CacheScope.TENANT, null, 4);
		CompoundCacheKey keyB = keyGenerator.generateKey(
				chatRequest(), "tenant-b", CacheScope.TENANT, null, 4);
		assertThat(keyA.ownerId()).isNotEqualTo(keyB.ownerId());
		assertThat(keyA.exactHash()).isNotEqualTo(keyB.exactHash());
	}

	@Test
	@DisplayName("operator-enabled GLOBAL key shares one global namespace")
	void enabledGlobalKeySharesNamespace() {
		properties.setGlobalScopeEnabled(true);
		try {
			MockHttpServletRequest httpReq = new MockHttpServletRequest();
			httpReq.addHeader("X-CacheRelay-Cache-Scope", "GLOBAL");
			VirtualApiKey globalKey =
					key("tenant-a", Set.of(CacheScope.TENANT, CacheScope.GLOBAL));

			CacheScope scope = policyEngine.resolveScope(httpReq, globalKey);
			CompoundCacheKey cacheKey =
					keyGenerator.generateKey(chatRequest(), "tenant-a", scope, null, 4);
			assertThat(scope).isEqualTo(CacheScope.GLOBAL);
			assertThat(cacheKey.ownerId()).isEqualTo("global");
		} finally {
			properties.setGlobalScopeEnabled(false);
		}
	}
}
