package io.github.kxng0109.cacherelay.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

import javax.sql.DataSource;

import io.github.kxng0109.cacherelay.SharedContainersBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FS-B15 against real PostgreSQL: the extender keeps the partition horizon
 * ahead of inserts, CHECKs reject negative money, and archive recovery is
 * idempotent.
 */
@DisplayName("FS-B15 retention guards against real PostgreSQL")
class PartitionRetentionIT extends SharedContainersBase {

	private JdbcTemplate jdbc() {
		return new JdbcTemplate(SharedContainersBase.newDataSource());
	}

	private RetentionJanitor janitor() throws Exception {
		DataSource dataSource = mock(DataSource.class);
		Connection connection = mock(Connection.class);
		PreparedStatement lockStatement = mock(PreparedStatement.class);
		ResultSet lockRows = mock(ResultSet.class);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.prepareStatement(anyString())).thenReturn(lockStatement);
		when(lockStatement.executeQuery()).thenReturn(lockRows);
		when(lockRows.next()).thenReturn(true);
		when(lockRows.getBoolean(1)).thenReturn(true);
		return new RetentionJanitor(jdbc(), dataSource, MaintenanceProperties.DEFAULTS);
	}

	@Test
	@DisplayName("FIN-B33: insert 14 months ahead succeeds after the extender runs")
	void extenderKeepsHorizonAhead() throws Exception {
		janitor().retain();

		YearMonth future = YearMonth.now(ZoneOffset.UTC).plusMonths(14);
		Instant expires = future.atDay(15).atStartOfDay(ZoneOffset.UTC).toInstant();
		UUID id = UUID.randomUUID();
		jdbc().update(
				"INSERT INTO replay_store (id, expires_at, body, body_hash) VALUES (?, ?, ?, ?)",
				id, java.sql.Timestamp.from(expires), new byte[] {1, 2, 3}, "ab".repeat(32));

		assertThat(jdbc().queryForObject(
				"SELECT COUNT(*) FROM replay_store WHERE id = ?", Long.class, id))
				.isEqualTo(1L);
	}

	@Test
	@DisplayName("FIN-B37: negative money is rejected by the database")
	void negativeMoneyRejected() {
		assertThatThrownBy(() -> jdbc().update(
				"INSERT INTO usage_ledger (id, request_id, owner_id, provider, model,"
						+ " prompt_tokens, completion_tokens, total_tokens, cost_usd_micros,"
						+ " duration_ms, created_at)"
						+ " VALUES (?, ?, 't', 'openai', 'm', 10, 5, 15, -1, 100, now())",
				UUID.randomUUID(), UUID.randomUUID()))
				.isInstanceOf(DataAccessException.class);
	}

	@Test
	@DisplayName("FIN-B38: archive recovery after a crash deletes exactly once")
	void archiveRecoveryIdempotent() throws Exception {
		UUID id = UUID.randomUUID();
		// 100 days: past the 90-day archive threshold, inside the 365-day
		// archive retention (400 days would also trip the archive purge).
		jdbc().update(
				"INSERT INTO alert_events (id, dedupe_sha, scope, detector, severity,"
						+ " starts_at, payload, status, created_at)"
						+ " VALUES (?, 'sha-old', 'KEY:x', 'burn_static', 'warning',"
						+ " now() - INTERVAL '100 days', '{}', 'SENT', now() - INTERVAL '100 days')",
				id);
		jdbc().update(
				"INSERT INTO alert_events_archive (id, dedupe_sha, scope, detector, severity,"
						+ " starts_at, ends_at, payload, value_text, month, status, attempts,"
						+ " next_retry_at, created_at)"
						+ " SELECT id, dedupe_sha, scope, detector, severity,"
						+ " starts_at, ends_at, payload, value_text, month, status, attempts,"
						+ " next_retry_at, created_at FROM alert_events WHERE id = ?",
				id);

		janitor().retain();

		assertThat(jdbc().queryForObject(
				"SELECT COUNT(*) FROM alert_events_archive", Long.class))
				.as("archive table total")
				.isPositive();
		assertThat(jdbc().queryForObject(
				"SELECT COUNT(*) FROM alert_events WHERE id = ?", Long.class, id))
				.as("hot row gone")
				.isEqualTo(0L);
		assertThat(jdbc().queryForObject(
				"SELECT COUNT(*) FROM alert_events_archive WHERE id = ?", Long.class, id))
				.as("exactly one archive copy")
				.isEqualTo(1L);
	}
}
