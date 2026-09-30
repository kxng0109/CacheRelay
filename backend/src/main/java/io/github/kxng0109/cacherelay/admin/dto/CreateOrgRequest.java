package io.github.kxng0109.cacherelay.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for creating an org. The slug is trimmed and lowercased server-side,
 * then restricted to lowercase alphanumerics and dashes.
 *
 * @param slug        desired org key, 1..64 chars
 * @param displayName human name, 1..128 chars
 */
@Schema(name = "CreateOrgRequest", description = "Payload for creating an org")
public record CreateOrgRequest(
		@Schema(description = "Desired org key", example = "acme")
		@NotBlank(message = "slug must be present") @Size(max = 64, message = "slug too long") String slug,

		@Schema(description = "Human name", example = "Acme Corp")
		@NotBlank(message = "displayName must be present") @Size(max = 128, message = "displayName too long") String displayName
) {
}
