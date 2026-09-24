package io.github.kxng0109.cacherelay.auth;

import java.lang.reflect.Field;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import io.github.kxng0109.cacherelay.auth.backfill.BackfillOutcome;
import io.github.kxng0109.cacherelay.auth.backfill.BackfillRequest;
import io.github.kxng0109.cacherelay.auth.backfill.BackfillResult;
import io.github.kxng0109.cacherelay.auth.backfill.SsoBackfillOrchestrator;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SSO completion: OIDC and plain OAuth2 principals, disabled rejection.
 */
@DisplayName("SsoSuccessHandler")
class SsoSuccessHandlerTest {

	private SsoAccountService accounts;
	private SsoProvisioningService provisioning;
	private SsoBackfillOrchestrator backfill;
	private OAuth2AuthorizedClientService authorizedClients;
	private JwtService jwt;
	private RefreshService refresh;
	private AuthCookieService cookies;
	private SsoSuccessHandler handler;

	@BeforeEach
	void setUp() {
		accounts = mock(SsoAccountService.class);
		provisioning = mock(SsoProvisioningService.class);
		backfill = mock(SsoBackfillOrchestrator.class);
		authorizedClients = mock(OAuth2AuthorizedClientService.class);
		jwt = mock(JwtService.class);
		refresh = mock(RefreshService.class);
		cookies = mock(AuthCookieService.class);
		handler = new SsoSuccessHandler(accounts, provisioning, backfill, Optional.empty(),
				jwt, refresh, cookies, AuthProperties.defaults());
		when(provisioning.provision(any(), any(), any(), any(), any(), any(), any()))
				.thenAnswer(invocation -> Optional.ofNullable(invocation.getArgument(0)));
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

	@Test
	@DisplayName("group claims and id token claims reach provisioning")
	void groupClaimsProvisioned() throws Exception {
		UUID userId = UUID.randomUUID();
		UserAccount account = account(userId, "op", false);
		OidcIdToken idToken = mock(OidcIdToken.class);
		when(idToken.getIssuer()).thenReturn(new URL("https://login.example.com/tid-1"));
		when(idToken.getSubject()).thenReturn("sub-9");
		when(idToken.getClaims()).thenReturn(Map.of("tid", "tid-1"));
		OidcUser principal = mock(OidcUser.class);
		when(principal.getIdToken()).thenReturn(idToken);
		when(principal.getSubject()).thenReturn("sub-9");
		when(principal.getAttribute("email")).thenReturn("op@example.com");
		when(principal.getAttributes()).thenReturn(Map.of("groups", List.of("eng-a")));
		OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(principal,
				List.of(), "azure");
		when(accounts.resolve(eq("https://login.example.com/tid-1"), eq("sub-9"),
				eq("op@example.com"), eq("azure"), any(), any()))
				.thenReturn(Optional.of(account));
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

		assertThat(response.getRedirectedUrl()).startsWith("/?sso=1#access_token=");
		verify(provisioning).provision(eq(account), eq("azure"),
				eq("https://login.example.com/tid-1"), eq(Map.of("groups", List.of("eng-a"))),
				eq(Map.of("tid", "tid-1")), any(), any());
	}

	@Test
	@DisplayName("null id token claims fall back to an empty map")
	void nullIdTokenClaimsFallback() throws Exception {
		UUID userId = UUID.randomUUID();
		UserAccount account = account(userId, "op", false);
		OidcIdToken idToken = mock(OidcIdToken.class);
		when(idToken.getIssuer()).thenReturn(new URL("https://login.example.com/tid-1"));
		when(idToken.getSubject()).thenReturn("sub-1");
		when(idToken.getClaims()).thenReturn(null);
		OidcUser principal = mock(OidcUser.class);
		when(principal.getIdToken()).thenReturn(idToken);
		when(principal.getSubject()).thenReturn("sub-1");
		when(principal.getAttribute("email")).thenReturn(null);
		when(principal.getAttributes()).thenReturn(Map.of());
		OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(principal,
				List.of(), "azure");
		when(accounts.resolve(eq("https://login.example.com/tid-1"), eq("sub-1"), isNull(),
				eq("azure"), any(), any())).thenReturn(Optional.of(account));
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

		assertThat(response.getRedirectedUrl()).startsWith("/?sso=1#access_token=");
		verify(provisioning).provision(eq(account), eq("azure"),
				eq("https://login.example.com/tid-1"), eq(Map.of()), eq(Map.of()), any(), any());
	}

	@Test
	@DisplayName("tenant denials fail the login closed")
	void tenantDeniedForbidden() throws Exception {
		UUID userId = UUID.randomUUID();
		UserAccount account = account(userId, "op", false);
		OAuth2User principal = mock(OidcUser.class);
		when(principal.getName()).thenReturn("op");
		OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(principal,
				List.of(), "azure");
		when(accounts.resolve(any(), any(), any(), any(), any(), any()))
				.thenReturn(Optional.of(account));
		when(provisioning.provision(any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(Optional.empty());
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

		assertThat(response.getStatus()).isEqualTo(403);
	}

	@Test
	@DisplayName("successful backfills provision with fetched groups")
	void backfillSuccessRedirects() throws Exception {
		UUID userId = UUID.randomUUID();
		UserAccount account = account(userId, "op", false);
		OidcIdToken idToken = mock(OidcIdToken.class);
		when(idToken.getIssuer()).thenReturn(new URL("https://login.example.com/tid-1"));
		when(idToken.getSubject()).thenReturn("sub-9");
		OidcUser principal = mock(OidcUser.class);
		when(principal.getIdToken()).thenReturn(idToken);
		when(principal.getSubject()).thenReturn("sub-9");
		when(principal.getAttribute("email")).thenReturn("op@example.com");
		when(principal.getAttributes()).thenReturn(Map.of("email", "op@example.com"));
		OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(principal,
				List.of(), "azure");
		when(accounts.resolve(eq("https://login.example.com/tid-1"), eq("sub-9"),
				eq("op@example.com"), eq("azure"), any(), any()))
				.thenReturn(Optional.of(account));
		when(provisioning.needsBackfill(userId, "azure")).thenReturn(true);
		BackfillResult fetched = new BackfillResult(Map.of("group-1", "Engineering"), false);
		when(backfill.backfill(eq("azure"), any(BackfillRequest.class), any(), any()))
				.thenReturn(BackfillOutcome.succeeded(fetched));
		when(provisioning.provisionWithBackfill(eq(account), eq("azure"), any(), any(), any(),
				eq(Map.of("group-1", "Engineering")), any(), any()))
				.thenReturn(Optional.of(account));
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

		assertThat(response.getRedirectedUrl()).startsWith("/?sso=1#access_token=");
		verify(provisioning, never()).provision(any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("failed backfills deny the login")
	void backfillFailedForbidden() throws Exception {
		UUID userId = UUID.randomUUID();
		UserAccount account = account(userId, "op", false);
		OidcUser principal = mock(OidcUser.class);
		when(principal.getName()).thenReturn("op");
		OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(principal,
				List.of(), "azure");
		when(accounts.resolve(any(), any(), any(), any(), any(), any()))
				.thenReturn(Optional.of(account));
		when(provisioning.needsBackfill(userId, "azure")).thenReturn(true);
		when(backfill.backfill(eq("azure"), any(BackfillRequest.class), any(), any()))
				.thenReturn(BackfillOutcome.failed());
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

		assertThat(response.getStatus()).isEqualTo(403);
	}

	@Test
	@DisplayName("disabled backfill verdicts deny the login")
	void backfillDisabledForbidden() throws Exception {
		UUID userId = UUID.randomUUID();
		UserAccount account = account(userId, "op", false);
		OidcUser principal = mock(OidcUser.class);
		when(principal.getName()).thenReturn("op");
		OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(principal,
				List.of(), "azure");
		when(accounts.resolve(any(), any(), any(), any(), any(), any()))
				.thenReturn(Optional.of(account));
		when(provisioning.needsBackfill(userId, "azure")).thenReturn(true);
		when(backfill.backfill(eq("azure"), any(BackfillRequest.class), any(), any()))
				.thenReturn(BackfillOutcome.succeeded(BackfillResult.forDisabledAccount()));
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

		assertThat(response.getStatus()).isEqualTo(403);
	}

	@Test
	@DisplayName("skipped backfills fall through to classic provisioning")
	void backfillSkippedClassic() throws Exception {
		UUID userId = UUID.randomUUID();
		UserAccount account = account(userId, "op", false);
		OidcUser principal = mock(OidcUser.class);
		when(principal.getName()).thenReturn("op");
		OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(principal,
				List.of(), "azure");
		when(accounts.resolve(any(), any(), any(), any(), any(), any()))
				.thenReturn(Optional.of(account));
		when(provisioning.needsBackfill(userId, "azure")).thenReturn(true);
		when(backfill.backfill(eq("azure"), any(BackfillRequest.class), any(), any()))
				.thenReturn(BackfillOutcome.skipped());
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

		assertThat(response.getRedirectedUrl()).startsWith("/?sso=1#access_token=");
		verify(provisioning).provision(eq(account), eq("azure"), any(), any(), any(), any(),
				any());
	}

	@Test
	@DisplayName("github logins load the user token for backfill")
	void githubTokenLoaded() throws Exception {
		UUID userId = UUID.randomUUID();
		UserAccount account = account(userId, "op", false);
		OAuth2User principal = mock(OAuth2User.class);
		when(principal.getName()).thenReturn("op");
		when(principal.getAttribute("login")).thenReturn("op");
		OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(principal,
				List.of(), "github");
		when(accounts.resolve(any(), any(), any(), any(), any(), any()))
				.thenReturn(Optional.of(account));
		when(provisioning.needsBackfill(userId, "github")).thenReturn(true);
		OAuth2AuthorizedClient authorized = mock(OAuth2AuthorizedClient.class);
		OAuth2AccessToken accessToken = mock(OAuth2AccessToken.class);
		when(accessToken.getTokenValue()).thenReturn("github-user-token");
		when(authorized.getAccessToken()).thenReturn(accessToken);
		when(authorizedClients.loadAuthorizedClient("github", "op")).thenReturn(authorized);
		BackfillResult fetched = new BackfillResult(Map.of("eng", "Engineering"), false);
		when(backfill.backfill(eq("github"), any(BackfillRequest.class), any(), any()))
				.thenReturn(BackfillOutcome.succeeded(fetched));
		when(provisioning.provisionWithBackfill(eq(account), eq("github"), any(), any(), any(),
				any(), any(), any())).thenReturn(Optional.of(account));
		MockHttpServletResponse response = new MockHttpServletResponse();
		SsoSuccessHandler githubHandler = new SsoSuccessHandler(accounts, provisioning, backfill,
				Optional.of(authorizedClients), jwt, refresh, cookies, AuthProperties.defaults());

		githubHandler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
				authentication);

		assertThat(response.getRedirectedUrl()).startsWith("/?sso=1#access_token=");
		verify(backfill).backfill(eq("github"), argThat(request ->
				"op".equals(request.login())
						&& "github-user-token".equals(request.userToken())), any(), any());
	}

	@Test
	@DisplayName("missing user tokens fail closed through the client")
	void missingUserTokenDenied() throws Exception {
		UUID userId = UUID.randomUUID();
		UserAccount account = account(userId, "op", false);
		OAuth2User principal = mock(OAuth2User.class);
		when(principal.getName()).thenReturn("op");
		OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(principal,
				List.of(), "github");
		when(accounts.resolve(any(), any(), any(), any(), any(), any()))
				.thenReturn(Optional.of(account));
		when(provisioning.needsBackfill(userId, "github")).thenReturn(true);
		when(authorizedClients.loadAuthorizedClient("github", "op")).thenReturn(null);
		when(backfill.backfill(eq("github"), any(BackfillRequest.class), any(), any()))
				.thenReturn(BackfillOutcome.failed());
		MockHttpServletResponse response = new MockHttpServletResponse();
		SsoSuccessHandler githubHandler = new SsoSuccessHandler(accounts, provisioning, backfill,
				Optional.of(authorizedClients), jwt, refresh, cookies, AuthProperties.defaults());

		githubHandler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
				authentication);

		assertThat(response.getStatus()).isEqualTo(403);
	}

	@Test
	@DisplayName("null access tokens fail closed")
	void nullAccessTokenDenied() throws Exception {
		UUID userId = UUID.randomUUID();
		UserAccount account = account(userId, "op", false);
		OAuth2User principal = mock(OAuth2User.class);
		when(principal.getName()).thenReturn("op");
		OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(principal,
				List.of(), "github");
		when(accounts.resolve(any(), any(), any(), any(), any(), any()))
				.thenReturn(Optional.of(account));
		when(provisioning.needsBackfill(userId, "github")).thenReturn(true);
		OAuth2AuthorizedClient authorized = mock(OAuth2AuthorizedClient.class);
		when(authorized.getAccessToken()).thenReturn(null);
		when(authorizedClients.loadAuthorizedClient("github", "op")).thenReturn(authorized);
		when(backfill.backfill(eq("github"), any(BackfillRequest.class), any(), any()))
				.thenReturn(BackfillOutcome.failed());
		MockHttpServletResponse response = new MockHttpServletResponse();
		SsoSuccessHandler githubHandler = new SsoSuccessHandler(accounts, provisioning, backfill,
				Optional.of(authorizedClients), jwt, refresh, cookies, AuthProperties.defaults());

		githubHandler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
				authentication);

		assertThat(response.getStatus()).isEqualTo(403);
		verify(backfill).backfill(eq("github"), argThat(request -> request.userToken() == null),
				any(), any());
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
