package io.github.kxng0109.cacherelay.auth;

import java.time.Instant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily hygiene for human-auth tables: purges expired refresh rows (idle or absolute
 * horizon), expired never-consumed invites, and audit events past the retention horizon
 * (default plus the longest jurisdictional override — the legal floor wins).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthJanitor {

	private final RefreshTokenRepository refresh;
	private final InviteTokenRepository invites;
	private final AuthAuditRepository audit;
	private final AuthProperties properties;

	/**
	 * Runs the purge once daily.
	 */
	@Scheduled(cron = "${gateway.auth.janitor-cron:0 0 4 * * *}")
	@Transactional
	public void purge() {
		Instant now = Instant.now();
		int tokens = refresh.deleteExpired(now, now);
		int expiredInvites = invites.deleteExpiredUnconsumed(now);
		int maxRetention = properties.auditRetentionDays();
		for (int override : properties.auditRetentionDaysByJurisdiction().values()) {
			maxRetention = Math.max(maxRetention, override);
		}
		int events = audit.deleteBefore(now.minusSeconds((long) maxRetention * 86_400L));
		log.info("Auth janitor purged {} refresh rows, {} invites, {} audit events",
				tokens, expiredInvites, events);
	}
}
