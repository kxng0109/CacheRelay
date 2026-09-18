package io.github.kxng0109.cacherelay.auth;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.kxng0109.cacherelay.auth.dto.LoginRequest;
import io.github.kxng0109.cacherelay.auth.dto.RedeemRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import io.github.kxng0109.cacherelay.auth.dto.AccessTokenResponse;
import io.github.kxng0109.cacherelay.auth.dto.MeResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for auth endpoints: success shapes and every rejection status.
 */
@DisplayName("AuthController")
class AuthControllerTest {

	private LoginService login;
	private InviteService invites;
	private JwtService jwt;
	private RefreshService refresh;
	private UserAccountRepository users;
	private AuthCookieService cookies;
	private AuthController controller;

	@BeforeEach
	void setUp() {
		login = mock(LoginService.class);
		invites = mock(InviteService.class);
		jwt = mock(JwtService.class);
		refresh = mock(RefreshService.class);
		users = mock(UserAccountRepository.class);
		cookies = mock(AuthCookieService.class);
		controller = new AuthController(login, invites, jwt, refresh, users, cookies,
				AuthProperties.defaults());
		when(cookies.createCookie(any(), anyBoolean())).thenReturn(
				ResponseCookie.from("refresh", "s").httpOnly(true).secure(false).path("/")
						.maxAge(100).sameSite("Lax").build());
		when(cookies.clearCookie()).thenReturn(
				ResponseCookie.from("refresh", "").httpOnly(true).secure(false).path("/")
						.maxAge(0).sameSite("Lax").build());
	}

	@Test
	@DisplayName("login success returns the token and sets the cookie")
	void loginSuccess() {
		UUID userId = UUID.randomUUID();

		when(login.login(eq("op"), eq("secret-password-1"), any(), any())).thenReturn(
				new LoginService.LoginSuccess("access", "session", userId, false));

		ResponseEntity<AccessTokenResponse> response =
				controller.login(new LoginRequest("op", "secret-password-1"),
						new MockHttpServletRequest());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().accessToken()).isEqualTo("access");
		assertThat(response.getHeaders().get("Set-Cookie").getFirst()).contains("refresh=s");
	}

	@Test
	@DisplayName("login rejection is a 401")
	void loginRejected() {
		when(login.login(any(), any(), any(), any())).thenReturn(
				new LoginService.LoginRejected());

		assertThatThrownBy(() -> controller.login(new LoginRequest("op", "x"),
				new MockHttpServletRequest()))
				.isInstanceOf(ResponseStatusException.class)
				.satisfies(e -> assertThat(
						((ResponseStatusException) e).getStatusCode().value()).isEqualTo(401));
	}

	@Test
	@DisplayName("redeem maps missing to 404 and gone to 410")
	void redeemStatuses() {
		when(invites.redeem(any(), any(), any(), any(), any())).thenReturn(
				new InviteService.RedeemResult.Missing());

		assertThatThrownBy(() -> controller.redeem(
				new RedeemRequest("t", "user1", "password-12345"),
				new MockHttpServletRequest()))
				.isInstanceOf(ResponseStatusException.class)
				.satisfies(e -> assertThat(
						((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));

		when(invites.redeem(any(), any(), any(), any(), any())).thenReturn(
				new InviteService.RedeemResult.Gone());

		assertThatThrownBy(() -> controller.redeem(
				new RedeemRequest("t", "user1", "password-12345"),
				new MockHttpServletRequest()))
				.isInstanceOf(ResponseStatusException.class)
				.satisfies(e -> assertThat(
						((ResponseStatusException) e).getStatusCode().value()).isEqualTo(410));
	}

	@Test
	@DisplayName("identity requires a live account behind the token")
	void identityGuards() {
		MockHttpServletRequest bare = new MockHttpServletRequest();
		assertThatThrownBy(() -> controller.me(bare))
				.isInstanceOf(ResponseStatusException.class);

		MockHttpServletRequest bad = new MockHttpServletRequest();
		bad.addHeader("Authorization", "Bearer bogus");
		when(jwt.validate("bogus")).thenThrow(new BadJwtException("bad"));
		assertThatThrownBy(() -> controller.me(bad))
				.isInstanceOf(ResponseStatusException.class)
				.satisfies(e -> assertThat(
						((ResponseStatusException) e).getStatusCode().value()).isEqualTo(401));

		UUID userId = UUID.randomUUID();
		Jwt decoded = mock(Jwt.class);
		when(decoded.getSubject()).thenReturn(userId.toString());
		when(jwt.validate("good")).thenReturn(decoded);
		UserAccount disabled = new UserAccount("off", "h", null, false);
		setDisabled(disabled);
		when(users.findById(userId)).thenReturn(Optional.of(disabled));
		MockHttpServletRequest gone = new MockHttpServletRequest();
		gone.addHeader("Authorization", "Bearer good");
		assertThatThrownBy(() -> controller.me(gone))
				.isInstanceOf(ResponseStatusException.class);

		UserAccount account = new UserAccount("op", "h", null, true);
		setId(account, userId);
		when(users.findById(userId)).thenReturn(Optional.of(account));
		MockHttpServletRequest live = new MockHttpServletRequest();
		live.addHeader("Authorization", "Bearer good");

		ResponseEntity<MeResponse> me = controller.me(live);

		assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(me.getBody().username()).isEqualTo("op");
		assertThat(me.getBody().admin()).isTrue();
	}

	@Test
	@DisplayName("logout revokes every session and clears the cookie")
	void logoutRevokes() {
		UUID userId = UUID.randomUUID();
		Jwt decoded = mock(Jwt.class);
		when(decoded.getSubject()).thenReturn(userId.toString());
		when(jwt.validate("good")).thenReturn(decoded);
		UserAccount account = new UserAccount("op", "h", null, false);
		setId(account, userId);
		when(users.findById(userId)).thenReturn(Optional.of(account));
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader("Authorization", "Bearer good");

		var response = controller.logout(request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(response.getHeaders().get("Set-Cookie").getFirst()).isNotNull();
		verify(refresh).revokeAll(userId);
	}

	@Test
	@DisplayName("redeem that cannot open a session is a 500")
	void redeemLoginFailure() {
		UserAccount account = new UserAccount("fresh", "h", null, false);
		when(invites.redeem(any(), any(), any(), any(), any())).thenReturn(
				new InviteService.RedeemResult.Redeemed(account, false));
		when(login.login(any(), any(), any(), any())).thenReturn(
				new LoginService.LoginRejected());

		assertThatThrownBy(() -> controller.redeem(
				new RedeemRequest("t", "fresh", "password-12345"),
				new MockHttpServletRequest()))
				.isInstanceOf(ResponseStatusException.class)
				.satisfies(e -> assertThat(
						((ResponseStatusException) e).getStatusCode().value()).isEqualTo(500));
	}

	@Test
	@DisplayName("non-bearer and unknown-token identities are 401")
	void identityMoreGuards() {
		MockHttpServletRequest basic = new MockHttpServletRequest();
		basic.addHeader("Authorization", "Basic dXNlcjpwYXNz");
		assertThatThrownBy(() -> controller.me(basic))
				.isInstanceOf(ResponseStatusException.class)
				.satisfies(e -> assertThat(
						((ResponseStatusException) e).getStatusCode().value()).isEqualTo(401));

		UUID userId = UUID.randomUUID();
		Jwt decoded = mock(Jwt.class);
		when(decoded.getSubject()).thenReturn(userId.toString());
		when(jwt.validate("orphan")).thenReturn(decoded);
		when(users.findById(userId)).thenReturn(Optional.empty());
		MockHttpServletRequest orphan = new MockHttpServletRequest();
		orphan.addHeader("Authorization", "Bearer orphan");
		assertThatThrownBy(() -> controller.me(orphan))
				.isInstanceOf(ResponseStatusException.class)
				.satisfies(e -> assertThat(
						((ResponseStatusException) e).getStatusCode().value()).isEqualTo(401));
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

	private static void setDisabled(UserAccount account) {
		try {
			Field field = UserAccount.class.getDeclaredField("disabled");
			field.setAccessible(true);
			field.set(account, true);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Test reflection failed", e);
		}
	}
}
