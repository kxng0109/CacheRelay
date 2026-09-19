package io.github.kxng0109.cacherelay.contracts;

import io.github.kxng0109.cacherelay.cache.contracts.CacheScope;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.Set;

/**
 * A virtual API key seeded at startup from configuration (used until the deferred admin/JWT path provides full CRUD).
 * Bound from {@code gateway.bootstrap-keys}.
 *
 * <p>Visibility defaults (FS-02): visibility sets left unset seed as empty, which loads as
 * allow-all for models/providers/tools/resources/prompts; cache scopes default to TENANT-only;
 * injection handling seeds as block. An unset restriction is not a restriction — configure
 * restrictions explicitly.</p>
 *
 * @param ownerId          owner/tenant id
 * @param name             label
 * @param plaintextKey     raw key including the {@code gw-} prefix (must be a real secret in practice; here it is
 *                         config-supplied)
 * @param rpmLimit         requests-per-minute (0 = unlimited)
 * @param tpmLimit         tokens-per-minute (0 = unlimited)
 * @param allowedModels    empty means all
 * @param allowedProviders empty means all
 * @param allowedTools     empty means all
 * @param deniedTools      empty means none
 * @param allowedResources empty means all visible
 * @param deniedResources  empty means none hidden
 * @param allowedPrompts   empty means all visible
 * @param deniedPrompts    empty means none hidden
 * @param allowedCacheScopes cache isolation scopes (SEC-01; null or empty means TENANT only)
 */
public record BootstrapKey(
		String ownerId,
		String name,
		String plaintextKey,
		int rpmLimit,
		int tpmLimit,
		@Size(max = PolicyBounds.MAX_PATTERNS)
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH)
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN) String> allowedModels,
		@Size(max = PolicyBounds.MAX_PATTERNS)
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH)
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN) String> allowedProviders,
		@Size(max = PolicyBounds.MAX_PATTERNS)
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH)
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN) String> allowedTools,
		@Size(max = PolicyBounds.MAX_PATTERNS)
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH)
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN) String> deniedTools,
		@Size(max = PolicyBounds.MAX_PATTERNS)
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH)
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN) String> allowedResources,
		@Size(max = PolicyBounds.MAX_PATTERNS)
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH)
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN) String> deniedResources,
		@Size(max = PolicyBounds.MAX_PATTERNS)
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH)
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN) String> allowedPrompts,
		@Size(max = PolicyBounds.MAX_PATTERNS)
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH)
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN) String> deniedPrompts,
		Set<CacheScope> allowedCacheScopes
) {
	/**
	 * Canonical constructor: stores immutable copies of the allow and deny lists so callers
	 * cannot mutate the key after it is bound. Designated for configuration binding.
	 */
	@ConstructorBinding
	public BootstrapKey(
			String ownerId,
			String name,
			String plaintextKey,
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
			Set<CacheScope> allowedCacheScopes
	) {
		this.ownerId = ownerId;
		this.name = name;
		this.plaintextKey = plaintextKey;
		this.rpmLimit = rpmLimit;
		this.tpmLimit = tpmLimit;
		this.allowedModels = allowedModels == null ? Set.of() : Set.copyOf(allowedModels);
		this.allowedProviders = allowedProviders == null ? Set.of() : Set.copyOf(allowedProviders);
		this.allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
		this.deniedTools = deniedTools == null ? Set.of() : Set.copyOf(deniedTools);
		this.allowedResources = allowedResources == null ? Set.of() : Set.copyOf(allowedResources);
		this.deniedResources = deniedResources == null ? Set.of() : Set.copyOf(deniedResources);
		this.allowedPrompts = allowedPrompts == null ? Set.of() : Set.copyOf(allowedPrompts);
		this.deniedPrompts = deniedPrompts == null ? Set.of() : Set.copyOf(deniedPrompts);
		this.allowedCacheScopes = VirtualApiKey.normalizeCacheScopes(allowedCacheScopes);
	}

	/**
	 * Backwards-compatible constructor omitting tool-level RBAC/ABAC sets.
	 */
	public BootstrapKey(
			String ownerId,
			String name,
			String plaintextKey,
			int rpmLimit,
			int tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders
	) {
		this(
				ownerId,
				name,
				plaintextKey,
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
				Set.of(CacheScope.TENANT)
		);
	}

	/**
	 * Backwards-compatible constructor omitting resource/prompt visibility sets.
	 */
	public BootstrapKey(
			String ownerId,
			String name,
			String plaintextKey,
			int rpmLimit,
			int tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders,
			Set<String> allowedTools,
			Set<String> deniedTools
	) {
		this(
				ownerId,
				name,
				plaintextKey,
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
				Set.of(CacheScope.TENANT)
		);
	}
}
