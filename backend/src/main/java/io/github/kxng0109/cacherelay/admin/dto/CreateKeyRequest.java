package io.github.kxng0109.cacherelay.admin.dto;

import io.github.kxng0109.cacherelay.cache.contracts.CacheScope;
import io.github.kxng0109.cacherelay.contracts.PolicyBounds;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.Set;
import java.util.UUID;

/**
 * Request payload for creating a virtual API key.
 *
 * @param ownerId          owner or tenant identifier (required)
 * @param name             label for the key (required)
 * @param rpmLimit         requests per minute limit (0 = unlimited)
 * @param tpmLimit         tokens per minute limit (0 = unlimited)
 * @param allowedModels    allowed model names (null or empty = all allowed)
 * @param allowedProviders allowed provider names (null or empty = all allowed)
 * @param allowedTools     allowed tool names or glob patterns (null or empty = all allowed)
 * @param deniedTools      denied tool names or glob patterns (null or empty = none)
 * @param allowedCacheScopes cache isolation scopes the key may use (null or empty = TENANT only)
 * @param allowedAgents    allowed A2A agent names or glob patterns (null or empty = all allowed)
 * @param deniedAgents     denied A2A agent names or glob patterns (null or empty = none denied)
 * @param ownerUserId      owning account id (required: keys are never born orphaned)
 */
@Schema(name = "CreateKeyRequest", description = "Payload for provisioning a new virtual API key with quotas, model, and tool access controls")
public record CreateKeyRequest(
		@Schema(description = "Owner or tenant identifier for billing attribution", example = "tenant-corp", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "ownerId must not be blank")
		String ownerId,

		@Schema(description = "Human-readable label for key identification", example = "production-engine-key", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "name must not be blank")
		String name,

		@Schema(description = "Requests per minute limit (0 = unlimited)", example = "120", minimum = "0")
		@PositiveOrZero(message = "rpmLimit must be non-negative")
		Integer rpmLimit,

		@Schema(description = "Tokens per minute limit (0 = unlimited)", example = "500000", minimum = "0")
		@PositiveOrZero(message = "tpmLimit must be non-negative")
		Integer tpmLimit,

		@Schema(description = "Set of permitted model alias identifiers (empty = all allowed)", example = "[\"gpt-56-luna\", \"claude-sonnet-4-5\"]")
		@Size(max = PolicyBounds.MAX_PATTERNS, message = "at most 64 entries per policy set")
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH, message = "pattern too long (max 256)")
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN, message = "pattern must not be blank") String> allowedModels,

		@Schema(description = "Set of permitted upstream provider identifiers (empty = all allowed)", example = "[\"openai\", \"anthropic\"]")
		@Size(max = PolicyBounds.MAX_PATTERNS, message = "at most 64 entries per policy set")
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH, message = "pattern too long (max 256)")
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN, message = "pattern must not be blank") String> allowedProviders,

		@Schema(description = "Set of permitted MCP tool identifiers or glob patterns (empty = all allowed)", example = "[\"postgres__*\", \"github__list_prs\"]")
		@Size(max = PolicyBounds.MAX_PATTERNS, message = "at most 64 entries per policy set")
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH, message = "pattern too long (max 256)")
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN, message = "pattern must not be blank") String> allowedTools,

		@Schema(description = "Set of denied MCP tool identifiers or glob patterns (empty = none denied)", example = "[\"*:delete_*\", \"*:drop_*\"]")
		@Size(max = PolicyBounds.MAX_PATTERNS, message = "at most 64 entries per policy set")
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH, message = "pattern too long (max 256)")
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN, message = "pattern must not be blank") String> deniedTools,

		@Schema(description = "Set of visible MCP resource URI globs (empty = all visible)", example = "[\"postgres://*\"]")
		@Size(max = PolicyBounds.MAX_PATTERNS, message = "at most 64 entries per policy set")
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH, message = "pattern too long (max 256)")
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN, message = "pattern must not be blank") String> allowedResources,

		@Schema(description = "Set of hidden MCP resource URI globs (empty = none hidden)", example = "[\"postgres://secret/*\"]")
		@Size(max = PolicyBounds.MAX_PATTERNS, message = "at most 64 entries per policy set")
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH, message = "pattern too long (max 256)")
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN, message = "pattern must not be blank") String> deniedResources,

		@Schema(description = "Set of visible MCP prompt name globs, matched against namespaced names (empty = all visible)", example = "[\"server__review_*\"]")
		@Size(max = PolicyBounds.MAX_PATTERNS, message = "at most 64 entries per policy set")
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH, message = "pattern too long (max 256)")
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN, message = "pattern must not be blank") String> allowedPrompts,

		@Schema(description = "Set of hidden MCP prompt name globs, matched against namespaced names (empty = none hidden)", example = "[\"server__admin_*\"]")
		@Size(max = PolicyBounds.MAX_PATTERNS, message = "at most 64 entries per policy set")
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH, message = "pattern too long (max 256)")
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN, message = "pattern must not be blank") String> deniedPrompts,

		@Schema(description = "Block tool delivery on indirect prompt injection markers (null = default block, false = warn only)", example = "true")
		Boolean injectionBlock,

		@Schema(description = "Cache isolation scopes the key may use (null or empty = TENANT only)", example = "[\"TENANT\"]")
		Set<CacheScope> allowedCacheScopes,

		@Schema(description = "Set of permitted A2A agent identifiers or glob patterns (empty = all allowed)", example = "[\"research-*\"]")
		@Size(max = PolicyBounds.MAX_PATTERNS, message = "at most 64 entries per policy set")
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH, message = "pattern too long (max 256)")
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN, message = "pattern must not be blank") String> allowedAgents,

		@Schema(description = "Set of denied A2A agent identifiers or glob patterns (empty = none denied)", example = "[\"prod-*\"]")
		@Size(max = PolicyBounds.MAX_PATTERNS, message = "at most 64 entries per policy set")
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH, message = "pattern too long (max 256)")
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN, message = "pattern must not be blank") String> deniedAgents,

		@Schema(description = "Owning account id (required)", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "ownerUserId must not be null")
		UUID ownerUserId
) {
	public CreateKeyRequest {
		rpmLimit = rpmLimit != null ? rpmLimit : 0;
		tpmLimit = tpmLimit != null ? tpmLimit : 0;
		allowedModels = allowedModels != null ? Set.copyOf(allowedModels) : Set.of();
		allowedProviders = allowedProviders != null ? Set.copyOf(allowedProviders) : Set.of();
		allowedTools = allowedTools != null ? Set.copyOf(allowedTools) : Set.of();
		deniedTools = deniedTools != null ? Set.copyOf(deniedTools) : Set.of();
		allowedResources = allowedResources != null ? Set.copyOf(allowedResources) : Set.of();
		deniedResources = deniedResources != null ? Set.copyOf(deniedResources) : Set.of();
		allowedPrompts = allowedPrompts != null ? Set.copyOf(allowedPrompts) : Set.of();
		deniedPrompts = deniedPrompts != null ? Set.copyOf(deniedPrompts) : Set.of();
		allowedCacheScopes = VirtualApiKey.normalizeCacheScopes(allowedCacheScopes);
		allowedAgents = allowedAgents != null ? Set.copyOf(allowedAgents) : Set.of();
		deniedAgents = deniedAgents != null ? Set.copyOf(deniedAgents) : Set.of();
	}

	/**
	 * Backwards-compatible constructor omitting the A2A agent policy sets.
	 */
	public CreateKeyRequest(
			String ownerId,
			String name,
			Integer rpmLimit,
			Integer tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders,
			Set<String> allowedTools,
			Set<String> deniedTools,
			Set<String> allowedResources,
			Set<String> deniedResources,
			Set<String> allowedPrompts,
			Set<String> deniedPrompts,
			Boolean injectionBlock,
			Set<CacheScope> allowedCacheScopes
	) {
		this(ownerId, name, rpmLimit, tpmLimit, allowedModels, allowedProviders, allowedTools,
				deniedTools, allowedResources, deniedResources, allowedPrompts, deniedPrompts,
				injectionBlock, allowedCacheScopes, Set.of(), Set.of(), null);
	}

	public CreateKeyRequest(
			String ownerId,
			String name,
			Integer rpmLimit,
			Integer tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders
	) {
		this(ownerId, name, rpmLimit, tpmLimit, allowedModels, allowedProviders, Set.of(), Set.of(),
				Set.of(), Set.of(), Set.of(), Set.of(), null, Set.of(CacheScope.TENANT), Set.of(), Set.of(), null);
	}

	/**
	 * Backwards-compatible constructor omitting resource/prompt visibility sets.
	 */
	public CreateKeyRequest(
			String ownerId,
			String name,
			Integer rpmLimit,
			Integer tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders,
			Set<String> allowedTools,
			Set<String> deniedTools
	) {
		this(ownerId, name, rpmLimit, tpmLimit, allowedModels, allowedProviders, allowedTools,
				deniedTools, Set.of(), Set.of(), Set.of(), Set.of(), null, Set.of(CacheScope.TENANT), Set.of(), Set.of(), null);
	}

	/**
	 * Backwards-compatible constructor omitting the cache-scope allowlist (defaults to TENANT-only).
	 */
	public CreateKeyRequest(
			String ownerId,
			String name,
			Integer rpmLimit,
			Integer tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders,
			Set<String> allowedTools,
			Set<String> deniedTools,
			Set<String> allowedResources,
			Set<String> deniedResources,
			Set<String> allowedPrompts,
			Set<String> deniedPrompts,
			Boolean injectionBlock
	) {
		this(ownerId, name, rpmLimit, tpmLimit, allowedModels, allowedProviders, allowedTools,
				deniedTools, allowedResources, deniedResources, allowedPrompts, deniedPrompts,
				injectionBlock, Set.of(CacheScope.TENANT), Set.of(), Set.of(), null);
	}

	/**
	 * Backwards-compatible constructor omitting the injection handling flag.
	 */
	public CreateKeyRequest(
			String ownerId,
			String name,
			Integer rpmLimit,
			Integer tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders,
			Set<String> allowedTools,
			Set<String> deniedTools,
			Set<String> allowedResources,
			Set<String> deniedResources,
			Set<String> allowedPrompts,
			Set<String> deniedPrompts
	) {
		this(ownerId, name, rpmLimit, tpmLimit, allowedModels, allowedProviders, allowedTools,
				deniedTools, allowedResources, deniedResources, allowedPrompts, deniedPrompts,
				null, Set.of(CacheScope.TENANT), Set.of(), Set.of(), null);
	}
}
