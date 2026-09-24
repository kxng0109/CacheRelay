package io.github.kxng0109.cacherelay.ledger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.kxng0109.cacherelay.auth.AuthAuditService;
import io.github.kxng0109.cacherelay.auth.MembershipStatus;
import io.github.kxng0109.cacherelay.auth.SsoMembership;
import io.github.kxng0109.cacherelay.auth.SsoMembershipRepository;
import io.github.kxng0109.cacherelay.auth.TeamRole;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.security.ratelimit.KeyManagementService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
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
 * Team dashboard scope: active members read merged member usage, outsiders
 * and inactive members read exactly like absent teams.
 */
@DisplayName("DashboardService team scope")
class DashboardServiceTeamTest {

	private static final Instant FIXED_NOW = Instant.parse("2026-09-23T12:00:00Z");

	private UsageLedgerRepository detail;

	private DashboardBucketRepository buckets;

	private SsoMembershipRepository teamMemberships;

	private KeyManagementService keys;

	private DashboardService service;

	private final UUID userId = UUID.randomUUID();

	private final UUID teamId = UUID.randomUUID();

	private final UUID peerId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		detail = mock(UsageLedgerRepository.class);
		buckets = mock(DashboardBucketRepository.class);
		teamMemberships = mock(SsoMembershipRepository.class);
		keys = mock(KeyManagementService.class);
		AuthAuditService audit = mock(AuthAuditService.class);
		EntityManager em = mock(EntityManager.class);
		PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
		Query timeout = mock(Query.class);
		when(em.createNativeQuery(any(String.class))).thenReturn(timeout);
		when(transactions.getTransaction(any(TransactionDefinition.class)))
				.thenReturn(mock(TransactionStatus.class));
		when(buckets.findByScopeTypeAndScopeKeyAndBucketDayBetween(
				any(String.class), any(String.class), any(), any())).thenReturn(List.of());
		when(buckets.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
		service = new DashboardService(detail, buckets, teamMemberships, keys, audit,
				DashboardProperties.DEFAULTS, em, transactions,
				Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
	}

	private VirtualApiKey keyFor(String ownerId, UUID owner) {
		return new VirtualApiKey(SHA256Hash.fromHex("b".repeat(64)), "gw-", ownerId, "k",
				60, 1000, null, null, null, null, null, null, null, null, false, true,
				FIXED_NOW, null, null, null, owner, false);
	}

	private OwnerModelUsageRecord detail(String owner, long requests, long cost) {
		return new OwnerModelUsageRecord(owner, "openai", "gpt-4o", requests, requests * 100L,
				requests * 50L, requests * 150L, cost, cost, cost, requests * 100L, 0L, 0L,
				requests * 100L, 0L);
	}

	@Test
	@DisplayName("active members read merged member usage")
	void memberSeesTeamUsage() {
		when(teamMemberships.findByUserIdAndTeamId(userId, teamId)).thenReturn(Optional.of(
				new SsoMembership(userId, teamId, TeamRole.MEMBER, MembershipStatus.ACTIVE)));
		when(teamMemberships.findByTeamIdAndStatus(teamId, MembershipStatus.ACTIVE))
				.thenReturn(List.of(
						new SsoMembership(userId, teamId, TeamRole.MEMBER, MembershipStatus.ACTIVE),
						new SsoMembership(peerId, teamId, TeamRole.LEAD, MembershipStatus.ACTIVE)));
		when(keys.listKeysByUser(userId)).thenReturn(List.of(keyFor("owner-1", userId)));
		when(keys.listKeysByUser(peerId)).thenReturn(List.of(keyFor("owner-2", peerId)));
		when(detail.getDetailRows(eq(Set.of("owner-1", "owner-2")), any(), any()))
				.thenReturn(List.of(detail("owner-1", 2L, 2_000L), detail("owner-2", 3L, 3_000L)));

		DashboardView view = service.getTeamView(userId, teamId,
				FIXED_NOW.minus(Duration.ofHours(6)), FIXED_NOW);

		assertThat(view.summary().totalRequests()).isEqualTo(5L);
		assertThat(view.summary().totalCostUsdMicros()).isEqualTo(5_000L);
		assertThat(view.summary().breakdownByOwner()).hasSize(2);
		assertThat(view.watermark()).isEqualTo(FIXED_NOW);
	}

	@Test
	@DisplayName("outsiders answer 404 without querying")
	void outsiderAnswers404() {
		when(teamMemberships.findByUserIdAndTeamId(userId, teamId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getTeamView(userId, teamId,
				FIXED_NOW.minus(Duration.ofHours(6)), FIXED_NOW))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		verify(detail, never()).getDetailRows(any(), any(), any());
	}

	@Test
	@DisplayName("inactive members answer 404 without querying")
	void inactiveMemberAnswers404() {
		when(teamMemberships.findByUserIdAndTeamId(userId, teamId)).thenReturn(Optional.of(
				new SsoMembership(userId, teamId, TeamRole.MEMBER, MembershipStatus.INACTIVE)));

		assertThatThrownBy(() -> service.getTeamView(userId, teamId,
				FIXED_NOW.minus(Duration.ofHours(6)), FIXED_NOW))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		verify(detail, never()).getDetailRows(any(), any(), any());
	}

	@Test
	@DisplayName("members without keys read an empty summary")
	void memberWithoutKeysReadsEmpty() {
		when(teamMemberships.findByUserIdAndTeamId(userId, teamId)).thenReturn(Optional.of(
				new SsoMembership(userId, teamId, TeamRole.MEMBER, MembershipStatus.ACTIVE)));
		when(teamMemberships.findByTeamIdAndStatus(teamId, MembershipStatus.ACTIVE))
				.thenReturn(List.of(new SsoMembership(userId, teamId, TeamRole.MEMBER,
						MembershipStatus.ACTIVE)));
		when(keys.listKeysByUser(userId)).thenReturn(List.of());

		DashboardView view = service.getTeamView(userId, teamId,
				FIXED_NOW.minus(Duration.ofHours(6)), FIXED_NOW);

		assertThat(view.summary().totalRequests()).isZero();
		verify(detail, never()).getDetailRows(any(), any(), any());
	}
}
