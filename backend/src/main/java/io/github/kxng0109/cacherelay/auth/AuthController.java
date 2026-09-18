package io.github.kxng0109.cacherelay.auth;

import java.util.Optional;
import java.util.UUID;

import io.github.kxng0109.cacherelay.auth.dto.AccessTokenResponse;
import io.github.kxng0109.cacherelay.auth.dto.LoginRequest;
import io.github.kxng0109.cacherelay.auth.dto.MeResponse;
import io.github.kxng0109.cacherelay.auth.dto.RedeemRequest;
import io.github.kxng0109.cacherelay.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public human-authentication endpoints: local login, invite redemption, session identity,
 * and logout. Refresh rotation lives in {@link RefreshFilter}; SSO entry points are
 * provided by Spring Security under {@code /oauth2/authorization/**}.
 */
@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Human login, invite redemption, session identity, logout")
public class AuthController {

	private final LoginService login;
	private final InviteService invites;
	private final JwtService jwt;
	private final RefreshService refresh;
	private final UserAccountRepository users;
	private final AuthCookieService cookies;
	private final AuthProperties properties;

	/**
	 * Authenticates a local account and opens a session (access token plus refresh cookie).
	 *
	 * @param body    credentials
	 * @param request current request (network attribution)
	 * @return access token with a {@code Set-Cookie} refresh
	 */
	@Operation(summary = "Local login", description = "Authenticates username+password; returns an access token and sets the httpOnly refresh cookie")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Authenticated"),
			@ApiResponse(responseCode = "401", description = "Invalid credentials, locked, or disabled")
	})
	@PostMapping("/login")
	public ResponseEntity<AccessTokenResponse> login(@Valid @RequestBody LoginRequest body,
			HttpServletRequest request) {
		LoginService.LoginResult result = login.login(body.username(), body.password(),
				request.getRemoteAddr(), request.getHeader("X-Request-ID"));
		if (result instanceof LoginService.LoginSuccess success) {
			long ttl = accessTtl(success.admin());
			return ResponseEntity.ok()
					.header(HttpHeaders.SET_COOKIE,
							cookies.createCookie(success.refreshToken(), success.admin()).toString())
					.body(new AccessTokenResponse(success.accessToken(), ttl, success.admin()));
		}
		throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
	}

	/**
	 * Redeems a single-use invite into an account and logs it in immediately.
	 *
	 * @param body    invite token plus desired credentials
	 * @param request current request (network attribution)
	 * @return access token with a {@code Set-Cookie} refresh
	 */
	@Operation(summary = "Redeem invite", description = "Consumes a single-use invite (410 when consumed or expired) and opens a session for the new account")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "201", description = "Redeemed and logged in"),
			@ApiResponse(responseCode = "404", description = "Unknown invite token"),
			@ApiResponse(responseCode = "410", description = "Invite consumed or expired")
	})
	@PostMapping("/redeem")
	public ResponseEntity<AccessTokenResponse> redeem(@Valid @RequestBody RedeemRequest body,
			HttpServletRequest request) {
		InviteService.RedeemResult result = invites.redeem(body.token(), body.username(),
				body.password(), request.getRemoteAddr(), request.getHeader("X-Request-ID"));
		if (result instanceof InviteService.RedeemResult.Missing) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown invite");
		}
		if (result instanceof InviteService.RedeemResult.Gone) {
			throw new ResponseStatusException(HttpStatus.GONE, "Invite consumed or expired");
		}
		LoginService.LoginResult session = login.login(body.username(), body.password(),
				request.getRemoteAddr(), request.getHeader("X-Request-ID"));
		if (session instanceof LoginService.LoginSuccess success) {
			long ttl = accessTtl(success.admin());
			return ResponseEntity.status(HttpStatus.CREATED)
					.header(HttpHeaders.SET_COOKIE,
							cookies.createCookie(success.refreshToken(), success.admin()).toString())
					.body(new AccessTokenResponse(success.accessToken(), ttl, success.admin()));
		}
		throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Login after redeem failed");
	}

	/**
	 * Returns the current session identity.
	 *
	 * @param request current request (bearer token)
	 * @return account identity
	 */
	@Operation(summary = "Session identity", description = "Returns the account behind the presented access token")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Identity"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid token")
	})
	@GetMapping("/me")
	public ResponseEntity<MeResponse> me(HttpServletRequest request) {
		UserAccount account = requireAccount(request);
		return ResponseEntity.ok(new MeResponse(
				account.getId().toString(), account.getUsername(), account.isAdmin()));
	}

	/**
	 * Revokes every session of the current account and clears the refresh cookie.
	 *
	 * @param request current request (bearer token)
	 * @return 204 with a clearing cookie
	 */
	@Operation(summary = "Logout everywhere", description = "Revokes all refresh families of the account and clears the cookie",
			security = @io.swagger.v3.oas.annotations.security.SecurityRequirement(
					name = OpenApiConfig.SCHEME_ADMIN_BEARER))
	@ApiResponses(value = {
			@ApiResponse(responseCode = "204", description = "Logged out"),
			@ApiResponse(responseCode = "401", description = "Missing or invalid token")
	})
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(HttpServletRequest request) {
		UserAccount account = requireAccount(request);
		refresh.revokeAll(account.getId());
		return ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, cookies.clearCookie().toString())
				.build();
	}

	private UserAccount requireAccount(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.startsWith("Bearer ")) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing credentials");
		}
		UUID userId;
		try {
			Jwt decoded = jwt.validate(header.substring("Bearer ".length()).trim());
			userId = UUID.fromString(decoded.getSubject());
		} catch (JwtException | IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
		}
		Optional<UserAccount> account = users.findById(userId);
		if (account.isEmpty() || account.get().isDisabled()) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token");
		}
		return account.get();
	}

	private long accessTtl(boolean admin) {
		return (admin ? properties.adminAccessTtl() : properties.accessTtl()).toSeconds();
	}
}
