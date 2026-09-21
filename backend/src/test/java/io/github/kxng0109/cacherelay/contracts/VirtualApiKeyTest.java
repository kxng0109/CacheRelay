package io.github.kxng0109.cacherelay.contracts;

import io.github.kxng0109.cacherelay.cache.contracts.CacheScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the defensive copying behavior of {@link VirtualApiKey}: the allow lists are stored as immutable snapshots,
 * so callers cannot mutate key metadata after it is resolved, and null input degrades to empty sets.
 */
@DisplayName("VirtualApiKey")
class VirtualApiKeyTest {

	@Test
	@DisplayName("allow lists are stored as immutable snapshots")
	void allowListsAreStoredAsImmutableSnapshots() {
		Set<String> models = new LinkedHashSet<>();
		models.add("gpt-56-luna");
		Set<String> providers = new LinkedHashSet<>();
		providers.add("openai");

		VirtualApiKey key = key(models, providers);

		models.add("sneaky-model");
		providers.add("sneaky-provider");
		assertEquals(Set.of("gpt-56-luna"), key.allowedModels(), "later mutations to the caller's set must not leak in");
		assertEquals(Set.of("openai"), key.allowedProviders(), "later mutations to the caller's set must not leak in");
		assertThrows(
				UnsupportedOperationException.class,
				() -> key.allowedModels().add("x"),
				"the stored allowed models must be unmodifiable"
		);
		assertThrows(
				UnsupportedOperationException.class,
				() -> key.allowedProviders().add("x"),
				"the stored allowed providers must be unmodifiable"
		);
	}

	@Test
	@DisplayName("null allow lists degrade to empty sets")
	void nullAllowListsDegradeToEmptySets() {
		VirtualApiKey key = key(null, null);
		assertTrue(key.allowedModels().isEmpty());
		assertTrue(key.allowedProviders().isEmpty());
	}

	private static VirtualApiKey key(Set<String> allowedModels, Set<String> allowedProviders) {
		return new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-secret"),
				"gw-",
				"owner",
				"test key",
				0,
				0,
				allowedModels,
				allowedProviders,
				true,
				Instant.parse("2026-01-01T00:00:00Z")
		);
	}

	@Test
	@DisplayName("normalizeCacheScopes defaults null, empty, and all-null sets to TENANT-only")
	void normalizeCacheScopesDefaults() {
		assertEquals(Set.of(CacheScope.TENANT), VirtualApiKey.normalizeCacheScopes(null));
		assertEquals(Set.of(CacheScope.TENANT), VirtualApiKey.normalizeCacheScopes(Set.of()));
		Set<CacheScope> allNull = new HashSet<>();
		allNull.add(null);
		assertEquals(Set.of(CacheScope.TENANT), VirtualApiKey.normalizeCacheScopes(allNull));
	}

	@Test
	@DisplayName("normalizeCacheScopes keeps configured scopes and drops nulls")
	void normalizeCacheScopesKeeps() {
		Set<CacheScope> withNull = new HashSet<>();
		withNull.add(CacheScope.GLOBAL);
		withNull.add(null);
		withNull.add(CacheScope.TENANT);
		assertEquals(Set.of(CacheScope.GLOBAL, CacheScope.TENANT),
				VirtualApiKey.normalizeCacheScopes(withNull));
	}
}