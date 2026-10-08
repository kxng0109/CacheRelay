package io.github.kxng0109.cacherelay.admin.dto;

import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Single mapper from {@link VirtualApiKey} to {@link KeyResponse} plus the shared 64-hex hash validator.
 *
 * <p>Both the admin and self-service controllers previously built the 22-argument response by hand; a field added to
 * the record had to be threaded through both sites. Hash-rejection messages stay caller-supplied so each surface keeps
 * its frozen literal.</p>
 */
public final class KeyMapper {

	private KeyMapper() {
	}

	/**
	 * Maps a key to its response shape.
	 *
	 * @param key stored key, never {@code null}
	 * @param ownerUsername resolved username, possibly {@code null} when unknown
	 * @return response DTO
	 */
	public static KeyResponse toKeyResponse(VirtualApiKey key, @Nullable String ownerUsername) {
		return new KeyResponse(
				key.keyHash() != null ? key.keyHash().hex() : "",
				key.keyPrefix(),
				key.ownerId(),
				key.name(),
				key.rpmLimit(),
				key.tpmLimit(),
				key.allowedModels(),
				key.allowedProviders(),
				key.allowedTools(),
				key.deniedTools(),
				key.allowedResources(),
				key.deniedResources(),
				key.allowedPrompts(),
				key.deniedPrompts(),
				key.injectionBlock(),
				key.enabled(),
				key.createdAt(),
				key.allowedCacheScopes(),
				key.allowedAgents(),
				key.deniedAgents(),
				key.ownerUserId(),
				ownerUsername);
	}

	/**
	 * Validates a 64-character hex key hash without reflecting the input.
	 *
	 * @param hex candidate hash, possibly {@code null}
	 * @param message rejection message for the calling surface, never {@code null}
	 * @return parsed hash
	 * @throws ResponseStatusException HTTP 400 with the caller-supplied message when malformed
	 */
	public static SHA256Hash parseHash(String hex, String message) {
		if (hex == null || hex.length() != 64 || !hex.matches("^[a-fA-F0-9]{64}$")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
		}
		return SHA256Hash.fromHex(hex);
	}
}
