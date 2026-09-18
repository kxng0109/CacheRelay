package io.github.kxng0109.cacherelay.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Local login credentials.
 *
 * @param username login name (any case)
 * @param password presented password
 */
@Schema(name = "LoginRequest", description = "Local username+password login")
public record LoginRequest(
		@Schema(description = "Login name", example = "operator", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "username must not be blank")
		String username,

		@Schema(description = "Password", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "password must not be blank")
		String password
) {
}
