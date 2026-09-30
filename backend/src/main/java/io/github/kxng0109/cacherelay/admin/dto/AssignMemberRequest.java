package io.github.kxng0109.cacherelay.admin.dto;

import io.github.kxng0109.cacherelay.auth.TeamRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Payload for assigning an account to a team.
 *
 * @param role team-scoped role, must be explicit
 */
@Schema(name = "AssignMemberRequest", description = "Payload for assigning an account to a team")
public record AssignMemberRequest(
		@Schema(description = "Team-scoped role", example = "MEMBER")
		@NotNull(message = "role must be explicit") TeamRole role
) {
}
