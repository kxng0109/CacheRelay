package io.github.kxng0109.cacherelay.ledger;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.kxng0109.cacherelay.admin.dto.LedgerSummaryResponse;
import io.github.kxng0109.cacherelay.admin.dto.ModelUsageSummary;
import io.github.kxng0109.cacherelay.admin.dto.OwnerUsageSummary;
import io.github.kxng0109.cacherelay.admin.dto.ProviderUsageSummary;
import io.github.kxng0109.cacherelay.auth.AuthAuditService;
import io.github.kxng0109.cacherelay.auth.MembershipStatus;
import io.github.kxng0109.cacherelay.auth.SsoMembership;
import io.github.kxng0109.cacherelay.auth.SsoMembershipRepository;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.security.ratelimit.KeyManagementService;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * On-demand usage dashboards: personal, org-wide, and admin drill-down views
 * computed only when viewed.
 *
 * <p>Nothing precomputes in the background. Each view resolves a scope to
 * ledger owner ids, serves settled days from lazily-persisted daily buckets,
 * scans only the open tail live, and caches the merged result for the
 * configured TTL with exact watermark-delta merges. Averages always recompute
 * from merged duration sums, never from averaged averages. Concurrent
 * identical views coalesce onto one computation. Dashboard scans run under a
 * statement timeout and per-user views are rate-limited, so refresh-spam can
 * never steal the proxy path's CPU.</p>
 *
 * <p>Team views resolve active members to their owned keys; non-members read
 * exactly like absent teams. The scope-type vocabulary reserves future
 * grains.</p>
 */
@Service
public class DashboardService {

	/** Personal scope grain. */
	public static final String SCOPE_PERSONAL = "PERSONAL";

	/** Org-wide scope grain. */
	public static final String SCOPE_ORG = "ORG";

	/** Scope key for org-wide views. */
	public static final String GLOBAL_KEY = "global";

	/** Team scope grain. */
	public static final String SCOPE_TEAM = "TEAM";

	/**
	 * Freshness header carrying when a dashboard view was computed.
	 */
	public static final String HEADER_GENERATED_AT = "X-Dashboard-Generated-At";

	/**
	 * Freshness header carrying the newest ledger row a view counted.
	 */
	public static final String HEADER_WATERMARK = "X-Dashboard-Watermark";

	private static final Duration MAX_WINDOW = UsageLedgerService.MAX_QUERY_WINDOW;

	private final UsageLedgerRepository detail;

	private final DashboardBucketRepository buckets;

	private final SsoMembershipRepository teamMemberships;

	private final KeyManagementService keys;

	private final AuthAuditService audit;

	private final DashboardProperties properties;

	private final EntityManager entityManager;

	private final TransactionTemplate readTransactions;

	private final Clock clock;

	private final Cache<String, CachedView> views;

	private final Cache<String, AtomicLong> viewCounts;

	private final ConcurrentHashMap<String, CompletableFuture<DashboardView>> flights = new ConcurrentHashMap<>();

	/**
	 * @param detail       ledger detail-grain queries, never {@code null}
	 * @param buckets      lazy daily bucket persistence, never {@code null}
	 * @param memberships  team membership reads, never {@code null}
	 * @param keys         key ownership resolution, never {@code null}
	 * @param audit        drill-down audit log, never {@code null}
	 * @param properties   dashboard ceilings, never {@code null}
	 * @param entityManager JPA access for the statement timeout, never {@code null}
	 * @param transactions transaction manager for read fencing, never {@code null}
	 * @param clock        clock for TTL and watermarks, never {@code null}
	 */
	public DashboardService(
			UsageLedgerRepository detail,
			DashboardBucketRepository buckets,
			SsoMembershipRepository memberships,
			KeyManagementService keys,
			AuthAuditService audit,
			DashboardProperties properties,
			EntityManager entityManager,
			PlatformTransactionManager transactions,
			Clock clock
	) {
		this.detail = detail;
		this.buckets = buckets;
		this.teamMemberships = memberships;
		this.keys = keys;
		this.audit = audit;
		this.properties = properties;
		this.entityManager = entityManager;
		this.readTransactions = new TransactionTemplate(transactions);
		this.readTransactions.setReadOnly(true);
		this.clock = clock;
		this.views = Caffeine.newBuilder()
				.expireAfterWrite(properties.resultTtlMinutes(), TimeUnit.MINUTES)
				.maximumSize(10_000)
				.build();
		this.viewCounts = Caffeine.newBuilder()
				.expireAfterWrite(1, TimeUnit.MINUTES)
				.maximumSize(10_000)
				.build();
	}

	/**
	 * Returns the caller's personal usage summary over their owned keys.
	 *
	 * @param userId session account id, never {@code null}
	 * @param from   window start inclusive, or {@code null} for the trailing default
	 * @param to     window end inclusive, or {@code null} for now
	 * @return personal dashboard view
	 */
	public DashboardView getPersonal(UUID userId, Instant from, Instant to) {
		Window window = resolveWindow(from, to);
		rateLimit(SCOPE_PERSONAL, userId.toString());
		Set<String> owners = distinctOwnerIds(keys.listKeysByUser(userId));
		return summarize(SCOPE_PERSONAL, userId.toString(), owners, window);
	}

	/**
	 * Returns the org-wide usage summary across all owners.
	 *
	 * @param from window start inclusive, or {@code null} for the trailing default
	 * @param to   window end inclusive, or {@code null} for now
	 * @return org-wide dashboard view
	 */
	public DashboardView getOrgWide(Instant from, Instant to) {
		Window window = resolveWindow(from, to);
		rateLimit(SCOPE_ORG, GLOBAL_KEY);
		return summarize(SCOPE_ORG, GLOBAL_KEY, null, window);
	}

	/**
	 * Returns one team's usage summary across its active members' owned keys.
	 * Non-members read exactly like absent teams.
	 *
	 * @param userId session account id, never {@code null}
	 * @param teamId viewed team, never {@code null}
	 * @param from   window start inclusive, or {@code null} for the trailing default
	 * @param to     window end inclusive, or {@code null} for now
	 * @return team dashboard view
	 */
	public DashboardView getTeamView(UUID userId, UUID teamId, Instant from, Instant to) {
		Window window = resolveWindow(from, to);
		rateLimit(SCOPE_TEAM, userId.toString());
		SsoMembership membership =
				teamMemberships.findByUserIdAndTeamId(userId, teamId).orElse(null);
		if (membership == null || membership.getStatus() != MembershipStatus.ACTIVE) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "team not found");
		}
		Set<String> owners = new HashSet<>();
		for (SsoMembership member : teamMemberships.findByTeamIdAndStatus(teamId,
				MembershipStatus.ACTIVE)) {
			owners.addAll(distinctOwnerIds(keys.listKeysByUser(member.getUserId())));
		}
		return summarize(SCOPE_TEAM, teamId.toString(), owners, window);
	}

	/**
	 * Returns one user's usage summary for an admin, auditing the access.
	 *
	 * @param adminActor   admin identifier for the audit trail, never {@code null}
	 * @param targetUserId viewed account id, never {@code null}
	 * @param from         window start inclusive, or {@code null} for the trailing default
	 * @param to           window end inclusive, or {@code null} for now
	 * @param ip           caller network address, or {@code null}
	 * @param requestId    correlation id, or {@code null}
	 * @return the target's dashboard view
	 */
	public DashboardView getUserAsAdmin(String adminActor, UUID targetUserId,
			Instant from, Instant to, String ip, String requestId) {
		Window window = resolveWindow(from, to);
		rateLimit("ADMIN", adminActor);
		Set<String> owners = distinctOwnerIds(keys.listKeysByUser(targetUserId));
		DashboardView view = summarize(SCOPE_PERSONAL, targetUserId.toString(), owners, window);
		audit.record(AuthAuditService.ACTION_DASHBOARD_VIEW, AuthAuditService.SEVERITY_INFO,
				adminActor, "/v1/admin/ledger/user/" + targetUserId + "/summary",
				AuthAuditService.OUTCOME_SUCCESS, ip, requestId);
		return view;
	}

	private DashboardView summarize(String scope, String scopeKey, Set<String> owners, Window window) {
		if (owners != null && owners.isEmpty()) {
			return emptyView(window.to());
		}
		String cacheKey = cacheKey(scope, scopeKey, owners, window.from());
		CompletableFuture<DashboardView> flight = new CompletableFuture<>();
		CompletableFuture<DashboardView> existing = flights.putIfAbsent(cacheKey, flight);
		if (existing != null) {
			return join(existing);
		}
		try {
			DashboardView computed = compute(scope, scopeKey, owners, window);
			flight.complete(computed);
			return computed;
		} catch (RuntimeException | Error failure) {
			flight.completeExceptionally(failure);
			throw failure;
		} finally {
			flights.remove(cacheKey, flight);
		}
	}

	private DashboardView join(CompletableFuture<DashboardView> existing) {
		try {
			return existing.join();
		} catch (CompletionException joined) {
			if (joined.getCause() instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IllegalStateException("Dashboard computation failed", joined.getCause());
		}
	}

	private DashboardView compute(String scope, String scopeKey, Set<String> owners, Window window) {
		Instant now = clock.instant();
		CachedView cached = views.getIfPresent(cacheKey(scope, scopeKey, owners, window.from()));
		if (cached != null && !now.isAfter(cached.expiresAt())) {
			if (!window.to().isAfter(cached.watermark())) {
				return toView(cached.rows(), cached.generatedAt(), cached.watermark());
			}
			List<OwnerModelUsageRecord> delta = queryDetail(owners, cached.watermark(), window.to());
			List<OwnerModelUsageRecord> merged = new ArrayList<>(cached.rows().size() + delta.size());
			merged.addAll(cached.rows());
			merged.addAll(delta);
			views.put(cacheKey(scope, scopeKey, owners, window.from()),
					new CachedView(merged, now, window.to(),
							now.plus(Duration.ofMinutes(properties.resultTtlMinutes()))));
			return toView(merged, now, window.to());
		}
		List<OwnerModelUsageRecord> rows = computeWindow(scope, scopeKey, owners, window, now);
		views.put(cacheKey(scope, scopeKey, owners, window.from()),
				new CachedView(rows, now, window.to(),
						now.plus(Duration.ofMinutes(properties.resultTtlMinutes()))));
		return toView(rows, now, window.to());
	}

	private List<OwnerModelUsageRecord> computeWindow(String scope, String scopeKey,
			Set<String> owners, Window window, Instant now) {
		LocalDate fromDate = window.from().atZone(ZoneOffset.UTC).toLocalDate();
		LocalDate toDate = window.to().atZone(ZoneOffset.UTC).toLocalDate();
		List<DashboardBucket> stored = readTransactions.execute(status -> {
			applyStatementTimeout();
			return buckets.findByScopeTypeAndScopeKeyAndBucketDayBetween(
					scope, scopeKey, fromDate, toDate);
		});
		Map<LocalDate, List<DashboardBucket>> byDay = stored.stream()
				.collect(Collectors.groupingBy(DashboardBucket::getBucketDay));
		List<LocalDate> settledDays = new ArrayList<>();
		for (LocalDate day = fromDate; !day.isAfter(toDate); day = day.plusDays(1)) {
			Instant dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant();
			if (!dayStart.isBefore(window.from())
					&& !dayStart.plus(Duration.ofDays(1)).isAfter(window.to())
					&& settled(day, now)) {
				settledDays.add(day);
			}
		}
		List<OwnerModelUsageRecord> rows = new ArrayList<>();
		for (LocalDate day : settledDays) {
			List<DashboardBucket> dayRows = byDay.getOrDefault(day, List.of());
			if (dayRows.isEmpty()) {
				dayRows = persistDay(scope, scopeKey, owners, day);
			}
			for (DashboardBucket bucket : dayRows) {
				rows.add(toRecord(bucket));
			}
		}
		Instant coveredFrom = null;
		Instant coveredTo = null;
		if (!settledDays.isEmpty()) {
			coveredFrom = settledDays.getFirst().atStartOfDay(ZoneOffset.UTC).toInstant();
			coveredTo = settledDays.getLast().atStartOfDay(ZoneOffset.UTC).toInstant()
					.plus(Duration.ofDays(1));
		}
		if (coveredFrom == null) {
			rows.addAll(queryDetail(owners, window.from(), window.to()));
		} else {
			if (window.from().isBefore(coveredFrom)) {
				rows.addAll(queryDetail(owners, window.from(), coveredFrom));
			}
			if (coveredTo.isBefore(window.to())) {
				rows.addAll(queryDetail(owners, coveredTo, window.to()));
			}
		}
		return rows;
	}

	private List<DashboardBucket> persistDay(String scope, String scopeKey,
			Set<String> owners, LocalDate day) {
		Instant dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant();
		Instant dayEnd = dayStart.plus(Duration.ofDays(1)).minusMillis(1);
		List<OwnerModelUsageRecord> grains = queryDetail(owners, dayStart, dayEnd);
		Instant now = clock.instant();
		List<DashboardBucket> rows = new ArrayList<>(grains.size());
		for (OwnerModelUsageRecord grain : grains) {
			rows.add(new DashboardBucket(UUID.randomUUID(), scope, scopeKey, day,
					grain.ownerId(), grain.provider(), grain.model(), grain.requests(),
					grain.promptTokens(), grain.completionTokens(), grain.totalTokens(),
					grain.costMicros(), grain.billedMicros(), grain.effectiveMicros(),
					grain.durationSumMs(), grain.cacheReadTokens(), grain.cacheWriteTokens(),
					grain.uncachedTokens(), grain.reasoningTokens(), dayEnd, now));
		}
		try {
			return buckets.saveAll(rows);
		} catch (DataIntegrityViolationException race) {
			return buckets.findByScopeTypeAndScopeKeyAndBucketDayBetween(
					scope, scopeKey, day, day);
		}
	}

	private List<OwnerModelUsageRecord> queryDetail(Set<String> owners, Instant from, Instant to) {
		return readTransactions.execute(status -> {
			applyStatementTimeout();
			return detail.getDetailRows(owners, from, to);
		});
	}

	private void applyStatementTimeout() {
		entityManager.createNativeQuery(
				"SET LOCAL statement_timeout = '" + properties.statementTimeoutSeconds() + "s'")
				.executeUpdate();
	}

	private boolean settled(LocalDate day, Instant now) {
		Instant usableFrom = day.atStartOfDay(ZoneOffset.UTC).toInstant()
				.plus(Duration.ofDays(1))
				.plus(Duration.ofMinutes(properties.graceOverlapMinutes()));
		return !now.isBefore(usableFrom);
	}

	private DashboardView toView(List<OwnerModelUsageRecord> rows, Instant generatedAt, Instant watermark) {
		return new DashboardView(buildResponse(rows), generatedAt, watermark);
	}

	private DashboardView emptyView(Instant watermark) {
		return new DashboardView(buildResponse(List.of()), clock.instant(), watermark);
	}

	private LedgerSummaryResponse buildResponse(List<OwnerModelUsageRecord> rows) {
		long requests = 0L;
		long prompt = 0L;
		long completion = 0L;
		long total = 0L;
		long cost = 0L;
		long durationSum = 0L;
		Map<String, Accumulator> byOwner = new HashMap<>();
		Map<String, Accumulator> byModel = new HashMap<>();
		Map<String, Accumulator> byProvider = new HashMap<>();
		for (OwnerModelUsageRecord row : rows) {
			requests += row.requests();
			prompt += row.promptTokens();
			completion += row.completionTokens();
			total += row.totalTokens();
			cost += row.costMicros();
			durationSum += row.durationSumMs();
			byOwner.computeIfAbsent(row.ownerId(), key -> new Accumulator()).add(row);
			byModel.computeIfAbsent(row.provider() + "\u0000" + row.model(), key -> new Accumulator()).add(row);
			byProvider.computeIfAbsent(row.provider(), key -> new Accumulator()).add(row);
		}
		double average = requests == 0 ? 0.0 : (double) durationSum / requests;
		BigDecimal costUsd = UsageLedgerService.microsToUsd(cost);
		List<OwnerUsageSummary> owners = byOwner.entrySet().stream()
				.map(entry -> new OwnerUsageSummary(entry.getKey(), entry.getValue().requests,
						entry.getValue().prompt, entry.getValue().completion, entry.getValue().total,
						entry.getValue().cost, UsageLedgerService.microsToUsd(entry.getValue().cost),
						entry.getValue().average()))
				.sorted(Comparator.comparingLong(OwnerUsageSummary::totalCostUsdMicros).reversed())
				.toList();
		List<ModelUsageSummary> models = byModel.entrySet().stream()
				.map(entry -> {
					String[] parts = entry.getKey().split("\u0000", -1);
					return new ModelUsageSummary(parts[0], parts[1], entry.getValue().requests,
							entry.getValue().prompt, entry.getValue().completion, entry.getValue().total,
							entry.getValue().cost, UsageLedgerService.microsToUsd(entry.getValue().cost),
							entry.getValue().average());
				})
				.sorted(Comparator.comparingLong(ModelUsageSummary::totalCostUsdMicros).reversed())
				.toList();
		List<ProviderUsageSummary> providers = byProvider.entrySet().stream()
				.map(entry -> new ProviderUsageSummary(entry.getKey(), entry.getValue().requests,
						entry.getValue().prompt, entry.getValue().completion, entry.getValue().total,
						entry.getValue().cost, UsageLedgerService.microsToUsd(entry.getValue().cost),
						entry.getValue().average()))
				.sorted(Comparator.comparingLong(ProviderUsageSummary::totalCostUsdMicros).reversed())
				.toList();
		return new LedgerSummaryResponse(requests, prompt, completion, total, cost, costUsd,
				average, owners, models, providers);
	}

	private Window resolveWindow(Instant from, Instant to) {
		Instant now = clock.instant();
		Instant effectiveTo = to == null ? now.truncatedTo(ChronoUnit.MINUTES) : to;
		Instant effectiveFrom = from == null
				? effectiveTo.minus(Duration.ofDays(properties.defaultWindowDays()))
				: from;
		if (effectiveFrom.isAfter(effectiveTo)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Parameter 'from' cannot be after 'to'");
		}
		if (Duration.between(effectiveFrom, effectiveTo).compareTo(MAX_WINDOW) > 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Query window exceeds maximum allowed limit of " + MAX_WINDOW.toDays() + " days");
		}
		return new Window(effectiveFrom, effectiveTo);
	}

	private void rateLimit(String scope, String key) {
		String limiter = scope + ":" + key;
		long seen = viewCounts.get(limiter, unused -> new AtomicLong()).incrementAndGet();
		if (seen > properties.rateLimitPerMinute()) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
					"Dashboard view rate exceeded");
		}
	}

	private Set<String> distinctOwnerIds(List<VirtualApiKey> owned) {
		Set<String> owners = new HashSet<>();
		for (VirtualApiKey key : owned) {
			if (key.ownerId() != null && !key.ownerId().isBlank()) {
				owners.add(key.ownerId());
			}
		}
		return owners;
	}

	private String cacheKey(String scope, String scopeKey, Set<String> owners, Instant from) {
		String ownerPart = owners == null ? "all" : owners.stream().sorted().collect(Collectors.joining(","));
		return scope + "|" + scopeKey + "|" + ownerPart + "|" + from.toString();
	}

	private OwnerModelUsageRecord toRecord(DashboardBucket bucket) {
		return new OwnerModelUsageRecord(bucket.getOwner(), bucket.getProvider(), bucket.getModel(),
				bucket.getRequests(), bucket.getPromptTokens(), bucket.getCompletionTokens(),
				bucket.getTotalTokens(), bucket.getCostMicros(), bucket.getBilledMicros(),
				bucket.getEffectiveMicros(), bucket.getDurationSumMs(), bucket.getCacheReadTokens(),
				bucket.getCacheWriteTokens(), bucket.getUncachedTokens(), bucket.getReasoningTokens());
	}

	private record Window(Instant from, Instant to) {
	}

	private record CachedView(List<OwnerModelUsageRecord> rows, Instant generatedAt,
			Instant watermark, Instant expiresAt) {
	}

	private static final class Accumulator {
		private long requests;
		private long prompt;
		private long completion;
		private long total;
		private long cost;
		private long durationSum;

		void add(OwnerModelUsageRecord row) {
			requests += row.requests();
			prompt += row.promptTokens();
			completion += row.completionTokens();
			total += row.totalTokens();
			cost += row.costMicros();
			durationSum += row.durationSumMs();
		}

		double average() {
			return (double) durationSum / requests;
		}
	}
}
