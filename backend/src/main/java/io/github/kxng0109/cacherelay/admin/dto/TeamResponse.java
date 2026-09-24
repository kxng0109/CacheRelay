package io.github.kxng0109.cacherelay.admin.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One team in an org with its live active-member count.
 *
 * @param teamId        team identifier, never {@code null}
 * @param orgSlug       owning org slug, never {@code null}
 * @param name          team display name, never {@code null}
 * @param idpGroupId    stable IdP group id, never {@code null}
 * @param activeMembers active membership count
 */
@Schema(name = "TeamResponse", description = "One SSO-provisioned team")
public record TeamResponse(
		@Schema(description = "Team identifier")
		UUID teamId,

		@Schema(description = "Owning org slug", example = "acme")
		String orgSlug,

		@Schema(description = "Team display name", example = "Eng")
		String name,

		@Schema(description = "Stable IdP group id", example = "group-1")
		String idpGroupId,

		@Schema(description = "Active membership count", example = "12")
		long activeMembers
) {
}
