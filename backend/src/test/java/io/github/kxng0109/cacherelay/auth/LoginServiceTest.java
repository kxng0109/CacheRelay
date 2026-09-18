package io.github.kxng0109.cacherelay.auth;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for local login: success, indistinguishable rejections, lockout.
 */
@DisplayName("LoginService")
class LoginServiceTest {

	private UserAccountRepository users;
	private PasswordEncoder passwords;
	private JwtService jwt;
	private RefreshService refresh;
	private AuthAuditService audit;
	private LoginService service;

	@BeforeEach
	void setUp() {
		users = mock(UserAccountRepository.class);
		passwords = mock(PasswordEncoder.class);
		jwt = mock(JwtService.class);
		refresh = mock(RefreshService.class);
		audit = mock(AuthAuditService.class);
		service = new LoginService(users, passwords, jwt, refresh, AuthProperties.defaults(),
				audit);
		when(audit.pseudonym(any())).thenReturn("actor-hash");
		when(audit.recentFailures(any(), any(), any())).thenReturn(0L);
	}

	@Test
	@DisplayName("valid credentials open a session and audit success")
	void validCredentialsSucceed() {
		UUID userId = UUID.randomUUID();
		UserAccount account = account(userId, "op", "hash", false);
		when(users.findByUsernameIgnoreCase("op")).thenReturn(Optional.of(account));
		when(passwords.matches("secret-password-1", "hash")).thenReturn(true);
		when(jwt.issueAccessToken(eq(userId), eq("op"), eq(false), anyLong()))
				.thenReturn("access");
		when(refresh.mint(userId, false)).thenReturn("session");

		LoginService.LoginResult result = service.login("op", "secret-password-1", "ip", "req");

		assertThat(result).isInstanceOf(LoginService.LoginSuccess.class);
		LoginService.LoginSuccess success = (LoginService.LoginSuccess) result;
		assertThat(success.accessToken()).isEqualTo("access");
		assertThat(success.refreshToken()).isEqualTo("session");
		verify(audit).record(eq(AuthAuditService.ACTION_LOCAL_LOGIN),
				eq(AuthAuditService.SEVERITY_INFO), eq("op"), any(), eq("SUCCESS"), any(), any());
	}

	@Test
	@DisplayName("unknown user, bad password, disabled, and SSO-only accounts all reject identically")
	void rejectionsIndistinguishable() {
		UUID userId = UUID.randomUUID();
		UserAccount bad = account(userId, "op", "hash", false);
		UserAccount disabled = account(UUID.randomUUID(), "off", "hash", false);
		setDisabled(disabled);
		UserAccount ssoOnly = account(UUID.randomUUID(), "sso", null, false);
		when(users.findByUsernameIgnoreCase("ghost")).thenReturn(Optional.empty());
		when(users.findByUsernameIgnoreCase("op")).thenReturn(Optional.of(bad));
		when(users.findByUsernameIgnoreCase("off")).thenReturn(Optional.of(disabled));
		when(users.findByUsernameIgnoreCase("sso")).thenReturn(Optional.of(ssoOnly));
		when(passwords.matches(any(), any())).thenReturn(false);

		assertThat(service.login("ghost", "x", null, null))
				.isInstanceOf(LoginService.LoginRejected.class);
		assertThat(service.login("op", "wrong", null, null))
				.isInstanceOf(LoginService.LoginRejected.class);
		assertThat(service.login("off", "x", null, null))
				.isInstanceOf(LoginService.LoginRejected.class);
		assertThat(service.login("sso", "x", null, null))
				.isInstanceOf(LoginService.LoginRejected.class);
		verifyNoInteractions(jwt, refresh);
	}

	@Test
	@DisplayName("lockout after max failures audits critical without touching accounts")
	void lockoutAfterMaxFailures() {
		when(audit.recentFailures(any(), any(), any())).thenReturn(5L);

		assertThat(service.login("op", "x", "ip", "req"))
				.isInstanceOf(LoginService.LoginRejected.class);
		verify(audit).record(eq(AuthAuditService.ACTION_LOCKOUT),
				eq(AuthAuditService.SEVERITY_CRITICAL), eq("op"), any(), eq("FAILURE"), any(),
				any());
		verifyNoInteractions(users, passwords, jwt, refresh);
	}

	private UserAccount account(UUID id, String username, String hash, boolean admin) {
		UserAccount account = new UserAccount(username, hash, null, admin);
		try {
			Field field = UserAccount.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(account, id);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Test reflection failed", e);
		}
		return account;
	}

	private void setDisabled(UserAccount account) {
		try {
			Field field = UserAccount.class.getDeclaredField("disabled");
			field.setAccessible(true);
			field.set(account, true);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Test reflection failed", e);
		}
	}
}
