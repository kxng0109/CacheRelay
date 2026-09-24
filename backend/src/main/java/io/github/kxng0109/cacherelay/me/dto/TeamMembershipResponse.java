package io.github.kxng0109.cacherelay.me.dto;

import java.util.UUID;

import io.github.kxng0109.cacherelay.auth.MembershipStatus;
import io.github.kxng0109.cacherelay.auth.TeamRole;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One of the caller's team memberships with the team and org identity the UI
 * needs for scoping.
 *
 * @param teamId   team identifier, never {@code null}
 * @param teamName team display name, never {@code null}
 * @param orgSlug  owning org slug, never {@code null}
 * @param role     team-scoped role, never {@code null}
 * @param status   lifecycle state, never {@code null}
 */
@Schema(name = "TeamMembershipResponse", description = "One team membership of the session account")
public record TeamMembershipResponse(
		@Schema(description = "Team identifier")
		UUID teamId,

		@Schema(description = "Team display name", example = "Eng")
		String teamName,

		@Schema(description = "Owning org slug", example = "acme")
		String orgSlug,

		@Schema(description = "Team-scoped role", example = "MEMBER")
		TeamRole role,

		@Schema(description = "Lifecycle state", example = "ACTIVE")
		MembershipStatus status
) {
}
