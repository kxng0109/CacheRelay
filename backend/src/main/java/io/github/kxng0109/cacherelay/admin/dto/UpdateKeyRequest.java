package io.github.kxng0109.cacherelay.admin.dto;

import io.github.kxng0109.cacherelay.cache.contracts.CacheScope;
import io.github.kxng0109.cacherelay.contracts.PolicyBounds;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * Optional fields for updating a virtual API key.
 *
 * @param name             new label (or null to preserve)
 * @param rpmLimit         new RPM limit (or null to preserve)
 * @param tpmLimit         new TPM limit (or null to preserve)
 * @param allowedModels    new allowed models (or null to preserve)
 * @param allowedProviders new allowed providers (or null to preserve)
 * @param allowedTools     new allowed tools (or null to preserve)
 * @param deniedTools      new denied tools (or null to preserve)
 * @param allowedResources new allowed resource URI globs (or null to preserve)
 * @param deniedResources  new denied resource URI globs (or null to preserve)
 * @param allowedPrompts   new allowed prompt globs (or null to preserve)
 * @param deniedPrompts    new denied prompt globs (or null to preserve)
 * @param allowedCacheScopes new cache isolation scopes (or null to preserve)
 * @param enabled          new enabled state (or null to preserve)
 * @param allowedAgents    new allowed A2A agents (or null to preserve)
 * @param deniedAgents     new denied A2A agents (or null to preserve)
 */
@Schema(name = "UpdateKeyRequest", description = "Patch payload for modifying virtual key quotas, allowlists, or enabled status")
public record UpdateKeyRequest(
		@Schema(description = "New label for the key (optional)", example = "updated-production-key")
		String name,

		@Schema(description = "New RPM quota (optional, 0 = unlimited)", example = "240")
		@PositiveOrZero(message = "rpmLimit must be non-negative")
		Integer rpmLimit,

		@Schema(description = "New TPM quota (optional, 0 = unlimited)", example = "1000000")
		@PositiveOrZero(message = "tpmLimit must be non-negative")
		Integer tpmLimit,

		@Schema(description = "New allowed model aliases set (optional)", example = "[\"gpt-56-luna\", \"claude-sonnet-4-5\"]")
		@Size(max = PolicyBounds.MAX_PATTERNS, message = "at most 64 entries per policy set")
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH, message = "pattern too long (max 256)")
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN, message = "pattern must not be blank") String> allowedModels,

		@Schema(description = "New allowed providers set (optional)", example = "[\"openai\", \"anthropic\"]")
		@Size(max = PolicyBounds.MAX_PATTERNS, message = "at most 64 entries per policy set")
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH, message = "pattern too long (max 256)")
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN, message = "pattern must not be blank") String> allowedProviders,

		@Schema(description = "New allowed MCP tools set (optional)", example = "[\"postgres__*\"]")
		@Size(max = PolicyBounds.MAX_PATTERNS, message = "at most 64 entries per policy set")
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH, message = "pattern too long (max 256)")
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN, message = "pattern must not be blank") String> allowedTools,

		@Schema(description = "New denied MCP tools set (optional)", example = "[\"*:delete_*\"]")
		@Size(max = PolicyBounds.MAX_PATTERNS, message = "at most 64 entries per policy set")
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH, message = "pattern too long (max 256)")
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN, message = "pattern must not be blank") String> deniedTools,

		@Schema(description = "New allowed resource URI globs (optional)", example = "[\"postgres://*\"]")
		@Size(max = PolicyBounds.MAX_PATTERNS, message = "at most 64 entries per policy set")
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH, message = "pattern too long (max 256)")
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN, message = "pattern must not be blank") String> allowedResources,

		@Schema(description = "New denied resource URI globs (optional)", example = "[\"postgres://secret/*\"]")
		@Size(max = PolicyBounds.MAX_PATTERNS, message = "at most 64 entries per policy set")
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH, message = "pattern too long (max 256)")
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN, message = "pattern must not be blank") String> deniedResources,

		@Schema(description = "New allowed prompt globs, matched against namespaced names (optional)", example = "[\"server__review_*\"]")
		@Size(max = PolicyBounds.MAX_PATTERNS, message = "at most 64 entries per policy set")
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH, message = "pattern too long (max 256)")
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN, message = "pattern must not be blank") String> allowedPrompts,

		@Schema(description = "New denied prompt globs, matched against namespaced names (optional)", example = "[\"server__admin_*\"]")
		@Size(max = PolicyBounds.MAX_PATTERNS, message = "at most 64 entries per policy set")
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH, message = "pattern too long (max 256)")
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN, message = "pattern must not be blank") String> deniedPrompts,

		@Schema(description = "Block tool delivery on injection markers (optional, null = keep)", example = "false")
		Boolean injectionBlock,

		@Schema(description = "New cache isolation scopes (optional, null = keep)", example = "[\"TENANT\"]")
		Set<CacheScope> allowedCacheScopes,

		@Schema(description = "Enable or disable key (optional)", example = "true")
		Boolean enabled,

		@Schema(description = "New allowed A2A agents set (optional, null = keep)", example = "[\"research-*\"]")
		@Size(max = PolicyBounds.MAX_PATTERNS, message = "at most 64 entries per policy set")
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH, message = "pattern too long (max 256)")
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN, message = "pattern must not be blank") String> allowedAgents,

		@Schema(description = "New denied A2A agents set (optional, null = keep)", example = "[\"prod-*\"]")
		@Size(max = PolicyBounds.MAX_PATTERNS, message = "at most 64 entries per policy set")
		Set<@Size(max = PolicyBounds.MAX_PATTERN_LENGTH, message = "pattern too long (max 256)")
				@Pattern(regexp = PolicyBounds.NON_BLANK_PATTERN, message = "pattern must not be blank") String> deniedAgents
) {
	/**
	 * Backwards-compatible constructor omitting the A2A agent policy sets (null = keep).
	 */
	public UpdateKeyRequest(
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
			Set<CacheScope> allowedCacheScopes,
			Boolean enabled
	) {
		this(name, rpmLimit, tpmLimit, allowedModels, allowedProviders, allowedTools,
				deniedTools, allowedResources, deniedResources, allowedPrompts, deniedPrompts,
				injectionBlock, allowedCacheScopes, enabled, null, null);
	}

	public UpdateKeyRequest(
			String name,
			Integer rpmLimit,
			Integer tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders,
			Boolean enabled
	) {
		this(name, rpmLimit, tpmLimit, allowedModels, allowedProviders, null, null, null, null,
				null, null, null, null, enabled, null, null);
	}

	/**
	 * Backwards-compatible constructor omitting resource/prompt visibility sets.
	 */
	public UpdateKeyRequest(
			String name,
			Integer rpmLimit,
			Integer tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders,
			Set<String> allowedTools,
			Set<String> deniedTools,
			Boolean enabled
	) {
		this(name, rpmLimit, tpmLimit, allowedModels, allowedProviders, allowedTools,
				deniedTools, null, null, null, null, null, null, enabled, null, null);
	}

	/**
	 * Backwards-compatible constructor omitting the cache-scope allowlist (null = keep).
	 */
	public UpdateKeyRequest(
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
			Boolean enabled
	) {
		this(name, rpmLimit, tpmLimit, allowedModels, allowedProviders, allowedTools,
				deniedTools, allowedResources, deniedResources, allowedPrompts, deniedPrompts,
				injectionBlock, null, enabled, null, null);
	}

	/**
	 * Backwards-compatible constructor omitting the injection handling flag.
	 */
	public UpdateKeyRequest(
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
			Boolean enabled
	) {
		this(name, rpmLimit, tpmLimit, allowedModels, allowedProviders, allowedTools,
				deniedTools, allowedResources, deniedResources, allowedPrompts, deniedPrompts,
				null, null, enabled, null, null);
	}
}
