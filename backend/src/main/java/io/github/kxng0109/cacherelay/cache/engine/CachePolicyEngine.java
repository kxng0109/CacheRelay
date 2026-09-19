package io.github.kxng0109.cacherelay.cache.engine;

import io.github.kxng0109.cacherelay.cache.config.CacheRelayCacheProperties;
import io.github.kxng0109.cacherelay.cache.contracts.CacheScope;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.proxy.protocol.OpenAiChatRequest;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Evaluates HTTP RFC 9111 caching headers, gateway extension headers, and request parameters to decide caching
 * eligibility, scope, and threshold overrides.
 */
@Component
@RequiredArgsConstructor
public class CachePolicyEngine {

	private final CacheRelayCacheProperties properties;

	/**
	 * Determines whether cache lookup should be attempted for the incoming request.
	 *
	 * @param request     client chat request
	 * @param httpRequest servlet HTTP request
	 * @return true if cache evaluation is permitted, false to bypass
	 */
	public boolean shouldEvaluateCache(OpenAiChatRequest request, HttpServletRequest httpRequest) {
		if (!properties.isEnabled()) {
			return false;
		}

		String cacheControl = httpRequest.getHeader("Cache-Control");
		if (cacheControl != null) {
			String ccLower = cacheControl.toLowerCase(Locale.ROOT);
			if (ccLower.contains("no-store") || ccLower.contains("no-cache") || ccLower.contains("max-age=0")) {
				return false;
			}
		}

		String cacherelayNoCache = httpRequest.getHeader("X-CacheRelay-No-Cache");
		if ("true".equalsIgnoreCase(cacherelayNoCache)) {
			return false;
		}

		String cacheMode = httpRequest.getHeader("X-CacheRelay-Cache-Mode");
		if ("bypass".equalsIgnoreCase(cacheMode) || "write-only".equalsIgnoreCase(cacheMode)) {
			return false;
		}

		// Temperature gating: High temperature requests (> temperatureFloor) bypass semantic caching
		if (request.temperature() != null && request.temperature() > properties.getSemantic().getTemperatureFloor()) {
			String allowStochastic = httpRequest.getHeader("X-CacheRelay-Cache-Stochastic");
			return "true".equalsIgnoreCase(allowStochastic);
		}

		return true;
	}

	/**
	 * Determines whether the response should be saved to cache upon completion.
	 *
	 * @param request     client chat request
	 * @param httpRequest servlet HTTP request
	 * @return true if caching the response is permitted, false otherwise
	 */
	public boolean shouldStoreInCache(OpenAiChatRequest request, HttpServletRequest httpRequest) {
		if (!properties.isEnabled()) {
			return false;
		}

		String cacheControl = httpRequest.getHeader("Cache-Control");
		if (cacheControl != null && cacheControl.toLowerCase(Locale.ROOT).contains("no-store")) {
			return false;
		}

		String cacheMode = httpRequest.getHeader("X-CacheRelay-Cache-Mode");
		if ("bypass".equalsIgnoreCase(cacheMode) || "read-only".equalsIgnoreCase(cacheMode)) {
			return false;
		}

		// Symmetric temperature gate (mirrors shouldEvaluateCache): high-temperature responses are
		// only stored when the client explicitly opts in via X-CacheRelay-Cache-Stochastic. Without this,
		// high-T stochastic responses would pollute the cache and be served to low-T deterministic
		// requests. The opt-in is symmetric: the same header is required to store and to retrieve.
		if (request.temperature() != null && request.temperature() > properties.getSemantic().getTemperatureFloor()) {
			String allowStochastic = httpRequest.getHeader("X-CacheRelay-Cache-Stochastic");
			if (!"true".equalsIgnoreCase(allowStochastic)) {
				return false;
			}
		}

		return true;
	}

	/**
	 * Resolves the multi-tenant isolation scope for the request against the
	 * authenticated key's server-side allowlist (SEC-01).
	 *
	 * <p>The {@code X-CacheRelay-Cache-Scope} header can only <em>select within</em>
	 * the key's {@code allowedCacheScopes}; anything outside is silently ignored
	 * (no error oracle distinguishing allowed from denied). {@code USER} always
	 * degrades to {@code TENANT} because no server-verified end-user claim exists
	 * ({@code X-User-Id} is never trusted). {@code GLOBAL} additionally requires
	 * the operator flag {@code gateway.cache.global-scope-enabled}. A missing key
	 * identity fails closed to {@code TENANT}.</p>
	 *
	 * @param httpRequest servlet HTTP request
	 * @param apiKey      authenticated virtual key, or {@code null} when unavailable
	 * @return resolved CacheScope, never {@code null}
	 */
	public CacheScope resolveScope(HttpServletRequest httpRequest, @Nullable VirtualApiKey apiKey) {
		Set<CacheScope> allowlist =
				apiKey == null ? Set.of(CacheScope.TENANT) : apiKey.allowedCacheScopes();
		String scopeHeader = httpRequest.getHeader("X-CacheRelay-Cache-Scope");
		CacheScope requested = null;
		if (scopeHeader != null) {
			try {
				requested = CacheScope.valueOf(scopeHeader.trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException ignored) {
			}
		}
		if (requested == null) {
			return floorScope(allowlist);
		}
		if (requested == CacheScope.USER) {
			return CacheScope.TENANT;
		}
		if (requested == CacheScope.GLOBAL
				&& (!properties.isGlobalScopeEnabled() || !allowlist.contains(CacheScope.GLOBAL))) {
			return floorScope(allowlist);
		}
		if (!allowlist.contains(requested)) {
			return floorScope(allowlist);
		}
		return requested;
	}

	/**
	 * Computes the safe fallback scope: the configured default when the key allows
	 * it (and the operator flag permits GLOBAL), otherwise TENANT.
	 *
	 * @param allowlist key's server-side scope allowlist (non-empty)
	 * @return fallback CacheScope
	 */
	private CacheScope floorScope(Set<CacheScope> allowlist) {
		CacheScope configured = properties.getDefaultScope();
		if (configured == null || configured == CacheScope.USER) {
			return CacheScope.TENANT;
		}
		if (configured == CacheScope.GLOBAL
				&& (!properties.isGlobalScopeEnabled() || !allowlist.contains(CacheScope.GLOBAL))) {
			return CacheScope.TENANT;
		}
		if (!allowlist.contains(configured)) {
			return CacheScope.TENANT;
		}
		return configured;
	}

	/**
	 * Resolves any custom similarity threshold override from client headers.
	 *
	 * @param httpRequest servlet HTTP request
	 * @return threshold in range [0.0, 1.0]
	 */
	public double resolveSimilarityThreshold(HttpServletRequest httpRequest) {
		String thresholdHeader = httpRequest.getHeader("X-CacheRelay-Semantic-Threshold");
		if (thresholdHeader != null) {
			try {
				double parsed = Double.parseDouble(thresholdHeader.trim());
				if (parsed >= 0.50 && parsed <= 1.00) {
					return parsed;
				}
			} catch (NumberFormatException ignored) {
			}
		}
		return properties.getSemantic().getSimilarityThreshold();
	}
}
