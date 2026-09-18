package io.github.kxng0109.cacherelay.auth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for profile-conditional refresh cookies.
 */
@DisplayName("AuthCookieService")
class AuthCookieServiceTest {

	@Test
	@DisplayName("production cookie is __Host-prefixed and Secure")
	void productionCookie() {
		Environment environment = mock(Environment.class);
		when(environment.getActiveProfiles()).thenReturn(new String[0]);
		AuthCookieService cookies = new AuthCookieService(AuthProperties.defaults(), environment);

		ResponseCookie created = cookies.createCookie("token", true);

		assertThat(created.getName()).isEqualTo("__Host-refresh");
		assertThat(created.isSecure()).isTrue();
		assertThat(created.isHttpOnly()).isTrue();
		assertThat(created.getPath()).isEqualTo("/");
		assertThat(created.getSameSite()).isEqualTo("Lax");

		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setCookies(new Cookie("__Host-refresh", "token"));
		assertThat(cookies.extract(request)).isEqualTo("token");

		MockHttpServletRequest empty = new MockHttpServletRequest();
		assertThat(cookies.extract(empty)).isNull();
	}

	@Test
	@DisplayName("dev cookie drops the prefix and Secure for plain http")
	void devCookie() {
		Environment environment = mock(Environment.class);
		when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
		AuthCookieService cookies = new AuthCookieService(AuthProperties.defaults(), environment);

		ResponseCookie created = cookies.createCookie("token", false);

		assertThat(created.getName()).isEqualTo("refresh");
		assertThat(created.isSecure()).isFalse();
		assertThat(created.isHttpOnly()).isTrue();

		ResponseCookie cleared = cookies.clearCookie();
		assertThat(cleared.getValue()).isEmpty();
		assertThat(cleared.getMaxAge().getSeconds()).isZero();
	}

	@Test
	@DisplayName("blank cookie values extract as absent")
	void blankCookieAbsent() {
		Environment environment = mock(Environment.class);
		when(environment.getActiveProfiles()).thenReturn(new String[0]);
		AuthCookieService cookies = new AuthCookieService(AuthProperties.defaults(), environment);

		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setCookies(new Cookie("__Host-refresh", "   "));

		assertThat(cookies.extract(request)).isNull();
	}

	@Test
	@DisplayName("later profiles still resolve and foreign cookies are ignored")
	void multiProfileAndForeignCookie() {
		Environment environment = mock(Environment.class);
		when(environment.getActiveProfiles()).thenReturn(new String[]{"prod", "dev"});
		AuthCookieService cookies = new AuthCookieService(AuthProperties.defaults(), environment);

		assertThat(cookies.createCookie("token", false).getName()).isEqualTo("refresh");

		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setCookies(new Cookie("other", "token"));

		assertThat(cookies.extract(request)).isNull();
	}
}
