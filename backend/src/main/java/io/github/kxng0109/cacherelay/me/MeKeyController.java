package io.github.kxng0109.cacherelay.me;

import java.util.List;
import java.util.UUID;

import io.github.kxng0109.cacherelay.admin.dto.KeyResponse;
import io.github.kxng0109.cacherelay.auth.JwtService;
import io.github.kxng0109.cacherelay.auth.UserAccount;
import io.github.kxng0109.cacherelay.auth.UserAccountRepository;
import io.github.kxng0109.cacherelay.config.OpenApiConfig;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.me.dto.DefaultKeyRequest;
import io.github.kxng0109.cacherelay.security.filter.KeyAuthFilter;
import io.github.kxng0109.cacherelay.security.ratelimit.KeyManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Self-service key surface under {@code /v1/me/keys} for session-authenticated
 * accounts. Every operation is owner-scoped: foreign keys read exactly like
 * absent keys, and responses carry metadata only — plaintext secrets never
 * cross this boundary.
 */
@RestController
@RequestMapping("/v1/me/keys")
@RequiredArgsConstructor
@Tag(name = "Self-service keys", description = "Session-authenticated key metadata, defaults, and self-revocation")
public class MeKeyController {

	private final KeyManagementService keys;

	private final JwtService sessions;

	private final UserAccountRepository users;

	/**
	 * Lists the caller's keys as metadata (never secrets).
	 *
	 * @param authorization session bearer token
	 * @return HTTP 200 OK with owned key metadata
	 */
	@Operation(
			summary = "List my keys",
			description = "Lists metadata of keys owned by the session account.",
			security = @SecurityRequirement(name = OpenApiConfig.SCHEME_BEARER_AUTH)
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Owned key metadata",
					content = @Content(mediaType = "application/json",
							array = @ArraySchema(schema = @Schema(implementation = KeyResponse.class)))),
			@ApiResponse(responseCode = "401", description = "Invalid session")
	})
	@GetMapping
	public ResponseEntity<List<KeyResponse>> listMyKeys(
			@Parameter(description = "Session bearer token", hidden = true)
			@RequestHeader("Authorization") String authorization) {
		UUID userId = requireSelf(authorization);
		List<KeyResponse> response = keys.listKeysByUser(userId).stream()
				.map(key -> toKeyResponse(key, userId))
				.toList();
		return ResponseEntity.ok(response);
	}

	/**
	 * Selects the caller's default key for act-as-self flows.
	 *
	 * @param authorization session bearer token
	 * @param request       owned key hash hex
	 * @return HTTP 204 on success, 404 for unknown or foreign keys
	 */
	@Operation(
			summary = "Set my default key",
			description = "Selects which owned key act-as-self flows use when none is named.",
			security = @SecurityRequirement(name = OpenApiConfig.SCHEME_BEARER_AUTH)
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "204", description = "Default selected", content = @Content),
			@ApiResponse(responseCode = "400", description = "Malformed key hash"),
			@ApiResponse(responseCode = "404", description = "Key not found"),
			@ApiResponse(responseCode = "401", description = "Invalid session")
	})
	@PutMapping("/default")
	public ResponseEntity<Void> setMyDefault(
			@Parameter(description = "Session bearer token", hidden = true)
			@RequestHeader("Authorization") String authorization,
			@RequestBody DefaultKeyRequest request) {
		UUID userId = requireSelf(authorization);
		SHA256Hash hash = parseHash(request.keyId());
		VirtualApiKey key = keys.findByHash(hash).orElse(null);
		if (key == null || !userId.equals(key.ownerUserId())) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "key not found");
		}
		keys.setDefaultKey(userId, hash);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Terminally revokes one of the caller's own keys.
	 *
	 * @param authorization session bearer token
	 * @param hashHex       key hash hex
	 * @return HTTP 204 on success, 404 for unknown or foreign keys
	 */
	@Operation(
			summary = "Revoke my key terminally",
			description = "Sets the irreversible revocation tombstone on an owned key. There is no inverse.",
			security = @SecurityRequirement(name = OpenApiConfig.SCHEME_BEARER_AUTH)
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "204", description = "Key tombstoned", content = @Content),
			@ApiResponse(responseCode = "400", description = "Malformed key hash"),
			@ApiResponse(responseCode = "404", description = "Key not found"),
			@ApiResponse(responseCode = "401", description = "Invalid session")
	})
	@PostMapping("/{hashHex}/revoke")
	public ResponseEntity<Void> revokeMyKey(
			@Parameter(description = "Session bearer token", hidden = true)
			@RequestHeader("Authorization") String authorization,
			@Parameter(description = "64-character SHA-256 hex digest of an owned key")
			@PathVariable("hashHex") String hashHex) {
		UUID userId = requireSelf(authorization);
		SHA256Hash hash = parseHash(hashHex);
		VirtualApiKey key = keys.findByHash(hash).orElse(null);
		if (key == null || !userId.equals(key.ownerUserId())) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "key not found");
		}
		keys.revokeKey(hash);
		return ResponseEntity.noContent().build();
	}

	private UUID requireSelf(String authorization) {
		String token = authorization == null ? "" : authorization.trim();
		if (token.startsWith(KeyAuthFilter.AUTH_SCHEME)) {
			token = token.substring(KeyAuthFilter.AUTH_SCHEME.length()).trim();
		}
		final Jwt decoded;
		try {
			decoded = sessions.validate(token);
		} catch (RuntimeException invalid) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid session");
		}
		if (decoded == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid session");
		}
		final UUID userId;
		try {
			userId = UUID.fromString(decoded.getSubject());
		} catch (RuntimeException malformed) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid session");
		}
		boolean active = users.findById(userId).map(account -> !account.isDisabled()).orElse(false);
		if (!active) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid session");
		}
		return userId;
	}

	private SHA256Hash parseHash(String hex) {
		if (hex == null || hex.length() != 64 || !hex.matches("^[a-fA-F0-9]{64}$")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "malformed key hash");
		}
		return SHA256Hash.fromHex(hex);
	}

	private KeyResponse toKeyResponse(VirtualApiKey key, UUID userId) {
		String username = users.findById(userId).map(UserAccount::getUsername).orElse(null);
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
				username);
	}
}
