package io.github.kxng0109.cacherelay.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Safe public representation of a user account: identity and status only,
 * never password or email hashes.
 *
 * @param userId    account id
 * @param username  login name
 * @param admin     whether the account holds admin rights
 * @param disabled  whether access is currently suspended
 * @param createdAt account creation timestamp
 */
@Schema(name = "UserSummaryResponse", description = "Safe public metadata representation of a user account")
public record UserSummaryResponse(
		@Schema(description = "Account UUID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
		UUID userId,

		@Schema(description = "Login name", example = "alice")
		String username,

		@Schema(description = "Whether the account holds admin rights", example = "false")
		boolean admin,

		@Schema(description = "Whether access is currently suspended", example = "false")
		boolean disabled,

		@Schema(description = "Account creation timestamp", example = "2026-09-01T00:00:00Z")
		Instant createdAt
) {
}
