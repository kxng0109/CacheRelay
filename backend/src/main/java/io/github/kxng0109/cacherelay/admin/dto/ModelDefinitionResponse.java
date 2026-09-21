package io.github.kxng0109.cacherelay.admin.dto;

import java.util.List;

import io.github.kxng0109.cacherelay.contracts.FailoverStrategy;
import io.github.kxng0109.cacherelay.contracts.ProviderRef;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One effective model alias with its origin.
 *
 * @param name     client facing model name
 * @param chain    ordered provider steps
 * @param strategy failover strategy
 * @param source   {@code "file"} for configuration-bound aliases (read-only)
 *                 or {@code "database"} for admin-managed aliases
 */
@Schema(name = "ModelDefinition", description = "Effective model alias with its origin")
public record ModelDefinitionResponse(
		@Schema(description = "Client facing model name", example = "fast-gpt")
		String name,

		@Schema(description = "Ordered provider steps")
		List<ProviderRef> chain,

		@Schema(description = "Failover strategy", example = "SEQUENTIAL")
		FailoverStrategy strategy,

		@Schema(description = "Alias origin: file or database", example = "database",
				allowableValues = {"file", "database"})
		String source
) {
}
