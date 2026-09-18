package io.github.kxng0109.cacherelay.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;

/**
 * Invite creation (master key or admin session only).
 *
 * @param email invited address for delivery; {@code null} for link-only invites
 * @param admin whether redemption creates an admin account
 */
@Schema(name = "InviteRequest", description = "Create a single-use invite")
public record InviteRequest(
		@Schema(description = "Invited address (emailed when the mail channel is configured)", example = "op@example.com")
		@Email(message = "email must be valid")
		String email,

		@Schema(description = "Whether redemption creates an admin account", example = "false")
		boolean admin
) {
}
