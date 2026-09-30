package io.github.kxng0109.cacherelay.auth;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.kxng0109.cacherelay.notify.ChannelResult;
import io.github.kxng0109.cacherelay.notify.GraphEmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
				mail, mock(TeamManagementService.class));
		when(audit.pseudonym(any())).thenReturn("hash");
		when(passwords.encode(any())).thenReturn("bcrypt");
	}

	@Test
	@DisplayName("create returns a copyable link without email when no address is given")
	void createLinkOnly() {
		InviteService.CreatedInvite created =
				service.create(null, null, true, null, "http://localhost:8080", null);

		assertThat(created.link()).startsWith("http://localhost:8080/redeem?token=");
		assertThat(created.emailed()).isFalse();
		verify(invites).save(any(InviteToken.class));
	}

	@Test
	@DisplayName("create emails the link when the channel sends, else link-only")
	void createConditionalEmail() {
		when(mail.sendDirect(any(), any(), any())).thenReturn(ChannelResult.SENT);

		InviteService.CreatedInvite sent =
				service.create(UUID.randomUUID(), "op@example.com", false, null, "http://h", null);

		assertThat(sent.emailed()).isTrue();

		when(mail.sendDirect(any(), any(), any())).thenReturn(ChannelResult.SKIPPED);
		InviteService.CreatedInvite skipped =
				service.create(UUID.randomUUID(), "op@example.com", false, null, "http://h", null);

		assertThat(skipped.emailed()).isFalse();
		assertThat(skipped.link()).contains("/redeem?token=");
	}

	@Test
	@DisplayName("create prefers the configured invite base over the request base")
	void createPrefersConfiguredBase() {
		service = new InviteService(invites, users, withInviteBase("https://app.example.com/"),
				passwords, audit, mail, mock(TeamManagementService.class));

		InviteService.CreatedInvite created =
				service.create(null, null, true, null, "http://backend:8080", null);

		assertThat(created.link()).startsWith("https://app.example.com/redeem?token=");
		assertThat(created.emailed()).isFalse();
	}

	@Test
	@DisplayName("create trims trailing slashes but keeps a configured sub-path")
	void createNormalizesConfiguredBase() {
		service = new InviteService(invites, users, withInviteBase("https://app.example.com///"),
				passwords, audit, mail, mock(TeamManagementService.class));

		assertThat(service.create(null, null, true, null, "http://backend:8080", null).link())
				.startsWith("https://app.example.com/redeem?token=");

		service = new InviteService(invites, users, withInviteBase("https://app.example.com/cr/"),
				passwords, audit, mail, mock(TeamManagementService.class));

		assertThat(service.create(null, null, true, null, "http://backend:8080", null).link())
				.startsWith("https://app.example.com/cr/redeem?token=");
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

	@Test
	@DisplayName("create persists team placement after validation")
	void createPersistsPlacement() {
		UUID teamId = UUID.randomUUID();
		SsoTeam team = new SsoTeam(UUID.randomUUID(), "", "local:eng", "Eng");
		TeamManagementService placement = mock(TeamManagementService.class);
		when(placement.requirePlaceableTeam(eq(teamId))).thenReturn(team);
		InviteService placed = new InviteService(invites, users, AuthProperties.defaults(),
				passwords, audit, mail, placement);

		placed.create(null, null, false, teamId, "http://h", null);

		ArgumentCaptor<InviteToken> saved = ArgumentCaptor.forClass(InviteToken.class);
		verify(invites).save(saved.capture());
		assertThat(saved.getValue().getTeamId()).isEqualTo(teamId);
	}

	@Test
	@DisplayName("create rejects unplaceable teams without persisting")
	void createRejectsUnplaceable() {
		UUID teamId = UUID.randomUUID();
		TeamManagementService placement = mock(TeamManagementService.class);
		when(placement.requirePlaceableTeam(eq(teamId)))
				.thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "team not found"));
		InviteService placed = new InviteService(invites, users, AuthProperties.defaults(),
				passwords, audit, mail, placement);

		assertThatThrownBy(() -> placed.create(null, null, false, teamId, "http://h", null))
				.isInstanceOf(ResponseStatusException.class);
		verify(invites, never()).save(any(InviteToken.class));
	}

	@Test
	@DisplayName("redemption places the account into the invite team")
	void redeemPlacesMembership() {
		UUID teamId = UUID.randomUUID();
		SsoTeam team = new SsoTeam(UUID.randomUUID(), "", "local:eng", "Eng");
		set(team, "id", teamId);
		InviteToken placed = new InviteToken(RefreshService.sha256Hex("token"), null, false,
				null, Instant.now().plusSeconds(3600), teamId);
		TeamManagementService placement = mock(TeamManagementService.class);
		when(placement.requirePlaceableTeam(eq(teamId))).thenReturn(team);
		SsoMembership seated = new SsoMembership(UUID.randomUUID(), teamId, TeamRole.MEMBER,
				MembershipStatus.ACTIVE);
		when(placement.assignMember(eq(teamId), any(UUID.class), eq(TeamRole.MEMBER)))
				.thenReturn(seated);
		InviteService service = new InviteService(invites, users, AuthProperties.defaults(),
				passwords, audit, mail, placement);
		when(invites.findByTokenHash(any())).thenReturn(Optional.of(placed));
		when(users.findByUsernameIgnoreCase(any())).thenReturn(Optional.empty());
		when(users.count()).thenReturn(2L);
		when(invites.consume(any(), any(), any())).thenReturn(1);

		InviteService.RedeemResult result =
				service.redeem("token", "fresh", "password-12345", null, null);

		assertThat(result).isInstanceOf(InviteService.RedeemResult.Redeemed.class);
		UUID accountId = ((InviteService.RedeemResult.Redeemed) result).account().getId();
		verify(placement).assignMember(eq(teamId), eq(accountId), eq(TeamRole.MEMBER));
	}

	@Test
	@DisplayName("dangling placement resolves to gone without accounts")
	void redeemDanglingPlacementGone() {
		UUID teamId = UUID.randomUUID();
		InviteToken placed = new InviteToken(RefreshService.sha256Hex("token"), null, false,
				null, Instant.now().plusSeconds(3600), teamId);
		TeamManagementService placement = mock(TeamManagementService.class);
		when(placement.requirePlaceableTeam(eq(teamId)))
				.thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "team not found"));
		InviteService service = new InviteService(invites, users, AuthProperties.defaults(),
				passwords, audit, mail, placement);
		when(invites.findByTokenHash(any())).thenReturn(Optional.of(placed));
		when(users.findByUsernameIgnoreCase(any())).thenReturn(Optional.empty());

		assertThat(service.redeem("token", "fresh", "password-12345", null, null))
				.isInstanceOf(InviteService.RedeemResult.Gone.class);
		verify(users, never()).saveAndFlush(any(UserAccount.class));
	}

	private InviteToken invite(boolean admin) {
		return new InviteToken(RefreshService.sha256Hex("token"), null, admin, null,
				Instant.now().plusSeconds(3600));
	}

	private static AuthProperties withInviteBase(String base) {
		return new AuthProperties(null, null, null, null, null, null, null, null, null, null,
				null, 180, Map.of(), 5, null, null, base);
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
