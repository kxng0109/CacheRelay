package io.github.kxng0109.cacherelay.auth;

import java.lang.reflect.Field;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SSO completion: OIDC and plain OAuth2 principals, disabled rejection.
 */
@DisplayName("SsoSuccessHandler")
class SsoSuccessHandlerTest {

	private SsoAccountService accounts;
	private JwtService jwt;
	private RefreshService refresh;
	private AuthCookieService cookies;
	private SsoSuccessHandler handler;

	@BeforeEach
	void setUp() {
		accounts = mock(SsoAccountService.class);
		jwt = mock(JwtService.class);
		refresh = mock(RefreshService.class);
		cookies = mock(AuthCookieService.class);
		handler = new SsoSuccessHandler(accounts, jwt, refresh, cookies,
				AuthProperties.defaults());
		when(jwt.issueAccessToken(any(), any(), anyBoolean(), anyLong()))
				.thenReturn("access");
		when(refresh.mint(any(), anyBoolean())).thenReturn("session");
		when(cookies.createCookie(any(), anyBoolean())).thenReturn(
				ResponseCookie.from("refresh", "session").httpOnly(true).secure(false)
						.path("/").maxAge(100).sameSite("Lax").build());
	}

	@Test
	@DisplayName("OIDC principals resolve by issuer and subject with a fragment redirect")
	void oidcFlow() throws Exception {
		UUID userId = UUID.randomUUID();
		UserAccount account = account(userId, "op", false);
		String mint = "randomString";

		OidcIdToken idToken = mock(OidcIdToken.class);
		when(idToken.getIssuer()).thenReturn(new URL("https://accounts.google.com"));
		when(idToken.getSubject()).thenReturn("sub-1");

		OidcUser principal = mock(OidcUser.class);
		when(principal.getIdToken()).thenReturn(idToken);
		when(principal.getSubject()).thenReturn("sub-1");
		when(principal.getAttribute("email")).thenReturn("op@example.com");

		when(refresh.mint(account.getId(), account.isAdmin()))
				.thenReturn(mint);
		when(cookies.createCookie(mint, account.isAdmin()))
				.thenReturn(ResponseCookie.from("refresh", mint).httpOnly(true).secure(false)
						.path("/").maxAge(100).sameSite("Lax").build());

		OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(principal,
				List.of(), "google");

		when(accounts.resolve(eq("https://accounts.google.com"), eq("sub-1"),
				eq("op@example.com"), eq("google"), any(), any()))
				.thenReturn(Optional.of(account));
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(request, response, authentication);

		assertThat(response.getRedirectedUrl()).startsWith("/?sso=1#access_token=access");
		assertThat(response.getHeader("Set-Cookie")).contains("randomString");
	}

	@Test
	@DisplayName("plain OAuth2 principals resolve by synthetic issuer and name")
	void oauth2Flow() throws Exception {
		UUID userId = UUID.randomUUID();
		UserAccount account = account(userId, "octocat", false);
		OAuth2User principal = mock(OAuth2User.class);
		when(principal.getName()).thenReturn("octocat");
		when(principal.getAttribute("email")).thenReturn(null);
		OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(principal,
				List.of(), "github");
		when(accounts.resolve("https://github.oauth", "octocat", null, "github", null, null))
				.thenReturn(Optional.of(account));

		handler.onAuthenticationSuccess(new MockHttpServletRequest(),
				new MockHttpServletResponse(), authentication);
	}

	@Test
	@DisplayName("disabled linked accounts are forbidden")
	void disabledForbidden() throws Exception {
		OAuth2User principal = mock(OAuth2User.class);
		when(principal.getName()).thenReturn("off");
		OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(principal,
				List.of(), "github");
		when(accounts.resolve(any(), any(), any(), any(), any(), any()))
				.thenReturn(Optional.empty());
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

		assertThat(response.getStatus()).isEqualTo(403);
	}

	@Test
	@DisplayName("non-OAuth2 authentications are unauthorized")
	void nonOauthUnauthorized() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
				new UsernamePasswordAuthenticationToken("u", "p"));

		assertThat(response.getStatus()).isEqualTo(401);
	}

	@Test
	@DisplayName("OIDC principals without an id token fall back to the OAuth2 path")
	void oidcWithoutIdToken() throws Exception {
		UUID userId = UUID.randomUUID();
		UserAccount account = account(userId, "op", false);
		OidcUser principal = mock(OidcUser.class);
		when(principal.getIdToken()).thenReturn(null);
		when(principal.getName()).thenReturn("op");
		when(principal.getAttribute("email")).thenReturn(null);
		OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(principal,
				List.of(), "okta");
		when(accounts.resolve(eq("https://okta.oauth"), eq("op"), isNull(), eq("okta"), any(),
				any())).thenReturn(Optional.of(account));
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

		assertThat(response.getRedirectedUrl()).startsWith("/?sso=1#access_token=");
	}

	@Test
	@DisplayName("admin sessions stamp the admin fragment flag")
	void adminFragmentFlag() throws Exception {
		UUID userId = UUID.randomUUID();
		UserAccount account = account(userId, "root", true);
		OAuth2User principal = mock(OAuth2User.class);
		when(principal.getName()).thenReturn("root");
		OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(principal,
				List.of(), "github");
		when(accounts.resolve(any(), any(), any(), any(), any(), any()))
				.thenReturn(Optional.of(account));
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

		assertThat(response.getRedirectedUrl()).contains("admin=true");
	}

	private UserAccount account(UUID id, String username, boolean admin) {
		UserAccount account = new UserAccount(username, "hash", null, admin);
		try {
			Field field = UserAccount.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(account, id);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Test reflection failed", e);
		}
		return account;
	}
}
