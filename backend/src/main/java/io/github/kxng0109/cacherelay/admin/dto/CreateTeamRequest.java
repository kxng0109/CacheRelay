package io.github.kxng0109.cacherelay.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload for creating a locally managed team inside an org.
 *
 * @param name display name, 1..128 chars
 */
@Schema(name = "CreateTeamRequest", description = "Payload for creating a locally managed team")
public record CreateTeamRequest(
		@Schema(description = "Display name", example = "Eng")
		@NotBlank(message = "name must be present") @Size(max = 128, message = "name too long") String name
) {
}
