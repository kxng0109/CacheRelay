package io.github.kxng0109.cacherelay.budget;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Monthly retention maintenance: detaches expired replay partitions into standalone archive tables (data
 * preserved), rolls old sent alerts into the 1-year archive, purges what aged out, and trims send logs and
 * dedupe claims. Single-flight across pods via advisory lock; every step is independent and best-effort (a
 * failed step logs and continues, retried next month). Audit and gap tables are never touched.
 */
@Component
public class RetentionJanitor {

	static final String LOCK_NAME = "cacherelay-retention-janitor";

	static final int REPLAY_ARCHIVE_DAYS = 90;

	static final int ALERT_ARCHIVE_DAYS = 90;

	static final int ARCHIVE_PURGE_DAYS = 365;

	static final int LOG_PURGE_DAYS = 90;

	/**
	 * FIN-B39: notification dedupe claims retire after 30 days — far beyond
	 * the alert retry horizon (minutes) and the 24h budget-claim lifecycle,
	 * so retries and monthly re-alerts (distinct dedupe_sha per month) never
	 * lose their guard early, while the table stays bounded.
	 */
	static final int DEDUPE_PURGE_DAYS = 30;

	private static final Logger log = LoggerFactory.getLogger(RetentionJanitor.class);

	private final JdbcTemplate jdbc;

	private final DataSource dataSource;

	private final MaintenanceProperties properties;

	private final AtomicLong lastTickMillis = new AtomicLong(0);

	private volatile MeterRegistry meterRegistry = new SimpleMeterRegistry();

	private volatile @Nullable TransactionTemplate archiveTemplate;

	public RetentionJanitor(JdbcTemplate jdbc, DataSource dataSource, MaintenanceProperties properties) {
		this.jdbc = jdbc;
		this.dataSource = dataSource;
		this.properties = properties;
	}

	/**
	 * Wires the transaction template for the alert archive roll. Forced to
	 * {@code REQUIRES_NEW}: the INSERT..SELECT plus DELETE plus count check
	 * must commit or roll back atomically (FIN-B38). Optional on purpose:
	 * without it the roll runs in auto-commit steps with the same statements.
	 *
	 * @param archiveTemplate template for the archive transaction, if available
	 */
	@Autowired
	public void setArchiveTemplate(@Nullable TransactionTemplate archiveTemplate) {
		if (archiveTemplate != null) {
			archiveTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
			this.archiveTemplate = archiveTemplate;
		}
	}

	/**
	 * Wires the registry for the last-successful-tick gauge. Optional on
	 * purpose: without it the tick still runs, only unobserved.
	 *
	 * @param meterRegistry registry hosting the tick gauge, if available
	 */
	@Autowired
	public void setMeterRegistry(@Nullable MeterRegistry meterRegistry) {
		if (meterRegistry != null) {
			this.meterRegistry = meterRegistry;
			Gauge.builder("cacherelay.job.last_tick_seconds", lastTickMillis,
							value -> value.get() / 1000.0)
					.description("Last successful retention tick (epoch seconds)")
					.tag("job", "retention-janitor")
					.register(meterRegistry);
		}
	}

	@Scheduled(cron = "${gateway.maintenance.retention-cron:0 0 3 1 * *}")
	public void retain() {
		if (!properties.retentionEnabled()) {
			return;
		}
		try (Connection connection = dataSource.getConnection()) {
			connection.setAutoCommit(false);
			if (!AdvisoryLock.tryLockXact(connection, LOCK_NAME)) {
				connection.rollback();
				return;
			}
			try {
				ensureReplayPartitions();
				detachExpiredReplayPartitions();
				archiveOldAlerts();
				purgeOldArchive();
				purgeOldLogs();
				purgeOldDedupes();
				connection.commit();
				lastTickMillis.set(System.currentTimeMillis());
			} catch (RuntimeException ex) {
				rollbackQuietly(connection);
				throw ex;
			}
		} catch (SQLException ex) {
			log.warn("Retention tick skipped (datasource unavailable)");
		}
	}

	private static void rollbackQuietly(Connection connection) {
		try {
			connection.rollback();
		} catch (SQLException ignored) {
		}
	}

	/**
	 * Extends the replay partition horizon to the current month plus
	 * {@value #PARTITION_HORIZON_MONTHS} (FIN-B33): inserts landing beyond
	 * the last pre-created partition fail with "no partition", so the horizon
	 * must always lead the writers. Fifteen months keeps the +14-month
	 * acceptance probe green with a month of margin even if monthly runs are
	 * missed for a year. {@code IF NOT EXISTS} makes concurrent pods
	 * idempotent. Visible for tests.
	 */
	static final int PARTITION_HORIZON_MONTHS = 15;

	void ensureReplayPartitions() {
		try {
			YearMonth month = YearMonth.now(ZoneOffset.UTC);
			for (int i = 0; i <= PARTITION_HORIZON_MONTHS; i++) {
				YearMonth target = month.plusMonths(i);
				String name = String.format("replay_store_%04d_%02d",
						target.getYear(), target.getMonthValue());
				YearMonth next = target.plusMonths(1);
				jdbc.execute(String.format(
						"CREATE TABLE IF NOT EXISTS %s PARTITION OF replay_store"
								+ " FOR VALUES FROM ('%04d-%02d-01') TO ('%04d-%02d-01')",
						name,
						target.getYear(), target.getMonthValue(),
						next.getYear(), next.getMonthValue()));
			}
		} catch (RuntimeException ex) {
			log.warn("Replay partition extension failed; next month retries");
		}
	}

	void detachExpiredReplayPartitions() {
		try {
			List<String> partitions = jdbc.queryForList(
					"SELECT inhrelid::regclass::text FROM pg_inherits WHERE inhparent = 'replay_store'::regclass",
					String.class);
			for (String partition : partitions) {
				try {
					String suffix = partition.substring("replay_store_".length());
					YearMonth end = YearMonth.parse(
							suffix.substring(0, 4) + "-" + suffix.substring(5, 7)).plusMonths(1);
					if (end.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()
							.isBefore(Instant.now().minus(Duration.ofDays(REPLAY_ARCHIVE_DAYS)))) {
						jdbc.execute("ALTER TABLE replay_store DETACH PARTITION " + partition
								+ " CONCURRENTLY");
						log.info("Detached replay partition {} into standalone archive", partition);
					}
				} catch (RuntimeException ex) {
					log.warn("Skipping unparseable replay partition {}", partition);
				}
			}
		} catch (RuntimeException ex) {
			log.warn("Replay partition detach failed; next month retries");
		}
	}

	void archiveOldAlerts() {
		try {
			TransactionTemplate template = this.archiveTemplate;
			if (template != null) {
				template.executeWithoutResult(status -> archiveBatch());
			} else {
				archiveBatch();
			}
		} catch (RuntimeException ex) {
			log.warn("Alert archive roll failed; next month retries");
		}
	}

	/**
	 * Moves one retention window of terminal alerts to the archive and deletes
	 * them from hot, then verifies nothing matching the predicate remains.
	 * Column lists are explicit: the archive once drifted behind alert_events
	 * (V14) and {@code SELECT *} fails loudly on drift instead of silently
	 * misaligning.
	 */
	private void archiveBatch() {
		String columns = "id, dedupe_sha, scope, detector, severity, starts_at, ends_at,"
				+ " payload, value_text, month, status, attempts, next_retry_at, created_at";
		int moved = jdbc.update(
				"INSERT INTO alert_events_archive (" + columns + ") SELECT " + columns
						+ " FROM alert_events"
						+ " WHERE status IN ('SENT', 'RESOLVED', 'SKIPPED') AND created_at < now() - (? || ' days')::interval"
						+ " ON CONFLICT DO NOTHING",
				Integer.toString(ALERT_ARCHIVE_DAYS));
		int purged = jdbc.update(
				"DELETE FROM alert_events WHERE status IN ('SENT', 'RESOLVED', 'SKIPPED')"
						+ " AND created_at < now() - (? || ' days')::interval",
				Integer.toString(ALERT_ARCHIVE_DAYS));
		if (moved > 0 || purged > 0) {
			log.info("Alert archive roll: {} archived, {} purged from hot", moved, purged);
		}
		Integer remaining = jdbc.queryForObject(
				"SELECT COUNT(*) FROM alert_events"
						+ " WHERE status IN ('SENT', 'RESOLVED', 'SKIPPED') AND created_at < now() - (? || ' days')::interval",
				Integer.class, Integer.toString(ALERT_ARCHIVE_DAYS));
		if (remaining != null && remaining > 0) {
			throw new IllegalStateException("Alert archive roll left " + remaining + " rows in hot");
		}
	}

	void purgeOldArchive() {
		try {
			int purged = jdbc.update(
					"DELETE FROM alert_events_archive WHERE created_at < now() - (? || ' days')::interval",
					Integer.toString(ARCHIVE_PURGE_DAYS));
			if (purged > 0) {
				log.warn("Purged {} alert archive rows older than {} days (reviewed policy)", purged,
						ARCHIVE_PURGE_DAYS);
			}
		} catch (RuntimeException ex) {
			log.warn("Alert archive purge failed; next month retries");
		}
	}

	void purgeOldLogs() {
		try {
			jdbc.update("DELETE FROM notification_log WHERE created_at < now() - (? || ' days')::interval",
					Integer.toString(LOG_PURGE_DAYS));
		} catch (RuntimeException ex) {
			log.warn("Notification log trim failed; next month retries");
		}
	}

	void purgeOldDedupes() {
		try {
			jdbc.update("DELETE FROM notification_dedupe WHERE created_at < now() - (? || ' days')::interval",
					Integer.toString(DEDUPE_PURGE_DAYS));
		} catch (RuntimeException ex) {
			log.warn("Notification dedupe trim failed; next month retries");
		}
	}
}
