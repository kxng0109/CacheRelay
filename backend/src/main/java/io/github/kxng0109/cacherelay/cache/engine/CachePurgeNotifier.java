package io.github.kxng0109.cacherelay.cache.engine;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Fans administrative cache purges out to every gateway instance via Postgres
 * {@code NOTIFY}. Each pod's {@link CachePurgeInvalidationListener} flushes
 * its process-local L0 on receipt, so a purge issued on one pod stops serving
 * purged content fleet-wide within milliseconds instead of waiting out the
 * L0 TTL.
 *
 * <p>Best-effort by design: the issuing pod always purges locally first (plus
 * the shared Redis tiers, which are already global), so its own reads are
 * correct even when fan-out fails. Failures log and continue. The channel
 * payload is {@code ALL} or {@code tenant:<ownerId>} where the owner id is a
 * validated tenant slug, so no quote characters can reach the statement.
 */
@Slf4j
@Component
public class CachePurgeNotifier {

	static final String CHANNEL = "cache_purge_all";

	private final JdbcTemplate jdbc;

	/**
	 * Creates the notifier.
	 *
	 * @param jdbc template for the {@code pg_notify} statement, never {@code null}
	 */
	public CachePurgeNotifier(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Notifies every pod that a purge executed.
	 *
	 * @param scope {@code ALL} or {@code tenant:<ownerId>} describing what was purged
	 */
	public void notifyPurge(String scope) {
		try {
			jdbc.execute("SELECT pg_notify('" + CHANNEL + "', '" + scope + "')");
		} catch (RuntimeException ex) {
			log.warn("Cache purge fan-out failed, remotes heal via TTL: {}", ex.getMessage());
		}
	}
}
