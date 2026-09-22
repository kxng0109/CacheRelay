package io.github.kxng0109.cacherelay.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One configured upstream provider with its live routing health.
 *
 * @param name                     provider identifier used in alias chains
 * @param type                     API dialect ({@code OPENAI}, {@code ANTHROPIC}, {@code OLLAMA},
 *                                 and so on)
 * @param baseUrl                  configured upstream base URL, or {@code null} when unset
 * @param keyConfigured            whether a non-blank API key is configured; the key value is
 *                                 never exposed
 * @param connectTimeoutSeconds    connect timeout in seconds
 * @param requestTimeoutSeconds    first-byte (per-attempt) request timeout in seconds
 * @param embeddingSingleAsString  whether single-text embedding batches serialize as a bare
 *                                 string for this provider
 * @param circuitState             live circuit breaker state ({@code CLOSED}, {@code OPEN},
 *                                 {@code HALF_OPEN}) as observed by this instance
 * @param aliasReferences          how many effective alias chain steps reference this provider
 * @param validationStatus         how far the integration has been validated
 *                                 ({@code CONTRACT_CHECKED}, {@code AUTH_REACHABLE},
 *                                 {@code LIVE_VERIFIED}, {@code UNVERIFIED}); see
 *                                 {@link ProviderValidationStatus}
 */
@Schema(name = "ProviderStatus", description = "Configured upstream provider with live routing health")
public record ProviderStatusResponse(
		@Schema(description = "Provider identifier used in alias chains", example = "openai")
		String name,

		@Schema(description = "API dialect spoken by the provider", example = "OPENAI")
		String type,

		@Schema(description = "Configured upstream base URL", example = "https://api.openai.com")
		String baseUrl,

		@Schema(description = "Whether a non-blank API key is configured; the key value is never exposed",
				example = "true")
		boolean keyConfigured,

		@Schema(description = "Connect timeout in seconds", example = "5")
		long connectTimeoutSeconds,

		@Schema(description = "First-byte request timeout in seconds", example = "60")
		long requestTimeoutSeconds,

		@Schema(description = "Whether single-text embedding batches serialize as a bare string",
				example = "false")
		boolean embeddingSingleAsString,

		@Schema(description = "Live circuit breaker state observed by this instance",
				example = "CLOSED", allowableValues = {"CLOSED", "OPEN", "HALF_OPEN"})
		String circuitState,

		@Schema(description = "How many effective alias chain steps reference this provider",
				example = "2")
		int aliasReferences,

		@Schema(description = "How far the integration has been validated",
				example = "AUTH_REACHABLE",
				allowableValues = {"CONTRACT_CHECKED", "AUTH_REACHABLE", "LIVE_VERIFIED", "UNVERIFIED"})
		String validationStatus
) {
}
