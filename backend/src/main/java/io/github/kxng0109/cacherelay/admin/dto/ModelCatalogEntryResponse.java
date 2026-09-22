package io.github.kxng0109.cacherelay.admin.dto;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One model suggestion from the pricing catalog, used to populate the model picker
 * when an admin builds an alias chain step.
 *
 * @param modelId                     exact upstream model id to use as a chain model override
 * @param provider                    catalog (LiteLLM) provider name, for example {@code openai}
 *                                    or {@code together_ai}
 * @param mode                        catalog mode (usually {@code chat})
 * @param inputCostPerToken           USD per input token
 * @param outputCostPerToken          USD per output token
 * @param cacheReadInputTokenCost     USD per cache read token, may be {@code null}
 * @param cacheCreationInputTokenCost USD per cache write token, may be {@code null}
 * @param maxInputTokens              context window, may be {@code null} when unknown
 * @param maxOutputTokens             completion bound, may be {@code null} when unknown
 * @param qualityTier                 curated quality tier name, may be {@code null} when unrated
 * @param benchmarkRefs               benchmark references backing the tier, may be {@code null}
 */
@Schema(name = "ModelCatalogEntry", description = "One model suggestion from the pricing catalog")
public record ModelCatalogEntryResponse(
		@Schema(description = "Exact upstream model id usable as a chain model override",
				example = "gpt-5.6-luna")
		String modelId,

		@Schema(description = "Catalog provider name", example = "openai")
		String provider,

		@Schema(description = "Catalog mode", example = "chat")
		String mode,

		@Schema(description = "USD per input token", example = "0.0000025")
		BigDecimal inputCostPerToken,

		@Schema(description = "USD per output token", example = "0.00001")
		BigDecimal outputCostPerToken,

		@Schema(description = "USD per cache read token, null when the provider has no cache reads")
		@Nullable BigDecimal cacheReadInputTokenCost,

		@Schema(description = "USD per cache write token, null when the provider has no cache writes")
		@Nullable BigDecimal cacheCreationInputTokenCost,

		@Schema(description = "Context window in tokens, null when unknown", example = "128000")
		@Nullable Long maxInputTokens,

		@Schema(description = "Completion bound in tokens, null when unknown", example = "16384")
		@Nullable Long maxOutputTokens,

		@Schema(description = "Curated quality tier name, null when the model is unrated",
				example = "FRONTIER")
		@Nullable String qualityTier,

		@Schema(description = "Benchmark references backing the tier, null when unrecorded")
		@Nullable String benchmarkRefs
) {
}
