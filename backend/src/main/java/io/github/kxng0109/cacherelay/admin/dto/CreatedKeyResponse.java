package io.github.kxng0109.cacherelay.admin.dto;

import io.github.kxng0109.cacherelay.cache.contracts.CacheScope;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Set;

/**
 * Single-exposure response containing the generated plaintext key along with its public metadata.
 *
 * @param keyId            hex digest identifier of the key
 * @param key              plaintext API key (returned strictly once on creation)
 * @param keyPrefix        visible key prefix (e.g. gw-...)
 * @param ownerId          owner identifier
 * @param name             label for the key
 * @param rpmLimit         requests per minute limit
 * @param tpmLimit         tokens per minute limit
 * @param allowedModels    allowed models
 * @param allowedProviders allowed providers
 * @param allowedTools     allowed tools
 * @param deniedTools      denied tools
 * @param enabled          whether the key is active
 * @param createdAt        creation timestamp
 */
@Schema(name = "CreatedKeyResponse", description = "Single-exposure response containing plaintext virtual key and registered metadata")
public record CreatedKeyResponse(
		@Schema(description = "64-character SHA-256 hex digest of the key", example = "a1b2c3d4e5f60718293a4b5c6d7e8f901a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d")
		String keyId,

		@Schema(description = "Plaintext virtual API key (returned only once)", example = "gw-aB3_x9...32chars")
		String key,

		@Schema(description = "Visible key prefix", example = "gw-")
		String keyPrefix,

		@Schema(description = "Owner tenant identifier", example = "tenant-corp")
		String ownerId,

		@Schema(description = "Key label", example = "production-key")
		String name,

		@Schema(description = "Requests per minute limit", example = "120")
		int rpmLimit,

		@Schema(description = "Tokens per minute limit", example = "500000")
		int tpmLimit,

		@Schema(description = "Allowed model aliases", example = "[\"gpt-56-luna\"]")
		Set<String> allowedModels,

		@Schema(description = "Allowed upstream providers", example = "[\"openai\"]")
		Set<String> allowedProviders,

		@Schema(description = "Allowed MCP tools", example = "[\"postgres__*\"]")
		Set<String> allowedTools,

		@Schema(description = "Denied MCP tools", example = "[\"*:delete_*\"]")
		Set<String> deniedTools,

		@Schema(description = "Visible resource URI globs", example = "[\"postgres://*\"]")
		Set<String> allowedResources,

		@Schema(description = "Hidden resource URI globs", example = "[\"postgres://secret/*\"]")
		Set<String> deniedResources,

		@Schema(description = "Visible prompt globs", example = "[\"review_*\"]")
		Set<String> allowedPrompts,

		@Schema(description = "Hidden prompt globs", example = "[\"admin_*\"]")
		Set<String> deniedPrompts,

		@Schema(description = "Whether indirect prompt injection blocks tool delivery", example = "true")
		boolean injectionBlock,

		@Schema(description = "Whether key is enabled", example = "true")
		boolean enabled,

		@Schema(description = "Creation timestamp (ISO-8601)", example = "2026-09-01T12:00:00Z")
		Instant createdAt,

		@Schema(description = "Cache isolation scopes (TENANT-only when unset)", example = "[\"TENANT\"]")
		Set<CacheScope> allowedCacheScopes
) {
	public CreatedKeyResponse(
			String keyId,
			String key,
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
				keyId,
				key,
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
				Set.of(CacheScope.TENANT)
		);
	}

	/**
	 * Backwards-compatible constructor omitting resource/prompt visibility sets.
	 */
	public CreatedKeyResponse(
			String keyId,
			String key,
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
				keyId,
				key,
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
				Set.of(CacheScope.TENANT)
		);
	}

	/**
	 * Backwards-compatible constructor omitting the injection handling flag.
	 */
	public CreatedKeyResponse(
			String keyId,
			String key,
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
			boolean enabled,
			Instant createdAt
	) {
		this(
				keyId,
				key,
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
				true,
				enabled,
				createdAt,
				Set.of(CacheScope.TENANT)
		);
	}
}
