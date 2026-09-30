package io.github.kxng0109.cacherelay.admin.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One org with its stable key and human name.
 *
 * @param id          org identifier, never {@code null}
 * @param slug        stable org key, never {@code null}
 * @param displayName human name, never {@code null}
 */
@Schema(name = "OrgResponse", description = "One organization")
public record OrgResponse(
		@Schema(description = "Org identifier")
		UUID id,

		@Schema(description = "Stable org key", example = "acme")
		String slug,

		@Schema(description = "Human name", example = "Acme Corp")
		String displayName
) {
}
