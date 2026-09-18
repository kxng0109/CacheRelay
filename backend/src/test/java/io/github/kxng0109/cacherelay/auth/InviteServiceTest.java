package io.github.kxng0109.cacherelay.auth;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import io.github.kxng0109.cacherelay.notify.ChannelResult;
import io.github.kxng0109.cacherelay.notify.GraphEmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for invites: link creation, conditional email, atomic redemption.
 */
@DisplayName("InviteService")
class InviteServiceTest {

	private InviteTokenRepository invites;
	private UserAccountRepository users;
	private PasswordEncoder passwords;
	private AuthAuditService audit;
	private GraphEmailSender mail;
	private InviteService service;

	@BeforeEach
	void setUp() {
		invites = mock(InviteTokenRepository.class);
		users = mock(UserAccountRepository.class);
		passwords = mock(PasswordEncoder.class);
		audit = mock(AuthAuditService.class);
		mail = mock(GraphEmailSender.class);
		service = new InviteService(invites, users, AuthProperties.defaults(), passwords, audit,
				mail);
		when(audit.pseudonym(any())).thenReturn("hash");
		when(passwords.encode(any())).thenReturn("bcrypt");
	}

	@Test
	@DisplayName("create returns a copyable link without email when no address is given")
	void createLinkOnly() {
		InviteService.CreatedInvite created =
				service.create(null, null, true, "http://localhost:8080", null);

		assertThat(created.link()).startsWith("http://localhost:8080/redeem?token=");
		assertThat(created.emailed()).isFalse();
		verify(invites).save(any(InviteToken.class));
	}

	@Test
	@DisplayName("create emails the link when the channel sends, else link-only")
	void createConditionalEmail() {
		when(mail.sendDirect(any(), any(), any())).thenReturn(ChannelResult.SENT);

		InviteService.CreatedInvite sent =
				service.create(UUID.randomUUID(), "op@example.com", false, "http://h", null);

		assertThat(sent.emailed()).isTrue();

		when(mail.sendDirect(any(), any(), any())).thenReturn(ChannelResult.SKIPPED);
		InviteService.CreatedInvite skipped =
				service.create(UUID.randomUUID(), "op@example.com", false, "http://h", null);

		assertThat(skipped.emailed()).isFalse();
		assertThat(skipped.link()).contains("/redeem?token=");
	}

	@Test
	@DisplayName("redeem of unknown, consumed, or expired tokens resolves without accounts")
	void redeemDeadTokens() {
		InviteToken consumed = invite(false);
		set(consumed, "consumedAt", Instant.now());
		InviteToken expired = invite(false);
		set(expired, "expiresAt", Instant.now().minusSeconds(10));
		when(invites.findByTokenHash(RefreshService.sha256Hex("missing")))
				.thenReturn(Optional.empty());
		when(invites.findByTokenHash(RefreshService.sha256Hex("consumed")))
				.thenReturn(Optional.of(consumed));
		when(invites.findByTokenHash(RefreshService.sha256Hex("expired")))
				.thenReturn(Optional.of(expired));

		assertThat(service.redeem("missing", "u", "password-12345", null, null))
				.isInstanceOf(InviteService.RedeemResult.Missing.class);
		assertThat(service.redeem("consumed", "u", "password-12345", null, null))
				.isInstanceOf(InviteService.RedeemResult.Gone.class);
		assertThat(service.redeem("expired", "u", "password-12345", null, null))
				.isInstanceOf(InviteService.RedeemResult.Gone.class);
	}

	@Test
	@DisplayName("first redemption bootstraps an admin and audits bootstrap")
	void redeemBootstrapsFirstAdmin() {
		when(invites.findByTokenHash(any())).thenReturn(Optional.of(invite(false)));
		when(users.findByUsernameIgnoreCase(any())).thenReturn(Optional.empty());
		when(users.count()).thenReturn(0L);
		when(invites.consume(any(), any(), any())).thenReturn(1);

		InviteService.RedeemResult result =
				service.redeem("token", "root", "password-12345", "ip", "req");

		assertThat(result).isInstanceOf(InviteService.RedeemResult.Redeemed.class);
		InviteService.RedeemResult.Redeemed redeemed =
				(InviteService.RedeemResult.Redeemed) result;
		assertThat(redeemed.account().isAdmin()).isTrue();
		assertThat(redeemed.bootstrapped()).isTrue();
		verify(audit).record(
				eq(AuthAuditService.ACTION_BOOTSTRAP_CONSUMED),
				any(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("later redemptions honor the invite privilege and taken names resolve to gone")
	void redeemLaterInvite() {
		when(invites.findByTokenHash(any())).thenReturn(Optional.of(invite(true)));
		when(users.findByUsernameIgnoreCase("fresh")).thenReturn(Optional.empty());
		when(users.findByUsernameIgnoreCase("taken")).thenReturn(
				Optional.of(new UserAccount("taken", "h", null, false)));
		when(users.count()).thenReturn(2L);
		when(invites.consume(any(), any(), any())).thenReturn(1);

		InviteService.RedeemResult ok =
				service.redeem("token", "fresh", "password-12345", null, null);
		assertThat(ok).isInstanceOf(InviteService.RedeemResult.Redeemed.class);
		assertThat(((InviteService.RedeemResult.Redeemed) ok).account().isAdmin()).isTrue();
		assertThat(((InviteService.RedeemResult.Redeemed) ok).bootstrapped()).isFalse();

		assertThat(service.redeem("token", "taken", "password-12345", null, null))
				.isInstanceOf(InviteService.RedeemResult.Gone.class);
	}

	@Test
	@DisplayName("concurrent consumption loss resolves to gone")
	void redeemConcurrentLoss() {
		when(invites.findByTokenHash(any())).thenReturn(Optional.of(invite(false)));
		when(users.findByUsernameIgnoreCase(any())).thenReturn(Optional.empty());
		when(users.count()).thenReturn(3L);
		when(invites.consume(any(), any(), any())).thenReturn(0);

		assertThat(service.redeem("token", "fresh", "password-12345", null, null))
				.isInstanceOf(InviteService.RedeemResult.Gone.class);
	}

	private InviteToken invite(boolean admin) {
		return new InviteToken(RefreshService.sha256Hex("token"), null, admin, null,
				Instant.now().plusSeconds(3600));
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
