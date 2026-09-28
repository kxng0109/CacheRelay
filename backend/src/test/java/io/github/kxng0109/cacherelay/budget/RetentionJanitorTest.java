package io.github.kxng0109.cacherelay.budget;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import javax.sql.DataSource;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the retention janitor with stubbed JDBC: partition detach math (old detaches, recent and
 * unparseable names are skipped), archive rollover, purges, lock contention, kill-switch, and tick failure
 * absorption.
 */
@DisplayName("RetentionJanitor")
class RetentionJanitorTest {

	private record Harness(JdbcTemplate jdbc, DataSource dataSource, RetentionJanitor janitor) {
	}

	private static Harness harness() throws Exception {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		DataSource dataSource = mock(DataSource.class);
		Connection connection = mock(Connection.class);
		PreparedStatement lockStatement = mock(PreparedStatement.class);
		ResultSet lockRows = mock(ResultSet.class);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.prepareStatement(anyString())).thenReturn(lockStatement);
		when(lockStatement.executeQuery()).thenReturn(lockRows);
		when(lockRows.next()).thenReturn(true);
		when(lockRows.getBoolean(1)).thenReturn(true);
		RetentionJanitor janitor = new RetentionJanitor(jdbc, dataSource, MaintenanceProperties.DEFAULTS);
		return new Harness(jdbc, dataSource, janitor);
	}

	@Test
	@DisplayName("old partitions detach while recent and malformed names are skipped")
	void detachMath() throws Exception {
		Harness harness = harness();
		String old = "replay_store_" + YearMonth.now(ZoneOffset.UTC).minusMonths(5)
				.toString().replace("-", "_");
		String recent = "replay_store_" + YearMonth.now(ZoneOffset.UTC)
				.toString().replace("-", "_");
		when(harness.jdbc().queryForList(anyString(), eq(String.class)))
				.thenReturn(List.of(old, recent, "replay_store_foo"));
		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);

		harness.janitor().detachExpiredReplayPartitions();

		verify(harness.jdbc()).execute(sql.capture());
		assertThat(sql.getValue()).contains("DETACH PARTITION " + old);
	}

	@Test
	@DisplayName("null meter registry leaves the roll unobserved without failing")
	void nullMeterRegistryLeavesRollUnobserved() throws Exception {
		Harness harness = harness();
		harness.janitor().setMeterRegistry(null);
		when(harness.jdbc().update(anyString(), anyString())).thenReturn(0, 0);
		when(harness.jdbc().update(anyString())).thenReturn(0);

		assertThatNoException().isThrownBy(() -> harness.janitor().archiveOldAlerts());
	}

	@Test
	@DisplayName("archive roll moves and purges in order")
	void archiveRollMovesAndPurges() throws Exception {
		Harness harness = harness();
		when(harness.jdbc().update(anyString(), anyString())).thenReturn(3, 3);

		harness.janitor().archiveOldAlerts();

		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		verify(harness.jdbc(), times(2)).update(sql.capture(), anyString());
		assertThat(sql.getAllValues().get(0)).contains("INSERT INTO alert_events_archive");
		assertThat(sql.getAllValues().get(1)).startsWith("DELETE FROM alert_events ");
	}

	@Test
	@DisplayName("won lock runs every step then unlocks")
	void wonLockRunsAllSteps() throws Exception {
		Harness harness = harness();
		when(harness.jdbc().queryForList(anyString(), eq(String.class))).thenReturn(List.of());
		when(harness.jdbc().update(anyString(), anyString())).thenReturn(0);

		harness.janitor().retain();

		verify(harness.jdbc()).queryForList(anyString(), eq(String.class));
		verify(harness.jdbc(), times(5)).update(anyString(), anyString());
	}

	@Test
	@DisplayName("detach query failure is absorbed")
	void detachQueryFailureAbsorbed() throws Exception {
		Harness harness = harness();
		when(harness.jdbc().queryForList(anyString(), eq(String.class)))
				.thenThrow(new RuntimeException("db down"));

		harness.janitor().detachExpiredReplayPartitions();
	}

	@Test
	@DisplayName("purge failures are absorbed individually")
	void purgeFailuresAbsorbed() throws Exception {
		Harness harness = harness();
		when(harness.jdbc().update(anyString(), anyString())).thenThrow(new RuntimeException("db down"));

		harness.janitor().archiveOldAlerts();
		harness.janitor().purgeOldArchive();
		harness.janitor().purgeOldLogs();
		harness.janitor().purgeOldDedupes();
	}

	@Test
	@DisplayName("lost lock skips the tick")
	void lostLockSkipsTick() throws Exception {
		DataSource dataSource = mock(DataSource.class);
		Connection connection = mock(Connection.class);
		java.sql.PreparedStatement lockStatement = mock(java.sql.PreparedStatement.class);
		java.sql.ResultSet lockRows = mock(java.sql.ResultSet.class);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.prepareStatement(anyString())).thenReturn(lockStatement);
		when(lockStatement.executeQuery()).thenReturn(lockRows);
		when(lockRows.next()).thenReturn(true);
		when(lockRows.getBoolean(1)).thenReturn(false);
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		RetentionJanitor janitor = new RetentionJanitor(jdbc, dataSource, MaintenanceProperties.DEFAULTS);

		janitor.retain();

		verify(jdbc, never()).queryForList(anyString(), eq(String.class));
	}

	@Test
	@DisplayName("disabled kill-switch skips everything")
	void disabledSkipsEverything() throws Exception {
		DataSource dataSource = mock(DataSource.class);
		RetentionJanitor janitor = new RetentionJanitor(mock(JdbcTemplate.class), dataSource,
				new MaintenanceProperties(false, 1000));

		janitor.retain();

		verify(dataSource, never()).getConnection();
	}

	@Test
	@DisplayName("datasource outage skips the tick")
	void datasourceOutageSkipsTick() throws Exception {
		DataSource dataSource = mock(DataSource.class);
		when(dataSource.getConnection()).thenThrow(new SQLException("db down"));
		RetentionJanitor janitor =
				new RetentionJanitor(mock(JdbcTemplate.class), dataSource, MaintenanceProperties.DEFAULTS);

		janitor.retain();
	}

	@Test
	@DisplayName("FIN-B36: partition detach is concurrent, never blocking")
	void detachIsConcurrent() throws Exception {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		DataSource dataSource = mock(DataSource.class);
		when(jdbc.queryForList(anyString(), eq(String.class)))
				.thenReturn(List.of("replay_store_2020_01"));
		RetentionJanitor janitor = new RetentionJanitor(jdbc, dataSource, MaintenanceProperties.DEFAULTS);

		janitor.detachExpiredReplayPartitions();

		verify(jdbc).execute(eq("ALTER TABLE replay_store DETACH PARTITION replay_store_2020_01 CONCURRENTLY"));
	}

	@Test
	@DisplayName("successful tick records the last-tick gauge")
	void successfulTickRecordsGauge() throws Exception {
		Harness harness = harness();
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		harness.janitor().setMeterRegistry(registry);

		harness.janitor().retain();

		assertThat(registry.get("cacherelay.job.last_tick_seconds")
				.tag("job", "retention-janitor").gauge().value()).isPositive();
	}
}
