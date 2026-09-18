package io.github.kxng0109.cacherelay.auth;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for refresh rotation over HTTP: CSRF guard, cookie handling, outcomes.
 */
@DisplayName("RefreshFilter")
class RefreshFilterTest {

	private RefreshService refresh;
	private JwtService jwt;
	private UserAccountRepository users;
	private AuthCookieService cookies;
	private AuthAuditService audit;
	private RefreshFilter filter;
	private final FilterChain chain = mock(FilterChain.class);

	@BeforeEach
	void setUp() {
		refresh = mock(RefreshService.class);
		jwt = mock(JwtService.class);
		users = mock(UserAccountRepository.class);
		cookies = mock(AuthCookieService.class);
		audit = mock(AuthAuditService.class);
		filter = new RefreshFilter(refresh, jwt, users, cookies, AuthProperties.defaults(),
				audit, new ObjectMapper());
	}

	@Test
	@DisplayName("missing CSRF header is a 400 without touching rotation")
	void missingHeaderBadRequest() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/auth/refresh");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(400);
	}

	@Test
	@DisplayName("missing cookie is a 401")
	void missingCookieUnauthorized() throws Exception {
		MockHttpServletRequest request = post();
		when(cookies.extract(request)).thenReturn(null);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(401);
	}

	@Test
	@DisplayName("successful rotation returns access plus a rotated cookie")
	void successRotates() throws Exception {
		UUID userId = UUID.randomUUID();
		UserAccount account = new UserAccount("op", "h", null, false);
		setId(account, userId);
		MockHttpServletRequest request = post();
		request.setCookies(new Cookie("refresh", "presented"));
		when(cookies.extract(request)).thenReturn("presented");
		when(refresh.rotate("presented")).thenReturn(
				new RefreshService.RotationSuccess("next", userId, false));
		when(users.findById(userId)).thenReturn(Optional.of(account));
		when(jwt.issueAccessToken(userId, "op", false, 600L)).thenReturn("access");
		when(cookies.createCookie("next", false)).thenReturn(cookie("refresh", "next"));
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getHeader("Set-Cookie")).contains("next");
		assertThat(response.getContentAsString()).contains("access");
	}

	@Test
	@DisplayName("reuse clears the cookie and is a 401")
	void reuseClearsCookie() throws Exception {
		MockHttpServletRequest request = post();
		request.setCookies(new Cookie("refresh", "replayed"));
		when(cookies.extract(request)).thenReturn("replayed");
		when(refresh.rotate("replayed")).thenReturn(new RefreshService.RotationReuse());
		when(cookies.clearCookie()).thenReturn(cookie("refresh", ""));
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getHeader("Set-Cookie")).isNotNull();
	}

	@Test
	@DisplayName("rotation for a deleted account is a 401")
	void accountGoneUnauthorized() throws Exception {
		UUID userId = UUID.randomUUID();
		MockHttpServletRequest request = post();
		request.setCookies(new Cookie("refresh", "presented"));
		when(cookies.extract(request)).thenReturn("presented");
		when(refresh.rotate("presented")).thenReturn(
				new RefreshService.RotationSuccess("next", userId, false));
		when(users.findById(userId)).thenReturn(Optional.empty());
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(401);
	}

	@Test
	@DisplayName("invalid tokens are a generic 401")
	void invalidUnauthorized() throws Exception {
		MockHttpServletRequest request = post();
		request.setCookies(new Cookie("refresh", "bogus"));
		when(cookies.extract(request)).thenReturn("bogus");
		when(refresh.rotate("bogus")).thenReturn(new RefreshService.RotationInvalid());
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(401);
	}

	@Test
	@DisplayName("non-POST and off-path requests bypass the filter")
	void shouldNotFilterShapes() {
		assertThat(filter.shouldNotFilter(
				new MockHttpServletRequest("GET", "/v1/auth/refresh"))).isTrue();
		assertThat(filter.shouldNotFilter(
				new MockHttpServletRequest("POST", "/v1/auth/other"))).isTrue();
		assertThat(filter.shouldNotFilter(
				new MockHttpServletRequest("POST", "/v1/auth/refresh"))).isFalse();
	}

	@Test
	@DisplayName("admin rotations stamp the strict access lifetime")
	void adminRotationStrictTtl() throws Exception {
		UUID userId = UUID.randomUUID();
		UserAccount account = new UserAccount("root", "h", null, true);
		setId(account, userId);
		MockHttpServletRequest request = post();
		request.setCookies(new Cookie("refresh", "presented"));
		when(cookies.extract(request)).thenReturn("presented");
		when(refresh.rotate("presented")).thenReturn(
				new RefreshService.RotationSuccess("next", userId, true));
		when(users.findById(userId)).thenReturn(Optional.of(account));
		when(jwt.issueAccessToken(userId, "root", true, 300L)).thenReturn("access");
		when(cookies.createCookie("next", true)).thenReturn(cookie("refresh", "next"));
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getContentAsString()).contains("\"expiresInSeconds\":300");
	}

	private MockHttpServletRequest post() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/auth/refresh");
		request.addHeader("X-CacheRelay-Refresh", "1");
		return request;
	}

	private ResponseCookie cookie(String name, String value) {
		return ResponseCookie.from(name, value).httpOnly(true).secure(false).path("/")
				.maxAge(100).sameSite("Lax").build();
	}

	private static void setId(UserAccount account, UUID id) {
		try {
			Field field = UserAccount.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(account, id);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Test reflection failed", e);
		}
	}
}
