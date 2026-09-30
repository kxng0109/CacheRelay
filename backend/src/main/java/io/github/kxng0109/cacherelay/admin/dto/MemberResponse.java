package io.github.kxng0109.cacherelay.admin.dto;

import java.util.UUID;

import io.github.kxng0109.cacherelay.auth.MembershipStatus;
import io.github.kxng0109.cacherelay.auth.TeamRole;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One account's membership in one team.
 *
 * @param userId account identifier, never {@code null}
 * @param teamId team identifier, never {@code null}
 * @param role   team-scoped role, never {@code null}
 * @param status lifecycle state, never {@code null}
 */
@Schema(name = "MemberResponse", description = "One account membership in one team")
public record MemberResponse(
		@Schema(description = "Account identifier")
		UUID userId,

		@Schema(description = "Team identifier")
		UUID teamId,

		@Schema(description = "Team-scoped role", example = "MEMBER")
		TeamRole role,

		@Schema(description = "Lifecycle state", example = "ACTIVE")
		MembershipStatus status
) {
}
