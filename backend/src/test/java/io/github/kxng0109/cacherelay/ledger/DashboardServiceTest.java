package io.github.kxng0109.cacherelay.ledger;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.github.kxng0109.cacherelay.auth.AuthAuditService;
import io.github.kxng0109.cacherelay.auth.SsoMembershipRepository;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.security.ratelimit.KeyManagementService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DashboardService")
class DashboardServiceTest {

	private static final Instant FIXED_NOW = Instant.parse("2026-09-23T12:00:00Z");

	private UsageLedgerRepository detail;

	private DashboardBucketRepository buckets;

	private SsoMembershipRepository teamMemberships;

	private KeyManagementService keys;

	private AuthAuditService audit;

	private EntityManager em;

	private PlatformTransactionManager transactions;

	private ManualClock clock;

	private DashboardService service;

	@BeforeEach
	void setUp() {
		detail = mock(UsageLedgerRepository.class);
		buckets = mock(DashboardBucketRepository.class);
		teamMemberships = mock(SsoMembershipRepository.class);
		keys = mock(KeyManagementService.class);
		audit = mock(AuthAuditService.class);
		em = mock(EntityManager.class);
		transactions = mock(PlatformTransactionManager.class);
		clock = new ManualClock(FIXED_NOW);
		Query timeout = mock(Query.class);
		when(em.createNativeQuery(anyString())).thenReturn(timeout);
		when(transactions.getTransaction(any(TransactionDefinition.class)))
				.thenReturn(mock(TransactionStatus.class));
		when(buckets.findByScopeTypeAndScopeKeyAndBucketDayBetween(
				anyString(), anyString(), any(), any())).thenReturn(List.of());
		when(buckets.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
		service = new DashboardService(detail, buckets, teamMemberships, keys, audit,
				DashboardProperties.DEFAULTS, em, transactions, clock);
	}

	@Test
	@DisplayName("personal view with no keys returns an empty summary without querying")
	void personalEmptyKeysReturnsEmpty() {
		UUID userId = UUID.randomUUID();
		when(keys.listKeysByUser(userId)).thenReturn(List.of());

		DashboardView view = service.getPersonal(userId, null, null);

		assertThat(view.summary().totalRequests()).isZero();
		assertThat(view.summary().breakdownByOwner()).isEmpty();
		assertThat(view.summary().totalCostUsd()).isEqualTo(BigDecimal.valueOf(0, 6));
		verify(detail, never()).getDetailRows(any(), any(), any());
	}

	@Test
	@DisplayName("personal view merges detail grains with exact averages")
	void personalMergesDetailExactly() {
		UUID userId = UUID.randomUUID();
		when(keys.listKeysByUser(userId)).thenReturn(List.of(
				keyFor("owner-1", userId), keyFor("owner-1", userId)));
		when(detail.getDetailRows(eq(Set.of("owner-1")), any(), any())).thenReturn(List.of(
				detail("owner-1", "openai", "gpt-4o", 10L, 1000L, 500L, 1500L,
						14_000L, 13_000L, 13_000L, 1_200L, 200L, 50L, 750L, 0L),
				detail("owner-1", "anthropic", "claude", 5L, 500L, 250L, 750L,
						7_000L, 7_000L, 7_000L, 800L, 100L, 25L, 375L, 10L)));

		DashboardView view = service.getPersonal(userId,
				FIXED_NOW.minus(Duration.ofHours(6)), FIXED_NOW);

		assertThat(view.summary().totalRequests()).isEqualTo(15L);
		assertThat(view.summary().totalPromptTokens()).isEqualTo(1500L);
		assertThat(view.summary().totalCompletionTokens()).isEqualTo(750L);
		assertThat(view.summary().totalTokens()).isEqualTo(2250L);
		assertThat(view.summary().totalCostUsdMicros()).isEqualTo(21_000L);
		assertThat(view.summary().totalCostUsd()).isEqualTo(BigDecimal.valueOf(21_000, 6));
		assertThat(view.summary().averageDurationMs()).isEqualTo(2000.0 / 15.0);
		assertThat(view.summary().breakdownByOwner()).hasSize(1);
		assertThat(view.summary().breakdownByModel()).hasSize(2);
		assertThat(view.summary().breakdownByModel().getFirst().model()).isEqualTo("gpt-4o");
		assertThat(view.summary().breakdownByProvider()).hasSize(2);
		assertThat(view.watermark()).isEqualTo(FIXED_NOW);
		assertThat(view.generatedAt()).isEqualTo(FIXED_NOW);
		verify(detail, times(1)).getDetailRows(eq(Set.of("owner-1")), any(), any());
	}

	@Test
	@DisplayName("missing window defaults to the trailing seven days quantized to the minute")
	void windowDefaultsToTrailingWeek() {
		UUID userId = UUID.randomUUID();
		when(keys.listKeysByUser(userId)).thenReturn(List.of(keyFor("owner-1", userId)));

		service.getPersonal(userId, null, null);

		verify(buckets).findByScopeTypeAndScopeKeyAndBucketDayBetween(eq("PERSONAL"),
				eq(userId.toString()), eq(LocalDate.of(2026, 9, 16)), eq(LocalDate.of(2026, 9, 23)));
	}

	@Test
	@DisplayName("reversed window fails fast with 400")
	void windowRejectsReversedRange() {
		UUID userId = UUID.randomUUID();

		assertThatThrownBy(() -> service.getPersonal(userId, FIXED_NOW, FIXED_NOW.minusSeconds(1)))
				.isInstanceOf(ResponseStatusException.class)
				.satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
						.isEqualTo(HttpStatus.BAD_REQUEST));
		verify(detail, never()).getDetailRows(any(), any(), any());
	}

	@Test
	@DisplayName("window beyond ninety days fails fast with 400")
	void windowRejectsOversizeRange() {
		UUID userId = UUID.randomUUID();

		assertThatThrownBy(() -> service.getPersonal(userId,
				FIXED_NOW.minus(Duration.ofDays(91)), FIXED_NOW))
				.isInstanceOf(ResponseStatusException.class)
				.satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
						.isEqualTo(HttpStatus.BAD_REQUEST));
		verify(detail, never()).getDetailRows(any(), any(), any());
	}

	@Test
	@DisplayName("org-wide view queries all owners")
	void orgWideQueriesAllOwners() {
		when(detail.getDetailRows(any(), any(), any())).thenReturn(List.of());

		DashboardView view = service.getOrgWide(FIXED_NOW.minus(Duration.ofHours(6)), FIXED_NOW);

		assertThat(view.summary().totalRequests()).isZero();
		verify(detail).getDetailRows(isNull(), eq(FIXED_NOW.minus(Duration.ofHours(6))), eq(FIXED_NOW));
	}

	@Test
	@DisplayName("repeat view inside the TTL reuses the cached rows without querying")
	void cacheHitAvoidsSecondQuery() {
		UUID userId = UUID.randomUUID();
		when(keys.listKeysByUser(userId)).thenReturn(List.of(keyFor("owner-1", userId)));
		when(detail.getDetailRows(any(), any(), any())).thenReturn(List.of(
				detail("owner-1", "openai", "gpt-4o", 2L, 200L, 100L, 300L,
						2_000L, 2_000L, 2_000L, 400L, 0L, 0L, 200L, 0L)));

		Instant from = FIXED_NOW.minus(Duration.ofHours(6));
		service.getPersonal(userId, from, FIXED_NOW);
		DashboardView second = service.getPersonal(userId, from, FIXED_NOW);

		assertThat(second.summary().totalRequests()).isEqualTo(2L);
		verify(detail, times(1)).getDetailRows(any(), any(), any());
	}

	@Test
	@DisplayName("expired cache recomputes from the ledger")
	void cacheExpiryRecomputes() {
		UUID userId = UUID.randomUUID();
		when(keys.listKeysByUser(userId)).thenReturn(List.of(keyFor("owner-1", userId)));
		when(detail.getDetailRows(any(), any(), any())).thenReturn(List.of());

		Instant from = FIXED_NOW.minus(Duration.ofHours(6));
		service.getPersonal(userId, from, FIXED_NOW);
		clock.advance(Duration.ofMinutes(6));
		service.getPersonal(userId, from, FIXED_NOW);

		verify(detail, times(2)).getDetailRows(eq(Set.of("owner-1")), eq(from), eq(FIXED_NOW));
	}

	@Test
	@DisplayName("advancing end merges only the delta rows")
	void deltaMergeCountsOnlyNewRows() {
		UUID userId = UUID.randomUUID();
		when(keys.listKeysByUser(userId)).thenReturn(List.of(keyFor("owner-1", userId)));
		Instant from = FIXED_NOW.minus(Duration.ofHours(6));
		when(detail.getDetailRows(eq(Set.of("owner-1")), eq(from), eq(FIXED_NOW))).thenReturn(List.of(
				detail("owner-1", "openai", "gpt-4o", 2L, 200L, 100L, 300L,
						2_000L, 2_000L, 2_000L, 400L, 0L, 0L, 200L, 0L)));
		Instant later = FIXED_NOW.plus(Duration.ofMinutes(1));
		when(detail.getDetailRows(eq(Set.of("owner-1")), eq(FIXED_NOW), eq(later))).thenReturn(List.of(
				detail("owner-1", "openai", "gpt-4o", 3L, 300L, 150L, 450L,
						3_000L, 3_000L, 3_000L, 600L, 0L, 0L, 300L, 0L)));

		service.getPersonal(userId, from, FIXED_NOW);
		clock.advance(Duration.ofMinutes(1));
		DashboardView merged = service.getPersonal(userId, from, later);

		assertThat(merged.summary().totalRequests()).isEqualTo(5L);
		assertThat(merged.summary().totalTokens()).isEqualTo(750L);
		assertThat(merged.summary().averageDurationMs()).isEqualTo(1000.0 / 5.0);
		assertThat(merged.watermark()).isEqualTo(later);
		verify(detail, times(2)).getDetailRows(any(), any(), any());
	}

	@Test
	@DisplayName("regressed end returns the cached view without querying")
	void coveredViewReturnsCached() {
		UUID userId = UUID.randomUUID();
		when(keys.listKeysByUser(userId)).thenReturn(List.of(keyFor("owner-1", userId)));
		Instant from = FIXED_NOW.minus(Duration.ofHours(6));
		when(detail.getDetailRows(any(), any(), any())).thenReturn(List.of(
				detail("owner-1", "openai", "gpt-4o", 2L, 200L, 100L, 300L,
						2_000L, 2_000L, 2_000L, 400L, 0L, 0L, 200L, 0L)));

		service.getPersonal(userId, from, FIXED_NOW);
		DashboardView earlier = service.getPersonal(userId, from, FIXED_NOW.minus(Duration.ofHours(1)));

		assertThat(earlier.summary().totalRequests()).isEqualTo(2L);
		verify(detail, times(1)).getDetailRows(any(), any(), any());
	}

	@Test
	@DisplayName("settled days serve from buckets and missing days persist")
	void settledDaysUseBuckets() {
		UUID userId = UUID.randomUUID();
		when(keys.listKeysByUser(userId)).thenReturn(List.of(keyFor("owner-1", userId)));
		Instant from = Instant.parse("2026-09-20T12:00:00Z");
		DashboardBucket settled = new DashboardBucket(UUID.randomUUID(), "PERSONAL",
				userId.toString(), LocalDate.of(2026, 9, 21), "owner-1", "openai", "gpt-4o",
				4L, 400L, 200L, 600L, 4_000L, 4_000L, 4_000L, 800L, 0L, 0L, 400L, 0L,
				Instant.parse("2026-09-21T23:59:59Z"), FIXED_NOW);
		when(buckets.findByScopeTypeAndScopeKeyAndBucketDayBetween(eq("PERSONAL"),
				eq(userId.toString()), any(), any())).thenReturn(List.of(settled));
		when(detail.getDetailRows(eq(Set.of("owner-1")),
				eq(Instant.parse("2026-09-22T00:00:00Z")),
				eq(Instant.parse("2026-09-22T23:59:59.999Z")))).thenReturn(List.of(
				detail("owner-1", "openai", "gpt-4o", 1L, 100L, 50L, 150L,
						1_000L, 1_000L, 1_000L, 200L, 0L, 0L, 100L, 0L)));
		when(detail.getDetailRows(eq(Set.of("owner-1")),
				eq(Instant.parse("2026-09-20T12:00:00Z")),
				eq(Instant.parse("2026-09-21T00:00:00Z")))).thenReturn(List.of());
		when(detail.getDetailRows(eq(Set.of("owner-1")),
				eq(Instant.parse("2026-09-23T00:00:00Z")), eq(FIXED_NOW))).thenReturn(List.of());

		DashboardView view = service.getPersonal(userId, from, FIXED_NOW);

		assertThat(view.summary().totalRequests()).isEqualTo(5L);
		verify(buckets).saveAll(any());
	}

	@Test
	@DisplayName("bucket write race falls back to the stored rows")
	void bucketWriteRaceFallsBack() {
		UUID userId = UUID.randomUUID();
		when(keys.listKeysByUser(userId)).thenReturn(List.of(keyFor("owner-1", userId)));
		Instant from = Instant.parse("2026-09-20T12:00:00Z");
		DashboardBucket winner = new DashboardBucket(UUID.randomUUID(), "PERSONAL",
				userId.toString(), LocalDate.of(2026, 9, 21), "owner-1", "openai", "gpt-4o",
				4L, 400L, 200L, 600L, 4_000L, 4_000L, 4_000L, 800L, 0L, 0L, 400L, 0L,
				Instant.parse("2026-09-21T23:59:59Z"), FIXED_NOW);
		when(buckets.findByScopeTypeAndScopeKeyAndBucketDayBetween(eq("PERSONAL"),
				eq(userId.toString()), any(), any()))
				.thenReturn(List.of(), List.of(winner));
		when(buckets.saveAll(any()))
				.thenThrow(new DataIntegrityViolationException("race"))
				.thenReturn(List.of());
		when(detail.getDetailRows(any(), any(), any())).thenReturn(List.of());

		DashboardView view = service.getPersonal(userId, from, FIXED_NOW);

		assertThat(view.summary().totalRequests()).isEqualTo(4L);
		verify(buckets, times(2)).findByScopeTypeAndScopeKeyAndBucketDayBetween(
				eq("PERSONAL"), eq(userId.toString()), any(), any());
	}

	@Test
	@DisplayName("views beyond the per-minute allowance answer 429")
	void rateLimitRejectsExcessViews() {
		DashboardService limited = new DashboardService(detail, buckets, teamMemberships, keys, audit,
				new DashboardProperties(5, 5, 7, 10, 2), em, transactions, clock);
		UUID userId = UUID.randomUUID();
		when(keys.listKeysByUser(userId)).thenReturn(List.of());
		Instant from = FIXED_NOW.minus(Duration.ofDays(1));

		limited.getPersonal(userId, from, FIXED_NOW);
		limited.getPersonal(userId, from, FIXED_NOW);

		assertThatThrownBy(() -> limited.getPersonal(userId, from, FIXED_NOW))
				.isInstanceOf(ResponseStatusException.class)
				.satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
						.isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
	}

	@Test
	@DisplayName("admin drill-down audits the access and resolves the target")
	void adminDrillDownAuditsAccess() {
		UUID targetId = UUID.randomUUID();
		when(keys.listKeysByUser(targetId)).thenReturn(List.of(keyFor("owner-9", targetId)));
		when(detail.getDetailRows(eq(Set.of("owner-9")), any(), any())).thenReturn(List.of(
				detail("owner-9", "openai", "gpt-4o", 1L, 100L, 50L, 150L,
						1_000L, 1_000L, 1_000L, 200L, 0L, 0L, 100L, 0L)));

		DashboardView view = service.getUserAsAdmin("admin-1", targetId,
				FIXED_NOW.minus(Duration.ofHours(6)), FIXED_NOW, "10.0.0.1", "req-1");

		assertThat(view.summary().totalRequests()).isEqualTo(1L);
		verify(audit).record(eq(AuthAuditService.ACTION_DASHBOARD_VIEW),
				eq(AuthAuditService.SEVERITY_INFO), eq("admin-1"),
				eq("/v1/admin/ledger/user/" + targetId + "/summary"),
				eq(AuthAuditService.OUTCOME_SUCCESS), eq("10.0.0.1"), eq("req-1"));
	}

	@Test
	@DisplayName("dashboard scans apply the configured statement timeout")
	void statementTimeoutApplied() {
		UUID userId = UUID.randomUUID();
		when(keys.listKeysByUser(userId)).thenReturn(List.of(keyFor("owner-1", userId)));
		when(detail.getDetailRows(any(), any(), any())).thenReturn(List.of());

		service.getPersonal(userId, FIXED_NOW.minus(Duration.ofHours(6)), FIXED_NOW);

		verify(em, atLeastOnce()).createNativeQuery("SET LOCAL statement_timeout = '10s'");
	}

	@Test
	@DisplayName("empty windows report zero averages instead of dividing")
	void emptyWindowReportsZeroAverage() {
		when(detail.getDetailRows(any(), any(), any())).thenReturn(List.of());

		DashboardView view = service.getOrgWide(FIXED_NOW.minus(Duration.ofHours(6)), FIXED_NOW);

		assertThat(view.summary().averageDurationMs()).isZero();
		assertThat(view.summary().breakdownByOwner()).isEmpty();
		assertThat(view.summary().breakdownByModel()).isEmpty();
		assertThat(view.summary().breakdownByProvider()).isEmpty();
	}

	@Test
	@DisplayName("concurrent identical views compute once")
	void singleFlightCoalescesViews() throws Exception {		UUID userId = UUID.randomUUID();
		when(keys.listKeysByUser(userId)).thenReturn(List.of(keyFor("owner-1", userId)));
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		when(detail.getDetailRows(any(), any(), any())).thenAnswer(invocation -> {
			entered.countDown();
			assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
			return List.of();
		});
		Instant from = FIXED_NOW.minus(Duration.ofHours(6));

		ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
		try {
			var first = pool.submit(() -> service.getPersonal(userId, from, FIXED_NOW));
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			var second = pool.submit(() -> service.getPersonal(userId, from, FIXED_NOW));
			release.countDown();
			first.get(10, TimeUnit.SECONDS);
			second.get(10, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}

		verify(detail, times(1)).getDetailRows(any(), any(), any());
	}

	@Test
	@DisplayName("joiners observe the original failure instead of a wrapper")
	void concurrentFailurePropagatesOriginal() throws Exception {
		UUID userId = UUID.randomUUID();
		when(keys.listKeysByUser(userId)).thenReturn(List.of(keyFor("owner-1", userId)));
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		RuntimeException failure = new RuntimeException("ledger down");
		when(detail.getDetailRows(any(), any(), any())).thenAnswer(invocation -> {
			entered.countDown();
			assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
			throw failure;
		});
		Instant from = FIXED_NOW.minus(Duration.ofHours(6));

		ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
		try {
			var first = pool.submit(() -> service.getPersonal(userId, from, FIXED_NOW));
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			var second = pool.submit(() -> service.getPersonal(userId, from, FIXED_NOW));
			Thread.sleep(500);
			release.countDown();
			assertThatThrownBy(first::get).hasCause(failure);
			assertThatThrownBy(second::get).hasCause(failure);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	@DisplayName("joiners wrap non-runtime failures instead of leaking wrappers")
	void concurrentErrorWrapsAsIllegalState() throws Exception {
		UUID userId = UUID.randomUUID();
		when(keys.listKeysByUser(userId)).thenReturn(List.of(keyFor("owner-1", userId)));
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		when(detail.getDetailRows(any(), any(), any())).thenAnswer(invocation -> {
			entered.countDown();
			assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
			throw new AssertionError("corrupt");
		});
		Instant from = FIXED_NOW.minus(Duration.ofHours(6));

		ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
		try {
			var first = pool.submit(() -> service.getPersonal(userId, from, FIXED_NOW));
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
			var second = pool.submit(() -> service.getPersonal(userId, from, FIXED_NOW));
			Thread.sleep(500);
			release.countDown();
			assertThatThrownBy(first::get).hasCauseInstanceOf(AssertionError.class);
			assertThatThrownBy(second::get).hasCauseInstanceOf(IllegalStateException.class);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	@DisplayName("today stays live even when fully inside the window")
	void todayFullyInsideStaysLive() {
		UUID userId = UUID.randomUUID();
		when(keys.listKeysByUser(userId)).thenReturn(List.of(keyFor("owner-1", userId)));
		when(detail.getDetailRows(any(), any(), any())).thenReturn(List.of());

		DashboardView view = service.getPersonal(userId,
				Instant.parse("2026-09-22T00:00:00Z"), Instant.parse("2026-09-24T00:00:00Z"));

		assertThat(view.summary().totalRequests()).isZero();
		verify(detail, times(1)).getDetailRows(eq(Set.of("owner-1")),
				eq(Instant.parse("2026-09-22T00:00:00Z")),
				eq(Instant.parse("2026-09-22T23:59:59.999Z")));
		verify(detail, never()).getDetailRows(any(),
				eq(Instant.parse("2026-09-22T00:00:00Z")),
				eq(Instant.parse("2026-09-22T00:00:00Z")));
		verify(detail, times(1)).getDetailRows(eq(Set.of("owner-1")),
				eq(Instant.parse("2026-09-23T00:00:00Z")),
				eq(Instant.parse("2026-09-24T00:00:00Z")));
	}

	@Test
	@DisplayName("window ending at a settled edge skips the tail scan")
	void windowEndingAtSettledEdgeSkipsTail() {
		UUID userId = UUID.randomUUID();
		when(keys.listKeysByUser(userId)).thenReturn(List.of(keyFor("owner-1", userId)));
		when(detail.getDetailRows(any(), any(), any())).thenReturn(List.of());

		DashboardView view = service.getPersonal(userId,
				Instant.parse("2026-09-20T12:00:00Z"), Instant.parse("2026-09-23T00:00:00Z"));

		assertThat(view.summary().totalRequests()).isZero();
		verify(detail, times(1)).getDetailRows(eq(Set.of("owner-1")),
				eq(Instant.parse("2026-09-21T00:00:00Z")),
				eq(Instant.parse("2026-09-21T23:59:59.999Z")));
		verify(detail, times(1)).getDetailRows(eq(Set.of("owner-1")),
				eq(Instant.parse("2026-09-22T00:00:00Z")),
				eq(Instant.parse("2026-09-22T23:59:59.999Z")));
		verify(detail, never()).getDetailRows(any(),
				eq(Instant.parse("2026-09-23T00:00:00Z")),
				eq(Instant.parse("2026-09-23T00:00:00Z")));
	}

	@Test
	@DisplayName("null and blank key owners never reach the ledger")
	void nullAndBlankOwnersExcluded() {
		UUID userId = UUID.randomUUID();
		when(keys.listKeysByUser(userId)).thenReturn(List.of(
				keyFor("owner-1", userId), keyFor(null, userId), keyFor("   ", userId)));
		when(detail.getDetailRows(any(), any(), any())).thenReturn(List.of());

		service.getPersonal(userId,
				FIXED_NOW.minus(Duration.ofHours(6)), FIXED_NOW);

		verify(detail, times(1)).getDetailRows(eq(Set.of("owner-1")), any(), any());
	}

	private static VirtualApiKey keyFor(String ownerId, UUID userId) {
		return new VirtualApiKey(SHA256Hash.fromHex("a".repeat(64)), "gw-", ownerId, "k",
				60, 1000, null, null, null, null, null, null, null, null, false, true,
				FIXED_NOW, null, null, null, userId, false);
	}

	private static OwnerModelUsageRecord detail(String owner, String provider, String model,
			long requests, long prompt, long completion, long total, long cost, long billed,
			long effective, long durationSum, long read, long write, long uncached, long reasoning) {
		return new OwnerModelUsageRecord(owner, provider, model, requests, prompt, completion,
				total, cost, billed, effective, durationSum, read, write, uncached, reasoning);
	}

	private static final class ManualClock extends Clock {
		private final AtomicReference<Instant> now;

		ManualClock(Instant now) {
			this.now = new AtomicReference<>(now);
		}

		void advance(Duration step) {
			now.updateAndGet(current -> current.plus(step));
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now.get();
		}
	}
}
