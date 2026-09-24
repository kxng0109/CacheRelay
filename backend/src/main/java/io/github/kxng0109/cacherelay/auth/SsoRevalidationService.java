package io.github.kxng0109.cacherelay.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.kxng0109.cacherelay.auth.backfill.BackfillResult;
import io.github.kxng0109.cacherelay.auth.backfill.SsoBackfillOrchestrator;
import io.github.kxng0109.cacherelay.security.ratelimit.KeyManagementService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
	 */
	public SsoRevalidationService(SsoRevalidationRepository watermarks, SsoLinkRepository links,
			UserAccountRepository users, KeyManagementService keys, RefreshService refresh,
			SsoBackfillProperties backfillProperties, SsoBackfillOrchestrator orchestrator,
			AuthAuditService audit, SsoRevalidationProperties properties, Clock clock) {
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
	 * @param cutoff    staleness bound, never {@code null}
	 * @param batchSize users claimed per tick
	 */
	public void revalidateBatch(Instant cutoff, int batchSize) {
		if (!properties.enabled()) {
			return;
		}
		List<UUID> missing =
				watermarks.findUserIdsMissingWatermark(PageRequest.of(0, batchSize));
		for (UUID userId : missing) {
			watermarks.save(new SsoRevalidation(userId, Instant.EPOCH, null));
		}
		List<SsoRevalidation> claimed =
				watermarks.findDueForRevalidation(cutoff, PageRequest.of(0, batchSize));
		for (SsoRevalidation watermark : claimed) {
			revalidateOne(watermark);
		}
	}

	private void revalidateOne(SsoRevalidation watermark) {
		Instant now = clock.instant();
		SsoLink link = null;
		for (SsoLink candidate : links.findByUserId(watermark.getUserId())) {
			if (checkable(candidate.getRegistrationId())) {
				link = candidate;
				break;
			}
		}
		if (link == null) {
			watermark.mark(now, null);
			watermarks.save(watermark);
			return;
		}
		Optional<BackfillResult> result =
				orchestrator.revalidate(link.getRegistrationId(), link.getSubject());
		if (result.isEmpty()) {
			watermark.mark(now, null);
			watermarks.save(watermark);
			return;
		}
		if (result.get().disabled()) {
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
