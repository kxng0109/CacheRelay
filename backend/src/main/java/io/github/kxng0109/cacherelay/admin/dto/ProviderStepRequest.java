package io.github.kxng0109.cacherelay.admin.dto;

import io.github.kxng0109.cacherelay.contracts.PolicyBounds;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One provider step inside a model alias chain.
 *
 * @param providerName  upstream provider name (must be configured)
 * @param modelOverride model name sent to the provider, or {@code null} to
 *                      send the requested model as is
 */
@Schema(name = "ModelChainStep", description = "One provider step inside a model alias chain")
public record ProviderStepRequest(
		@Schema(description = "Configured upstream provider name", example = "openai",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "providerName must not be blank")
		String providerName,

		@Schema(description = "Model name sent to the provider (null sends the requested model as is)",
				example = "gpt-5.6-luna")
		@Size(max = PolicyBounds.MAX_PATTERN_LENGTH, message = "modelOverride too long (max 256)")
		String modelOverride
) {
}
