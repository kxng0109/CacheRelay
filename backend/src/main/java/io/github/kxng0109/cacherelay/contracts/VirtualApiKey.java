package io.github.kxng0109.cacherelay.contracts;

import io.github.kxng0109.cacherelay.cache.contracts.CacheScope;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * A gateway-managed virtual API key, resolved from its {@link SHA256Hash}.
 *
 * <p>Stored in Redis as a hash under {@code apikey:{hashHex}}; never persisted
 * in plaintext. {@code rpmLimit}/{@code tpmLimit} of 0 mean unlimited.</p>
 *
 * @param keyHash          SHA-256 of the plaintext key (Redis key)
 * @param keyPrefix        human-visible prefix, e.g. {@code gw-}
 * @param ownerId          tenant/owner identifier used for ledger attribution
 * @param name             human-readable label
 * @param rpmLimit         requests-per-minute limit (0 = unlimited)
 * @param tpmLimit         tokens-per-minute limit (0 = unlimited)
 * @param allowedModels    empty set means "all models allowed"
 * @param allowedProviders empty set means "all providers allowed"
 * @param allowedTools     empty set means "all tools allowed"
 * @param deniedTools      empty set means "no tools denied"
 * @param allowedResources empty set means "all resource servers visible"
 * @param deniedResources  empty set means "no resource servers hidden"
 * @param allowedPrompts   empty set means "all prompts visible"
 * @param deniedPrompts    empty set means "no prompts hidden"
 * @param injectionBlock   whether indirect prompt injection markers in tool output block delivery (true) or only warn (false)
 * @param enabled          whether the key is currently active
 * @param createdAt        creation timestamp
 * @param allowedCacheScopes cache isolation scopes the key may use (SEC-01: the request header can only
 *                           select within this server-side allowlist; null or empty means TENANT only)
 * @param allowedAgents    empty set means "all A2A agents allowed"
 * @param deniedAgents     empty set means "no A2A agents denied"
 */
public record VirtualApiKey(
		SHA256Hash keyHash,
		String keyPrefix,
		String ownerId,
		String name,
		int rpmLimit,
		int tpmLimit,
		Set<String> allowedModels,
		Set<String> allowedProviders,
		Set<String> allowedTools,
		Set<String> deniedTools,
		Set<String> allowedResources,
		Set<String> deniedResources,
		Set<String> allowedPrompts,
		Set<String> deniedPrompts,
		boolean injectionBlock,
		boolean enabled,
		Instant createdAt,
		Set<CacheScope> allowedCacheScopes,
		Set<String> allowedAgents,
		Set<String> deniedAgents
) {
	/**
	 * Stores immutable copies of the allow and deny lists so callers cannot mutate the
	 * key metadata after it is resolved.
	 */
	public VirtualApiKey {
		allowedModels = allowedModels == null ? Set.of() : Set.copyOf(allowedModels);
		allowedProviders = allowedProviders == null ? Set.of() : Set.copyOf(allowedProviders);
		allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
		deniedTools = deniedTools == null ? Set.of() : Set.copyOf(deniedTools);
		allowedResources = allowedResources == null ? Set.of() : Set.copyOf(allowedResources);
		deniedResources = deniedResources == null ? Set.of() : Set.copyOf(deniedResources);
		allowedPrompts = allowedPrompts == null ? Set.of() : Set.copyOf(allowedPrompts);
		deniedPrompts = deniedPrompts == null ? Set.of() : Set.copyOf(deniedPrompts);
		allowedCacheScopes = normalizeCacheScopes(allowedCacheScopes);
		allowedAgents = allowedAgents == null ? Set.of() : Set.copyOf(allowedAgents);
		deniedAgents = deniedAgents == null ? Set.of() : Set.copyOf(deniedAgents);
	}

	/**
	 * Normalizes the cache-scope allowlist to a non-empty immutable set, defaulting to
	 * TENANT-only when nothing was configured (fail-closed: no scope is granted implicitly).
	 *
	 * @param scopes configured scopes, possibly {@code null} or empty
	 * @return non-empty immutable scope set
	 */
	public static Set<CacheScope> normalizeCacheScopes(Set<CacheScope> scopes) {
		if (scopes == null || scopes.isEmpty()) {
			return Set.of(CacheScope.TENANT);
		}
		EnumSet<CacheScope> copy = EnumSet.noneOf(CacheScope.class);
		for (CacheScope scope : scopes) {
			if (scope != null) {
				copy.add(scope);
			}
		}
		return copy.isEmpty() ? Set.of(CacheScope.TENANT) : Set.copyOf(copy);
	}

	/**
	 * Backwards-compatible constructor omitting the A2A agent policy sets.
	 */
	public VirtualApiKey(
			SHA256Hash keyHash,
			String keyPrefix,
			String ownerId,
			String name,
			int rpmLimit,
			int tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders,
			Set<String> allowedTools,
			Set<String> deniedTools,
			Set<String> allowedResources,
			Set<String> deniedResources,
			Set<String> allowedPrompts,
			Set<String> deniedPrompts,
			boolean injectionBlock,
			boolean enabled,
			Instant createdAt,
			Set<CacheScope> allowedCacheScopes
	) {
		this(
				keyHash,
				keyPrefix,
				ownerId,
				name,
				rpmLimit,
				tpmLimit,
				allowedModels,
				allowedProviders,
				allowedTools,
				deniedTools,
				allowedResources,
				deniedResources,
				allowedPrompts,
				deniedPrompts,
				injectionBlock,
				enabled,
				createdAt,
				allowedCacheScopes,
				Set.of(),
				Set.of()
		);
	}

	/**
	 * Backwards-compatible constructor omitting tool-level RBAC/ABAC sets.
	 */
	public VirtualApiKey(
			SHA256Hash keyHash,
			String keyPrefix,
			String ownerId,
			String name,
			int rpmLimit,
			int tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders,
			boolean enabled,
			Instant createdAt
	) {
		this(
				keyHash,
				keyPrefix,
				ownerId,
				name,
				rpmLimit,
				tpmLimit,
				allowedModels,
				allowedProviders,
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				true,
				enabled,
				createdAt,
				Set.of(CacheScope.TENANT),
				Set.of(),
				Set.of()
		);
	}

	/**
	 * Backwards-compatible constructor omitting the cache-scope allowlist (defaults to TENANT-only).
	 */
	public VirtualApiKey(
			SHA256Hash keyHash,
			String keyPrefix,
			String ownerId,
			String name,
			int rpmLimit,
			int tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders,
			Set<String> allowedTools,
			Set<String> deniedTools,
			Set<String> allowedResources,
			Set<String> deniedResources,
			Set<String> allowedPrompts,
			Set<String> deniedPrompts,
			boolean injectionBlock,
			boolean enabled,
			Instant createdAt
	) {
		this(
				keyHash,
				keyPrefix,
				ownerId,
				name,
				rpmLimit,
				tpmLimit,
				allowedModels,
				allowedProviders,
				allowedTools,
				deniedTools,
				allowedResources,
				deniedResources,
				allowedPrompts,
				deniedPrompts,
				injectionBlock,
				enabled,
				createdAt,
				Set.of(CacheScope.TENANT),
				Set.of(),
				Set.of()
		);
	}

	/**
	 * Backwards-compatible constructor omitting resource/prompt visibility sets.
	 */
	public VirtualApiKey(
			SHA256Hash keyHash,
			String keyPrefix,
			String ownerId,
			String name,
			int rpmLimit,
			int tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders,
			Set<String> allowedTools,
			Set<String> deniedTools,
			boolean enabled,
			Instant createdAt
	) {
		this(
				keyHash,
				keyPrefix,
				ownerId,
				name,
				rpmLimit,
				tpmLimit,
				allowedModels,
				allowedProviders,
				allowedTools,
				deniedTools,
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				true,
				enabled,
				createdAt,
				Set.of(CacheScope.TENANT),
				Set.of(),
				Set.of()
		);
	}
}
