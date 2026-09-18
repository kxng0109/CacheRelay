package io.github.kxng0109.cacherelay.auth;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * Completes SSO logins: resolves the external identity to a shadow account, sets the
 * refresh cookie, and redirects to the SPA shell with the short-lived access token in the
 * URL fragment. Fragments never leave the browser (no transmission, no logs); the SPA
 * moves the token to memory and clears the URL immediately.
 */
@Component
@RequiredArgsConstructor
public class SsoSuccessHandler implements AuthenticationSuccessHandler {

	private final SsoAccountService accounts;
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
		UserAccount user = account.get();
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
}
