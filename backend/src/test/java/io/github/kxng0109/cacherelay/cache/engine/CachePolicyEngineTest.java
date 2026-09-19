package io.github.kxng0109.cacherelay.cache.engine;

import io.github.kxng0109.cacherelay.cache.config.CacheRelayCacheProperties;
import io.github.kxng0109.cacherelay.cache.contracts.CacheScope;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.proxy.protocol.OpenAiChatRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CachePolicyEngine")
class CachePolicyEngineTest {

	private final CacheRelayCacheProperties properties = new CacheRelayCacheProperties();
	private final CachePolicyEngine policyEngine = new CachePolicyEngine(properties);

	@Test
	@DisplayName("shouldEvaluateCache respects RFC 9111 headers, extension headers, and temperature floor")
	void shouldEvaluateCache() {
		OpenAiChatRequest normalReq = new OpenAiChatRequest(
				"gpt-4o",
				List.of(),
				0.0,
				null,
				null,
				null,
				null,
				true,
				null
		);
		MockHttpServletRequest httpReq = new MockHttpServletRequest();

		// Default: enabled
		assertThat(policyEngine.shouldEvaluateCache(normalReq, httpReq)).isTrue();

		// Cache-Control: no-cache / no-store / max-age=0
		MockHttpServletRequest ccNoCache = new MockHttpServletRequest();
		ccNoCache.addHeader("Cache-Control", "no-cache");
		assertThat(policyEngine.shouldEvaluateCache(normalReq, ccNoCache)).isFalse();

		MockHttpServletRequest ccNoStore = new MockHttpServletRequest();
		ccNoStore.addHeader("Cache-Control", "no-store");
		assertThat(policyEngine.shouldEvaluateCache(normalReq, ccNoStore)).isFalse();

		// X-CacheRelay-No-Cache: true
		MockHttpServletRequest cacherelayNoCache = new MockHttpServletRequest();
		cacherelayNoCache.addHeader("X-CacheRelay-No-Cache", "true");
		assertThat(policyEngine.shouldEvaluateCache(normalReq, cacherelayNoCache)).isFalse();

		// Cache-Control: max-age=0
		MockHttpServletRequest ccMaxAge = new MockHttpServletRequest();
		ccMaxAge.addHeader("Cache-Control", "max-age=0");
		assertThat(policyEngine.shouldEvaluateCache(normalReq, ccMaxAge)).isFalse();

		// X-CacheRelay-Cache-Mode: write-only
		MockHttpServletRequest cacherelayWriteOnly = new MockHttpServletRequest();
		cacherelayWriteOnly.addHeader("X-CacheRelay-Cache-Mode", "write-only");
		assertThat(policyEngine.shouldEvaluateCache(normalReq, cacherelayWriteOnly)).isFalse();

		// X-CacheRelay-Cache-Mode: bypass in shouldStoreInCache
		MockHttpServletRequest storeBypass = new MockHttpServletRequest();
		storeBypass.addHeader("X-CacheRelay-Cache-Mode", "bypass");
		assertThat(policyEngine.shouldStoreInCache(normalReq, storeBypass)).isFalse();

		// High temperature with false stochastic header
		MockHttpServletRequest nonStochastic = new MockHttpServletRequest();
		nonStochastic.addHeader("X-CacheRelay-Cache-Stochastic", "false");
		OpenAiChatRequest highTempReq2 = new OpenAiChatRequest(
				"gpt-4o",
				List.of(),
				0.7,
				null,
				null,
				null,
				null,
				true,
				null
		);
		assertThat(policyEngine.shouldEvaluateCache(highTempReq2, nonStochastic)).isFalse();

		// High temperature without stochastic header
		MockHttpServletRequest noStochasticHeader = new MockHttpServletRequest();
		OpenAiChatRequest highTempReq3 = new OpenAiChatRequest(
				"gpt-4o",
				List.of(),
				0.8,
				null,
				null,
				null,
				null,
				true,
				null
		);
		assertThat(policyEngine.shouldEvaluateCache(highTempReq3, noStochasticHeader)).isFalse();

		// shouldStoreInCache with read-write mode
		MockHttpServletRequest readWrite = new MockHttpServletRequest();
		readWrite.addHeader("X-CacheRelay-Cache-Mode", "read-write");
		assertThat(policyEngine.shouldStoreInCache(normalReq, readWrite)).isTrue();

		// Out of range upper threshold (> 1.00)
		MockHttpServletRequest highThreshReq = new MockHttpServletRequest();
		highThreshReq.addHeader("X-CacheRelay-Semantic-Threshold", "1.50");
		assertThat(policyEngine.resolveSimilarityThreshold(highThreshReq)).isEqualTo(0.80);

		// High temperature (0.7 > 0.1) without stochastic override -> bypass
		OpenAiChatRequest highTempReq = new OpenAiChatRequest(
				"gpt-4o",
				List.of(),
				0.7,
				null,
				null,
				null,
				null,
				true,
				null
		);
		assertThat(policyEngine.shouldEvaluateCache(highTempReq, httpReq)).isFalse();

		// High temperature with X-CacheRelay-Cache-Stochastic: true -> allowed
		MockHttpServletRequest stochasticReq = new MockHttpServletRequest();
		stochasticReq.addHeader("X-CacheRelay-Cache-Stochastic", "true");
		assertThat(policyEngine.shouldEvaluateCache(highTempReq, stochasticReq)).isTrue();
	}

	@Test
	@DisplayName("shouldStoreInCache respects no-store and read-only directives")
	void shouldStoreInCache() {
		OpenAiChatRequest normalReq = new OpenAiChatRequest(
				"gpt-4o",
				List.of(),
				0.0,
				null,
				null,
				null,
				null,
				true,
				null
		);
		MockHttpServletRequest httpReq = new MockHttpServletRequest();
		assertThat(policyEngine.shouldStoreInCache(normalReq, httpReq)).isTrue();

		MockHttpServletRequest ccNoStore = new MockHttpServletRequest();
		ccNoStore.addHeader("Cache-Control", "no-store");
		assertThat(policyEngine.shouldStoreInCache(normalReq, ccNoStore)).isFalse();

		MockHttpServletRequest readOnly = new MockHttpServletRequest();
		readOnly.addHeader("X-CacheRelay-Cache-Mode", "read-only");
		assertThat(policyEngine.shouldStoreInCache(normalReq, readOnly)).isFalse();

		properties.setEnabled(false);
		assertThat(policyEngine.shouldEvaluateCache(normalReq, httpReq)).isFalse();
		assertThat(policyEngine.shouldStoreInCache(normalReq, httpReq)).isFalse();
		properties.setEnabled(true);
	}

	@Test
	@DisplayName("resolveScope enforces the server-side key policy, never client headers")
	void resolveScopeEnforcesKeyPolicy() {
		VirtualApiKey tenantKey = keyWithScopes(Set.of(CacheScope.TENANT));
		VirtualApiKey globalKey =
				keyWithScopes(Set.of(CacheScope.TENANT, CacheScope.GLOBAL));

		// Forged GLOBAL on a TENANT-only key is ignored (no error oracle)
		MockHttpServletRequest globalReq = new MockHttpServletRequest();
		globalReq.addHeader("X-CacheRelay-Cache-Scope", "GLOBAL");
		assertThat(policyEngine.resolveScope(globalReq, tenantKey)).isEqualTo(CacheScope.TENANT);

		// USER with a forged victim id degrades to TENANT: no verified end-user claim exists
		MockHttpServletRequest userReq = new MockHttpServletRequest();
		userReq.addHeader("X-CacheRelay-Cache-Scope", "USER");
		userReq.addHeader("X-User-Id", "victim-user");
		assertThat(policyEngine.resolveScope(userReq, tenantKey)).isEqualTo(CacheScope.TENANT);

		// GLOBAL allowed by the key but operator-disabled stays TENANT
		assertThat(policyEngine.resolveScope(globalReq, globalKey)).isEqualTo(CacheScope.TENANT);

		// Operator flag on: allowed keys reach GLOBAL, TENANT-only keys still cannot
		properties.setGlobalScopeEnabled(true);
		try {
			assertThat(policyEngine.resolveScope(globalReq, globalKey)).isEqualTo(CacheScope.GLOBAL);
			assertThat(policyEngine.resolveScope(globalReq, tenantKey)).isEqualTo(CacheScope.TENANT);
		} finally {
			properties.setGlobalScopeEnabled(false);
		}

		// Invalid scope header falls back to the default
		MockHttpServletRequest invalidReq = new MockHttpServletRequest();
		invalidReq.addHeader("X-CacheRelay-Cache-Scope", "INVALID_SCOPE");
		assertThat(policyEngine.resolveScope(invalidReq, tenantKey)).isEqualTo(CacheScope.TENANT);

		// Missing key identity fails closed to TENANT
		assertThat(policyEngine.resolveScope(globalReq, null)).isEqualTo(CacheScope.TENANT);
	}

	@Test
	@DisplayName("resolveSimilarityThreshold parses header overrides and fallbacks")
	void resolveSimilarityThreshold() {
		MockHttpServletRequest req = new MockHttpServletRequest();
		req.addHeader("X-CacheRelay-Semantic-Threshold", "0.95");

		assertThat(policyEngine.resolveSimilarityThreshold(req)).isEqualTo(0.95);

		// Invalid or out-of-range overrides fall back to defaults
		MockHttpServletRequest invalidReq = new MockHttpServletRequest();
		invalidReq.addHeader("X-CacheRelay-Semantic-Threshold", "0.20"); // below 0.50 min
		assertThat(policyEngine.resolveSimilarityThreshold(invalidReq)).isEqualTo(0.80);

		MockHttpServletRequest malformedReq = new MockHttpServletRequest();
		malformedReq.addHeader("X-CacheRelay-Semantic-Threshold", "abc");
		assertThat(policyEngine.resolveSimilarityThreshold(malformedReq)).isEqualTo(0.80);
	}

	private static VirtualApiKey keyWithScopes(Set<CacheScope> scopes) {
		return new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-test-scope-key-0123456789abcdef"),
				"gw-",
				"tenant-a",
				"scope-test",
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
}
