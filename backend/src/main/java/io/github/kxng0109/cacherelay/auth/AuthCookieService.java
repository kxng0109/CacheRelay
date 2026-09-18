package io.github.kxng0109.cacherelay.auth;

import java.time.Duration;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

/**
 * Profile-conditional refresh cookie handling. Production sets the strict cookie
 * ({@code __Host-} prefix, {@code Secure}, {@code HttpOnly}, explicit
 * {@code SameSite=Lax}, {@code Path=/}); the {@code dev} profile relaxes the name and
 * {@code Secure} so plain-{@code http://localhost} development works.
 */
@Service
public class AuthCookieService {

	private final AuthProperties properties;
	private final boolean devProfile;

	/**
	 * Creates the service, sniffing the active profiles once.
	 *
	 * @param properties  auth tuning surface
	 * @param environment active profiles
	 */
	public AuthCookieService(AuthProperties properties, Environment environment) {
		this.properties = properties;
		boolean dev = false;
		for (String profile : environment.getActiveProfiles()) {
			if ("dev".equals(profile)) {
				dev = true;
			}
		}
		this.devProfile = dev;
	}

	/**
	 * Builds the {@code Set-Cookie} value carrying a fresh refresh token.
	 *
	 * @param token opaque refresh token
	 * @param admin whether strict admin idle lifetime applies
	 * @return configured cookie
	 */
	public ResponseCookie createCookie(String token, boolean admin) {
		Duration idle = admin ? properties.adminRefreshTtl() : properties.refreshTtl();
		return ResponseCookie.from(cookieName(), token)
				.httpOnly(true)
				.secure(!devProfile)
				.path("/")
				.maxAge(idle.toSeconds())
				.sameSite(properties.cookieSameSite())
				.build();
	}

	/**
	 * Builds the clearing cookie (empty value, immediate expiry).
	 *
	 * @return clearing cookie
	 */
	public ResponseCookie clearCookie() {
		return ResponseCookie.from(cookieName(), "")
				.httpOnly(true)
				.secure(!devProfile)
				.path("/")
				.maxAge(0)
				.sameSite(properties.cookieSameSite())
				.build();
	}

	/**
	 * Extracts the presented refresh token from the request cookies.
	 *
	 * @param request current request
	 * @return token value, or {@code null} when absent
	 */
	public String extract(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (cookieName().equals(cookie.getName())) {
				String value = cookie.getValue();
				return value != null && !value.isBlank() ? value : null;
			}
		}
		return null;
	}

	private String cookieName() {
		return devProfile ? "refresh" : properties.cookieName();
	}
}
