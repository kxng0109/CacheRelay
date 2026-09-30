package io.github.kxng0109.cacherelay.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for renaming an org or a team. Slugs and group bindings are immutable.
 *
 * @param displayName human name, 1..128 chars
 */
@Schema(name = "RenameRequest", description = "Payload for renaming an org or a team")
public record RenameRequest(
		@Schema(description = "Human name", example = "Eng")
		@NotBlank(message = "displayName must be present") @Size(max = 128, message = "displayName too long") String displayName
) {
}
