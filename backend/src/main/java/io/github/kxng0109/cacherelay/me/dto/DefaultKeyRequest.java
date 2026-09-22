package io.github.kxng0109.cacherelay.me.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Payload for selecting the caller's default key for act-as-self flows.
 *
 * @param keyId key hash hex of an owned key
 */
@Schema(name = "DefaultKeyRequest", description = "Payload for selecting the caller's default key")
public record DefaultKeyRequest(
		@Schema(description = "64-character SHA-256 hex digest of an owned key",
				example = "a1b2c3d4e5f60718293a4b5c6d7e8f901a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "keyId must not be blank")
		String keyId
) {
}
