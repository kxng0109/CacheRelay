package io.github.kxng0109.cacherelay.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.kxng0109.cacherelay.auth.backfill.BackfillResult;
import io.github.kxng0109.cacherelay.auth.backfill.SsoBackfillOrchestrator;
import io.github.kxng0109.cacherelay.security.ratelimit.KeyManagementService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IdP revalidation sweep: re-checks known SSO accounts at the IdP on two
 * ShedLock single-holder cadences and revokes access on positive
 * disabled-or-deleted signals only.
 *
 * <p>Watermarks self-seed at the epoch so first checks run immediately, then
 * advance on every attempt (success or transport failure alike) so IdP
 * outages retry at cadence instead of hot-looping or mass-revoking. Group
 * drift converges on next login; this sweep closes the disabled gap that
 * per-login checks cannot reach. GitHub has no service credential for user
 * reads, so its links are skipped (removals still deny the next login).</p>
 */
@Service
public class SsoRevalidationService {

	private final SsoRevalidationRepository watermarks;

	private final SsoLinkRepository links;

	private final UserAccountRepository users;

	private final KeyManagementService keys;

	private final RefreshService refresh;

	private final SsoBackfillProperties backfillProperties;

	private final SsoBackfillOrchestrator orchestrator;

	private final AuthAuditService audit;

	private final SsoRevalidationProperties properties;

	private final Clock clock;

	private final @Nullable SsoRevalidationService self;

	/**
	 * Creates the service.
	 *
	 * @param watermarks         watermark persistence, never {@code null}
	 * @param links              SSO link reads, never {@code null}
	 * @param users              account persistence, never {@code null}
	 * @param keys               key revocation, never {@code null}
	 * @param refresh            session revocation, never {@code null}
	 * @param backfillProperties per-registration backfill entries, never {@code null}
	 * @param orchestrator       IdP fetch dispatcher, never {@code null}
	 * @param audit              audit log, never {@code null}
	 * @param properties         sweep ceilings, never {@code null}
	 * @param clock              clock for cutoffs and stamps, never {@code null}
	 * @param self               transactional self-proxy, or {@code null} for direct
	 *                           calls in unit tests (mocks need no transaction)
	 */
	@Autowired
	public SsoRevalidationService(SsoRevalidationRepository watermarks, SsoLinkRepository links,
			UserAccountRepository users, KeyManagementService keys, RefreshService refresh,
			SsoBackfillProperties backfillProperties, SsoBackfillOrchestrator orchestrator,
			AuthAuditService audit, SsoRevalidationProperties properties, Clock clock,
			@Lazy SsoRevalidationService self) {
		this.watermarks = watermarks;
		this.links = links;
		this.users = users;
		this.keys = keys;
		this.refresh = refresh;
		this.backfillProperties = backfillProperties;
		this.orchestrator = orchestrator;
		this.audit = audit;
		this.properties = properties;
		this.clock = clock;
		this.self = self;
	}

	/**
	 * Creates the service with direct (non-transactional) internal calls, for
	 * unit tests whose repositories are mocks.
	 *
	 * @param watermarks         watermark persistence, never {@code null}
	 * @param links              SSO link reads, never {@code null}
	 * @param users              account persistence, never {@code null}
	 * @param keys               key revocation, never {@code null}
	 * @param refresh            session revocation, never {@code null}
	 * @param backfillProperties per-registration backfill entries, never {@code null}
	 * @param orchestrator       IdP fetch dispatcher, never {@code null}
	 * @param audit              audit log, never {@code null}
	 * @param properties         sweep ceilings, never {@code null}
	 * @param clock              clock for cutoffs and stamps, never {@code null}
	 */
	public SsoRevalidationService(SsoRevalidationRepository watermarks, SsoLinkRepository links,
			UserAccountRepository users, KeyManagementService keys, RefreshService refresh,
			SsoBackfillProperties backfillProperties, SsoBackfillOrchestrator orchestrator,
			AuthAuditService audit, SsoRevalidationProperties properties, Clock clock) {
		this(watermarks, links, users, keys, refresh, backfillProperties, orchestrator,
				audit, properties, clock, null);
	}

	/**
	 * Hot sweep over recently verified accounts.
	 */
	@Scheduled(cron = "${gateway.sso.revalidation.hot-cron:0 */15 * * * *}")
	@SchedulerLock(name = "sso-reval-hot", lockAtMostFor = "14m", lockAtLeastFor = "14m")
	public void sweepHot() {
		revalidateBatch(clock.instant().minus(Duration.ofMinutes(properties.hotStaleMinutes())),
				properties.batchSize());
	}

	/**
	 * Cold sweep over dormant accounts.
	 */
	@Scheduled(cron = "${gateway.sso.revalidation.cold-cron:0 0 2 * * *}")
	@SchedulerLock(name = "sso-reval-cold", lockAtMostFor = "1h", lockAtLeastFor = "23h")
	public void sweepCold() {
		revalidateBatch(clock.instant().minus(Duration.ofHours(properties.coldStaleHours())),
				properties.batchSize());
	}

	/**
	 * Revalidates one overdue batch: self-seeds missing watermarks, claims due
	 * rows, and checks each at the IdP.
	 *
	 * <p>Transaction boundaries are deliberately narrow: the claim phase runs in
	 * one short transaction, every IdP call runs outside any transaction, and
	 * each verdict persists in its own short transaction. Transactions must
	 * never span the remote IdP reads, or one sweep holds a pool connection
	 * for minutes. Internal steps route through the transactional self-proxy
	 * when present, so unit tests with mocked repositories keep working with
	 * direct calls.</p>
	 *
	 * @param cutoff    staleness bound, never {@code null}
	 * @param batchSize users claimed per tick
	 */
	public void revalidateBatch(Instant cutoff, int batchSize) {
		if (!properties.enabled()) {
			return;
		}
		List<Claim> claimed = target().claimBatch(cutoff, batchSize);
		for (Claim claim : claimed) {
			revalidateOne(claim);
		}
	}

	private SsoRevalidationService target() {
		return self != null ? self : this;
	}

	/**
	 * One claimed watermark plus its checkable IdP link, if any.
	 *
	 * @param watermark claimed watermark, never {@code null}
	 * @param link      checkable link, or {@code null} when the account has none
	 */
	private record Claim(SsoRevalidation watermark, @Nullable SsoLink link) {
	}

	/**
	 * Seeds missing watermarks and claims due rows in one short transaction.
	 *
	 * @param cutoff    staleness bound, never {@code null}
	 * @param batchSize users claimed per tick
	 * @return claimed watermarks with resolved links, never {@code null}
	 */
	@Transactional
	public List<Claim> claimBatch(Instant cutoff, int batchSize) {
		List<UUID> missing =
				watermarks.findUserIdsMissingWatermark(PageRequest.of(0, batchSize));
		for (UUID userId : missing) {
			watermarks.save(new SsoRevalidation(userId, Instant.EPOCH, null));
		}
		List<Claim> claimed = new ArrayList<>();
		for (SsoRevalidation watermark :
				watermarks.findDueForRevalidation(cutoff, PageRequest.of(0, batchSize))) {
			SsoLink link = null;
			for (SsoLink candidate : links.findByUserId(watermark.getUserId())) {
				if (checkable(candidate.getRegistrationId())) {
					link = candidate;
					break;
				}
			}
			claimed.add(new Claim(watermark, link));
		}
		return claimed;
	}

	private void revalidateOne(Claim claim) {
		Instant now = clock.instant();
		SsoLink link = claim.link();
		if (link == null) {
			target().noteSkipped(claim.watermark(), now);
			return;
		}
		// Remote IdP read: deliberately outside any transaction, so a slow or
		// hung provider never holds a pool connection.
		Optional<BackfillResult> result =
				orchestrator.revalidate(link.getRegistrationId(), link.getSubject());
		target().applyVerdict(claim.watermark(), result, now);
	}

	/**
	 * Marks a link-less watermark without a verdict in its own short transaction.
	 *
	 * @param watermark claimed watermark, never {@code null}
	 * @param now       decision instant, never {@code null}
	 */
	@Transactional
	public void noteSkipped(SsoRevalidation watermark, Instant now) {
		watermark.mark(now, null);
		watermarks.save(watermark);
	}

	/**
	 * Persists one IdP verdict in its own short transaction.
	 *
	 * @param watermark claimed watermark, never {@code null}
	 * @param verdict   IdP outcome, empty when the check produced none
	 * @param now       decision instant, never {@code null}
	 */
	@Transactional
	public void applyVerdict(SsoRevalidation watermark, Optional<BackfillResult> verdict,
			Instant now) {
		if (verdict.isEmpty()) {
			watermark.mark(now, null);
			watermarks.save(watermark);
			return;
		}
		if (verdict.get().disabled()) {
			revoke(watermark.getUserId());
			watermark.mark(now, RevalidationStatus.INACTIVE);
		} else {
			watermark.mark(now, RevalidationStatus.ACTIVE);
		}
		watermarks.save(watermark);
	}

	private boolean checkable(String registrationId) {
		return backfillProperties.forRegistration(registrationId)
				.map(entry -> entry.mode() != BackfillMode.NONE
						&& entry.mode() != BackfillMode.GITHUB_API)
				.orElse(false);
	}

	private void revoke(UUID userId) {
		Optional<UserAccount> account = users.findById(userId);
		if (account.isEmpty()) {
			watermarks.deleteById(userId);
			audit.record(AuthAuditService.ACTION_SSO_REVOKE, AuthAuditService.SEVERITY_INFO,
					"sso:unknown", "/sso/revalidation", AuthAuditService.OUTCOME_SUCCESS, null,
					null);
			return;
		}
		UserAccount target = account.get();
		target.disable();
		users.save(target);
		keys.revokeUserKeys(userId);
		refresh.revokeAll(userId);
		audit.record(AuthAuditService.ACTION_SSO_REVOKE, AuthAuditService.SEVERITY_WARN,
				target.getUsername(), "/sso/revalidation", AuthAuditService.OUTCOME_SUCCESS, null,
				null);
	}
}
