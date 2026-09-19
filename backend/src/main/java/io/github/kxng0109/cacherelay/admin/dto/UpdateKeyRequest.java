package io.github.kxng0109.cacherelay.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.PositiveOrZero;

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
 * @param enabled          new enabled state (or null to preserve)
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
		Set<String> allowedModels,

		@Schema(description = "New allowed providers set (optional)", example = "[\"openai\", \"anthropic\"]")
		Set<String> allowedProviders,

		@Schema(description = "New allowed MCP tools set (optional)", example = "[\"postgres__*\"]")
		Set<String> allowedTools,

		@Schema(description = "New denied MCP tools set (optional)", example = "[\"*:delete_*\"]")
		Set<String> deniedTools,

		@Schema(description = "New allowed resource URI globs (optional)", example = "[\"postgres://*\"]")
		Set<String> allowedResources,

		@Schema(description = "New denied resource URI globs (optional)", example = "[\"postgres://secret/*\"]")
		Set<String> deniedResources,

		@Schema(description = "New allowed prompt globs (optional)", example = "[\"review_*\"]")
		Set<String> allowedPrompts,

		@Schema(description = "New denied prompt globs (optional)", example = "[\"admin_*\"]")
		Set<String> deniedPrompts,

		@Schema(description = "Block tool delivery on injection markers (optional, null = keep)", example = "false")
		Boolean injectionBlock,

		@Schema(description = "Enable or disable key (optional)", example = "true")
		Boolean enabled
) {
	public UpdateKeyRequest(
			String name,
			Integer rpmLimit,
			Integer tpmLimit,
			Set<String> allowedModels,
			Set<String> allowedProviders,
			Boolean enabled
	) {
		this(name, rpmLimit, tpmLimit, allowedModels, allowedProviders, null, null, null, null,
				null, null, null, enabled);
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
				deniedTools, null, null, null, null, null, enabled);
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
				null, enabled);
	}
}
