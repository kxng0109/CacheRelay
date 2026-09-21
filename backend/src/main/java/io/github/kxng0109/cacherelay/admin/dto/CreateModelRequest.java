package io.github.kxng0109.cacherelay.admin.dto;

import java.util.List;

import io.github.kxng0109.cacherelay.model.ModelAliasRegistry;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request payload for creating a database-managed model alias.
 *
 * @param name     client facing model name (lowercase slug)
 * @param chain    ordered provider steps (at least one)
 * @param strategy failover strategy (SEQUENTIAL or RACE, case-insensitive)
 */
@Schema(name = "CreateModelRequest", description = "Payload for creating a database-managed model alias")
public record CreateModelRequest(
		@Schema(description = "Client facing model name", example = "fast-gpt",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "name must not be blank")
		@Pattern(regexp = ModelAliasRegistry.NAME_PATTERN, message = "invalid model name")
		String name,

		@Schema(description = "Ordered provider steps to try", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "chain must not be null")
		@Size(min = 1, max = ModelAliasRegistry.MAX_CHAIN_STEPS, message = "chain must contain 1 to 8 steps")
		List<@Valid ProviderStepRequest> chain,

		@Schema(description = "Failover strategy", example = "SEQUENTIAL",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "strategy must not be blank")
		String strategy
) {
}
