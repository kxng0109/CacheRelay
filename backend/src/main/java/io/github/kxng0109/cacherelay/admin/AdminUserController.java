package io.github.kxng0109.cacherelay.admin;

import java.util.UUID;

import io.github.kxng0109.cacherelay.admin.dto.SetDisabledRequest;
import io.github.kxng0109.cacherelay.auth.UserAccount;
import io.github.kxng0109.cacherelay.auth.UserAccountRepository;
import io.github.kxng0109.cacherelay.config.OpenApiConfig;
import io.github.kxng0109.cacherelay.security.ratelimit.KeyManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST controller for account lifecycle under {@code /v1/admin/users}.
 * Disabling suspends access; deletion terminally revokes every attached key
 * first, so no key ever outlives its account in a usable state.
 */
@RestController
@RequestMapping("/v1/admin/users")
@RequiredArgsConstructor
@Tag(name = "Admin - Users", description = "Account lifecycle with terminal key cascade")
public class AdminUserController {

	private final UserAccountRepository users;

	private final KeyManagementService keys;

	/**
	 * Toggles an account's disabled state. Re-enabling never resurrects
	 * tombstoned keys.
	 *
	 * @param id      account id
	 * @param request desired disabled state
	 * @return HTTP 204 on success, 404 for unknown accounts
	 */
	@Operation(
			summary = "Set account disabled state",
			description = "Disables or re-enables an account. Key tombstones are terminal either way.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "204", description = "State updated"),
			@ApiResponse(responseCode = "400", description = "Malformed account id"),
			@ApiResponse(responseCode = "404", description = "Account not found"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect")
	})
	@PutMapping("/{id}/disabled")
	public ResponseEntity<Void> setDisabled(
			@Parameter(description = "Account UUID")
			@PathVariable("id") String id,
			@RequestBody SetDisabledRequest request) {
		UserAccount account = requireAccount(id);
		if (request.disabled()) {
			account.disable();
		} else {
			account.enable();
		}
		users.save(account);
		keys.invalidateOwnerCache(account.getId());
		return ResponseEntity.noContent().build();
	}

	/**
	 * Deletes an account after terminally revoking every attached key and
	 * clearing its default-key selection.
	 *
	 * @param id account id
	 * @return HTTP 204 on success, 404 for unknown accounts
	 */
	@Operation(
			summary = "Delete account with terminal key cascade",
			description = "Revokes every attached key irreversibly, clears the default selection, then removes the row.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "204", description = "Account deleted", content = @Content),
			@ApiResponse(responseCode = "400", description = "Malformed account id"),
			@ApiResponse(responseCode = "404", description = "Account not found"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect")
	})
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> deleteUser(
			@Parameter(description = "Account UUID")
			@PathVariable("id") String id) {
		UserAccount account = requireAccount(id);
		keys.revokeUserKeys(account.getId());
		keys.clearDefaultKey(account.getId());
		users.deleteById(account.getId());
		return ResponseEntity.noContent().build();
	}

	private UserAccount requireAccount(String id) {
		final UUID userId;
		try {
			userId = UUID.fromString(id);
		} catch (IllegalArgumentException malformed) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "malformed account id");
		}
		return users.findById(userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "account not found"));
	}
}
