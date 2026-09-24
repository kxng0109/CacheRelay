package io.github.kxng0109.cacherelay.auth;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import io.github.kxng0109.cacherelay.auth.backfill.BackfillOutcome;
import io.github.kxng0109.cacherelay.auth.backfill.BackfillRequest;
import io.github.kxng0109.cacherelay.auth.backfill.BackfillStatus;
import io.github.kxng0109.cacherelay.auth.backfill.SsoBackfillOrchestrator;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * Completes SSO logins: resolves the external identity to a shadow account,
 * provisions org/team memberships from IdP claims (tenant denials fail
 * closed), sets the refresh cookie, and redirects to the SPA shell with the
 * short-lived access token in the URL fragment. Fragments never leave the
 * browser (no transmission, no logs); the SPA moves the token to memory and
 * clears the URL immediately.
 */
@Component
@RequiredArgsConstructor
public class SsoSuccessHandler implements AuthenticationSuccessHandler {

	private final SsoAccountService accounts;
	private final SsoProvisioningService provisioning;
	private final SsoBackfillOrchestrator backfill;
	private final Optional<OAuth2AuthorizedClientService> authorizedClients;
	private final JwtService jwt;
	private final RefreshService refresh;
	private final AuthCookieService cookies;
	private final AuthProperties properties;

	@Override
	public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException {
		if (!(authentication instanceof OAuth2AuthenticationToken token)) {
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
			return;
		}
		OAuth2User principal = token.getPrincipal();
		String registrationId = token.getAuthorizedClientRegistrationId();
		String issuer;
		String subject;
		String email = principal.getAttribute("email");
		if (principal instanceof OidcUser oidc && oidc.getIdToken() != null) {
			issuer = oidc.getIdToken().getIssuer().toString();
			subject = oidc.getSubject();
		} else {
			issuer = "https://" + registrationId + ".oauth";
			subject = principal.getName();
		}
		Optional<UserAccount> account = accounts.resolve(issuer, subject, email, registrationId,
				request.getRemoteAddr(), request.getHeader("X-Request-ID"));
		if (account.isEmpty()) {
			response.sendError(HttpServletResponse.SC_FORBIDDEN);
			return;
		}
		Map<String, Object> attributes =
				principal.getAttributes() == null ? Map.of() : principal.getAttributes();
		Map<String, Object> idClaims = idTokenClaims(principal);
		Optional<UserAccount> provisioned;
		if (provisioning.needsBackfill(account.get().getId(), registrationId)) {
			provisioned = provisionWithBackfill(token, principal, account.get(),
					registrationId, issuer, subject, email, attributes, idClaims, request);
		} else {
			provisioned = provisioning.provision(account.get(), registrationId, issuer,
					attributes, idClaims, request.getRemoteAddr(),
					request.getHeader("X-Request-ID"));
		}
		if (provisioned.isEmpty()) {
			response.sendError(HttpServletResponse.SC_FORBIDDEN);
			return;
		}
		UserAccount user = provisioned.get();
		long ttl = (user.isAdmin()
				? properties.adminAccessTtl()
				: properties.accessTtl()).toSeconds();
		String access = jwt.issueAccessToken(user.getId(), user.getUsername(), user.isAdmin(),
				ttl);
		String session = refresh.mint(user.getId(), user.isAdmin());
		ResponseCookie cookie = cookies.createCookie(session, user.isAdmin());
		response.setHeader("Set-Cookie", cookie.toString());
		String fragment = "access_token=" + URLEncoder.encode(access, StandardCharsets.UTF_8)
				+ "&admin=" + user.isAdmin();
		response.sendRedirect(request.getContextPath() + "/?sso=1#" + fragment);
	}

	private Map<String, Object> idTokenClaims(OAuth2User principal) {
		if (principal instanceof OidcUser oidc && oidc.getIdToken() != null
				&& oidc.getIdToken().getClaims() != null) {
			return oidc.getIdToken().getClaims();
		}
		return Map.of();
	}

	/**
	 * Runs the blocking first-login backfill; empty denies the login through
	 * the caller's single 403 site.
	 */
	private Optional<UserAccount> provisionWithBackfill(OAuth2AuthenticationToken authentication,
			OAuth2User principal, UserAccount account, String registrationId, String issuer,
			String subject, String email, Map<String, Object> attributes,
			Map<String, Object> idClaims, HttpServletRequest request) {
		String userToken = "github".equals(registrationId) ? loadUserToken(authentication) : null;
		BackfillRequest backfillRequest = new BackfillRequest(subject, email,
				loginOf(principal), userToken, attributes);
		BackfillOutcome outcome = backfill.backfill(registrationId, backfillRequest,
				request.getRemoteAddr(), request.getHeader("X-Request-ID"));
		if (outcome.status() == BackfillStatus.FAILED) {
			return Optional.empty();
		}
		if (outcome.status() == BackfillStatus.SUCCEEDED) {
			if (outcome.result().disabled()) {
				return Optional.empty();
			}
			return provisioning.provisionWithBackfill(account, registrationId, issuer,
					attributes, idClaims, outcome.result().groups(), request.getRemoteAddr(),
					request.getHeader("X-Request-ID"));
		}
		return provisioning.provision(account, registrationId, issuer, attributes, idClaims,
				request.getRemoteAddr(), request.getHeader("X-Request-ID"));
	}

	private String loginOf(OAuth2User principal) {
		Object login = principal.getAttribute("login");
		if (login instanceof String text) {
			return text;
		}
		return principal.getName();
	}

	private String loadUserToken(OAuth2AuthenticationToken authentication) {
		if (authorizedClients.isEmpty()) {
			return null;
		}
		OAuth2AuthorizedClient client = authorizedClients.get().loadAuthorizedClient(
				authentication.getAuthorizedClientRegistrationId(), authentication.getName());
		if (client == null || client.getAccessToken() == null) {
			return null;
		}
		return client.getAccessToken().getTokenValue();
	}
}
