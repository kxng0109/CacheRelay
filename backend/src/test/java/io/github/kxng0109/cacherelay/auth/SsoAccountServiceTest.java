package io.github.kxng0109.cacherelay.auth;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SSO shadow accounts: first-sight creation, linking, disabled rejection.
 */
@DisplayName("SsoAccountService")
class SsoAccountServiceTest {

	private SsoLinkRepository links;
	private UserAccountRepository users;
	private AuthAuditService audit;
	private SsoAccountService service;

	@BeforeEach
	void setUp() {
		links = mock(SsoLinkRepository.class);
		users = mock(UserAccountRepository.class);
		audit = mock(AuthAuditService.class);
		service = new SsoAccountService(links, users, audit);
		when(audit.pseudonym(any())).thenReturn("hash");
	}

	@Test
	@DisplayName("first-sight identity creates a non-admin shadow account plus link")
	void firstSightCreatesShadow() {
		when(links.findByIssuerAndSubject("https://idp", "sub-1")).thenReturn(Optional.empty());
		when(users.findByUsernameIgnoreCase(any())).thenReturn(Optional.empty());

		Optional<UserAccount> resolved = service.resolve("https://idp", "sub-1",
				"op@example.com", "google", null, null);

		assertThat(resolved).isPresent();
		assertThat(resolved.get().getUsername()).isEqualTo("sso-op");
		assertThat(resolved.get().isAdmin()).isFalse();
		assertThat(resolved.get().getPasswordHash()).isNull();
		verify(users).saveAndFlush(any(UserAccount.class));
		verify(links).save(any(SsoLink.class));
	}

	@Test
	@DisplayName("known identity returns the linked account")
	void knownIdentityReturnsAccount() {
		UUID userId = UUID.randomUUID();
		UserAccount account = new UserAccount("op", null, "hash", false);
		set(account, "id", userId);
		when(links.findByIssuerAndSubject("https://idp", "sub-1")).thenReturn(
				Optional.of(new SsoLink(userId, "https://idp", "sub-1", "google")));
		when(users.findById(userId)).thenReturn(Optional.of(account));

		assertThat(service.resolve("https://idp", "sub-1", null, "google", null, null))
				.contains(account);
	}

	@Test
	@DisplayName("linked disabled accounts resolve to empty with a failure audit")
	void disabledLinkRejected() {
		UUID userId = UUID.randomUUID();
		UserAccount account = new UserAccount("op", null, "hash", false);
		set(account, "id", userId);
		set(account, "disabled", true);
		when(links.findByIssuerAndSubject("https://idp", "sub-1")).thenReturn(
				Optional.of(new SsoLink(userId, "https://idp", "sub-1", "google")));
		when(users.findById(userId)).thenReturn(Optional.of(account));

		assertThat(service.resolve("https://idp", "sub-1", null, "google", "ip", "req"))
				.isEmpty();
		verify(audit).record(
				eq(AuthAuditService.ACTION_SSO_LOGIN),
				any(), any(), any(), eq(AuthAuditService.OUTCOME_FAILURE), any(), any());
	}

	@Test
	@DisplayName("extreme seeds sanitize, truncate, and uniquify repeatedly")
	void extremeSeeds() {
		String longBase = "a".repeat(200);
		when(links.findByIssuerAndSubject(any(), any())).thenReturn(Optional.empty());
		when(users.findByUsernameIgnoreCase(any())).thenAnswer(invocation -> {
			String name = invocation.getArgument(0);
			boolean free = name.equals(longBase) || name.equals("---")
					|| name.equals("sso-op-3");
			return free ? Optional.empty()
					: Optional.of(new UserAccount(name, "h", null, false));
		});

		Optional<UserAccount> long_ = service.resolve("https://idp", "s-long",
				"a".repeat(250) + "@example.com", "google", null, null);
		assertThat(long_).isPresent();
		assertThat(long_.get().getUsername()).hasSizeLessThanOrEqualTo(205);

		Optional<UserAccount> symbols = service.resolve("https://idp", "s-sym", "@@@", "google",
				null, null);
		assertThat(symbols).isPresent();
		assertThat(symbols.get().getUsername()).hasSizeGreaterThanOrEqualTo(3);

		Optional<UserAccount> third = service.resolve("https://idp", "s-3", "op@example.com",
				"google", null, null);
		assertThat(third).isPresent();
		assertThat(third.get().getUsername()).isEqualTo("sso-op-3");
	}

	@Test
	@DisplayName("taken usernames uniquify with a numeric suffix")
	void usernameUniquifies() {
		when(links.findByIssuerAndSubject(any(), any())).thenReturn(Optional.empty());
		when(users.findByUsernameIgnoreCase("sso-op")).thenReturn(
				Optional.of(new UserAccount("sso-op", "h", null, false)));
		when(users.findByUsernameIgnoreCase("sso-op-2")).thenReturn(Optional.empty());

		Optional<UserAccount> resolved = service.resolve("https://idp", "sub-9",
				"op@example.com", "okta", null, null);

		assertThat(resolved).isPresent();
		assertThat(resolved.get().getUsername()).isEqualTo("sso-op-2");
	}

	@Test
	@DisplayName("links to deleted accounts resolve to empty with a failure audit")
	void linkToDeletedAccount() {
		UUID userId = UUID.randomUUID();
		when(links.findByIssuerAndSubject("https://idp", "gone")).thenReturn(
				Optional.of(new SsoLink(userId, "https://idp", "gone", "google")));
		when(users.findById(userId)).thenReturn(Optional.empty());

		assertThat(service.resolve("https://idp", "gone", null, "google", "ip", "req"))
				.isEmpty();
		verify(audit).record(
				eq(AuthAuditService.ACTION_SSO_LOGIN),
				any(), any(), any(), eq(AuthAuditService.OUTCOME_FAILURE), any(), any());
	}

	@Test
	@DisplayName("identities without email derive names from the subject")
	void nullEmailDerivesFromSubject() {
		when(links.findByIssuerAndSubject(any(), any())).thenReturn(Optional.empty());
		when(users.findByUsernameIgnoreCase(any())).thenReturn(Optional.empty());

		Optional<UserAccount> resolved = service.resolve("https://idp", "subject-42", null,
				"generic", null, null);

		assertThat(resolved).isPresent();
		assertThat(resolved.get().getUsername()).isEqualTo("subject-42");
		assertThat(resolved.get().getEmailHash()).isNull();
	}

	private static void set(Object target, String field, Object value) {
		try {
			Field declared = target.getClass().getDeclaredField(field);
			declared.setAccessible(true);
			declared.set(target, value);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Test reflection failed", e);
		}
	}
}
