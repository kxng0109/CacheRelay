package io.github.kxng0109.cacherelay.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Issued access token (refresh travels in the {@code httpOnly} cookie, never here).
 *
 * @param accessToken signed short-lived JWT for {@code Authorization: Bearer} use
 * @param expiresInSeconds seconds until access expiry
 * @param admin whether the session holds admin privilege
 */
@Schema(name = "AccessTokenResponse", description = "Access token issued on login or refresh")
public record AccessTokenResponse(
		@Schema(description = "Compact JWT access token")
		String accessToken,

		@Schema(description = "Seconds until access-token expiry", example = "600")
		long expiresInSeconds,

		@Schema(description = "Whether the session is administrative", example = "false")
		boolean admin
) {
}
