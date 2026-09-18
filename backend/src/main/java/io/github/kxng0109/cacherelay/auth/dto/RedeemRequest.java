package io.github.kxng0109.cacherelay.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Invite redemption into a new local account.
 *
 * @param token    opaque invite token from the link
 * @param username desired login name
 * @param password desired password (minimum length enforced)
 */
@Schema(name = "RedeemRequest", description = "Redeem a single-use invite into an account")
public record RedeemRequest(
		@Schema(description = "Opaque invite token", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "token must not be blank")
		String token,

		@Schema(description = "Desired login name", example = "operator", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "username must not be blank")
		@Size(min = 3, max = 255, message = "username must be 3-255 characters")
		String username,

		@Schema(description = "Desired password", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "password must not be blank")
		@Size(min = 12, max = 255, message = "password must be 12-255 characters")
		String password
) {
}
