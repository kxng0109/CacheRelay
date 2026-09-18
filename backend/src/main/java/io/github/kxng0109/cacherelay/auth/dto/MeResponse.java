package io.github.kxng0109.cacherelay.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Current session identity.
 *
 * @param userId   account identifier
 * @param username login name
 * @param admin    whether the session holds admin privilege
 */
@Schema(name = "MeResponse", description = "Current session identity")
public record MeResponse(
		@Schema(description = "Account identifier")
		String userId,

		@Schema(description = "Login name", example = "operator")
		String username,

		@Schema(description = "Whether the session is administrative", example = "false")
		boolean admin
) {
}
