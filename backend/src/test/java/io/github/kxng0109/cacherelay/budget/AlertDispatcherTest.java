package io.github.kxng0109.cacherelay.budget;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.sql.DataSource;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the alert dispatcher with stubbed outbox/client/datasource: sent/terminal/retryable
 * handling, poison ceiling, per-event failure isolation, lock contention, and the kill-switch.
 */
@DisplayName("AlertDispatcher")
class AlertDispatcherTest {

	private static final BudgetDetectionProperties ENABLED =
			new BudgetDetectionProperties(true, "http://alertmanager:9093", 100);

	private record Harness(DataSource dataSource, AlertEventRepository outbox, AlertmanagerClient client,
	                       AlertDispatcher dispatcher) {
	}

	private static Harness harness(List<AlertEvent> due) throws Exception {
		DataSource dataSource = mock(DataSource.class);
		Connection connection = mock(Connection.class);
		PreparedStatement lockStatement = mock(PreparedStatement.class);
		ResultSet lockRows = mock(ResultSet.class);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.prepareStatement(anyString())).thenReturn(lockStatement);
		when(lockStatement.executeQuery()).thenReturn(lockRows);
		when(lockRows.next()).thenReturn(true);
		when(lockRows.getBoolean(1)).thenReturn(true);
		AlertEventRepository outbox = mock(AlertEventRepository.class);
		when(outbox.claimDue(any(Instant.class), anyInt())).thenReturn(due);
		AlertmanagerClient client = mock(AlertmanagerClient.class);
		AlertDispatcher dispatcher =
				new AlertDispatcher(outbox, client, ENABLED, dataSource,
						mock(ApplicationEventPublisher.class));
		return new Harness(dataSource, outbox, client, dispatcher);
	}

	private static AlertEvent event() {
		return new AlertEvent("sha", "KEY:hex", "burn_static", "warning",
				Instant.now(), "{\"scope\":\"KEY:hex\"}", "50.00", "2026-09");
	}

	@Test
	@DisplayName("successful tick records the last-tick gauge")
	void successfulTickRecordsGauge() throws Exception {
		Harness harness = harness(List.of());
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		harness.dispatcher().setMeterRegistry(registry);

		harness.dispatcher().dispatch();

		assertThat(registry.get("cacherelay.job.last_tick_seconds")
				.tag("job", "alert-dispatcher").gauge().value()).isPositive();
	}

	@Test
	@DisplayName("null meter registry leaves dispatch unobserved without failing")
	void nullMeterRegistryLeavesDispatchUnobserved() throws Exception {
		Harness harness = harness(List.of());
		harness.dispatcher().setMeterRegistry(null);

		assertThatNoException().isThrownBy(() -> harness.dispatcher().dispatch());
	}

	@Test
	@DisplayName("sent alerts are marked and saved")
	void sentMarkedAndSaved() throws Exception {
		AlertEvent row = event();
		Harness harness = harness(List.of(row));
		when(harness.client().post(any())).thenReturn(new AlertmanagerClient.PostResult(true, false, false));

		harness.dispatcher().dispatch();

		assertThat(row.getStatus()).isEqualTo("SENT");
		verify(harness.outbox()).save(row);
	}

	@Test
	@DisplayName("terminal rejections kill the row immediately")
	void terminalKillsRow() throws Exception {
		AlertEvent row = event();
		Harness harness = harness(List.of(row));
		when(harness.client().post(any())).thenReturn(new AlertmanagerClient.PostResult(false, false, false));

		harness.dispatcher().dispatch();

		assertThat(row.getStatus()).isEqualTo("FAILED");
		verify(harness.outbox()).save(row);
	}

	@Test
	@DisplayName("FIN-B16: claim and outcomes run inside REQUIRES_NEW transactions")
	void claimUsesTransactionTemplate() throws Exception {
		AlertEvent row = event();
		Harness harness = harness(List.of(row));
		when(harness.client().post(any())).thenReturn(new AlertmanagerClient.PostResult(true, false, false));
		TransactionTemplate template = mock(TransactionTemplate.class);
		doAnswer(invocation -> {
			Consumer<TransactionStatus> work = invocation.getArgument(0);
			work.accept(mock(TransactionStatus.class));
			return null;
		}).when(template).executeWithoutResult(any());
		when(template.execute(any())).thenAnswer(invocation -> {
			TransactionCallback<?> callback = invocation.getArgument(0);
			return callback.doInTransaction(mock(TransactionStatus.class));
		});
		harness.dispatcher().setClaimTemplate(template);

		harness.dispatcher().dispatch();

		assertThat(row.getStatus()).isEqualTo("SENT");
		verify(template).execute(any());
		verify(template).executeWithoutResult(any());
	}

	@Test
	@DisplayName("FIN-B18: rows past the batch deadline re-arm without sending")
	void pastDeadlineRearmsWithoutSending() throws Exception {
		AlertEvent row = event();
		Harness harness = harness(List.of(row));

		harness.dispatcher().sendBatch(List.of(row), Instant.now().minusSeconds(1));

		verify(harness.client(), never()).post(any());
		assertThat(row.getStatus()).isEqualTo("PENDING");
		assertThat(row.getAttempts()).isEqualTo(1);
		verify(harness.outbox()).save(row);
	}

	@Test
	@DisplayName("FIN-B17: skipped delivery parks the row, never marks it sent")
	void skippedParksRow() throws Exception {
		AlertEvent row = event();
		Harness harness = harness(List.of(row));
		when(harness.client().post(any())).thenReturn(new AlertmanagerClient.PostResult(false, false, true));

		harness.dispatcher().dispatch();

		assertThat(row.getStatus()).isEqualTo("SKIPPED");
		verify(harness.outbox()).save(row);
	}

	@Test
	@DisplayName("retryable failures back off with a future retry horizon")
	void retryableBacksOff() throws Exception {
		AlertEvent row = event();
		Harness harness = harness(List.of(row));
		when(harness.client().post(any())).thenReturn(new AlertmanagerClient.PostResult(false, true, false));

		Instant before = Instant.now();
		harness.dispatcher().dispatch();

		assertThat(row.getStatus()).isEqualTo("PENDING");
		assertThat(row.getAttempts()).isEqualTo(1);
		assertThat(row.getNextRetryAt()).isAfterOrEqualTo(before);
		verify(harness.outbox()).save(row);
	}

	@Test
	@DisplayName("rows past the attempt ceiling die even on retryable failures")
	void ceilingKillsRow() throws Exception {
		AlertEvent row = event();
		for (int i = 0; i < AlertDispatcher.MAX_ATTEMPTS; i++) {
			row.backoff(Instant.now());
		}
		Harness harness = harness(List.of(row));
		when(harness.client().post(any())).thenReturn(new AlertmanagerClient.PostResult(false, true, false));

		harness.dispatcher().dispatch();

		assertThat(row.getStatus()).isEqualTo("FAILED");
	}

	@Test
	@DisplayName("one poison event does not block the rest of the batch")
	void poisonDoesNotBlockBatch() throws Exception {
		AlertEvent poison = event();
		AlertEvent healthy = event();
		Harness harness = harness(List.of(poison, healthy));
		when(harness.client().post(any()))
				.thenThrow(new RuntimeException("boom"))
				.thenReturn(new AlertmanagerClient.PostResult(true, false, false));

		harness.dispatcher().dispatch();

		verify(harness.outbox()).save(healthy);
	}

	@Test
	@DisplayName("lost lock skips the tick")
	void lostLockSkipsTick() throws Exception {
		DataSource dataSource = mock(DataSource.class);
		Connection connection = mock(Connection.class);
		PreparedStatement lockStatement = mock(PreparedStatement.class);
		ResultSet lockRows = mock(ResultSet.class);
		when(dataSource.getConnection()).thenReturn(connection);
		when(connection.prepareStatement(anyString())).thenReturn(lockStatement);
		when(lockStatement.executeQuery()).thenReturn(lockRows);
		when(lockRows.next()).thenReturn(true);
		when(lockRows.getBoolean(1)).thenReturn(false);
		AlertEventRepository outbox = mock(AlertEventRepository.class);
		AlertDispatcher dispatcher =
				new AlertDispatcher(outbox, mock(AlertmanagerClient.class), ENABLED, dataSource,
						mock(ApplicationEventPublisher.class));

		dispatcher.dispatch();

		verify(outbox, never()).claimDue(any(Instant.class), anyInt());
	}

	@Test
	@DisplayName("disabled kill-switch skips everything")
	void disabledSkipsEverything() throws Exception {
		DataSource dataSource = mock(DataSource.class);
		AlertDispatcher dispatcher = new AlertDispatcher(mock(AlertEventRepository.class),
				mock(AlertmanagerClient.class),
				new BudgetDetectionProperties(false, "", 100), dataSource,
				mock(ApplicationEventPublisher.class));

		dispatcher.dispatch();

		verify(dataSource, never()).getConnection();
	}

	@Test
	@DisplayName("datasource outage skips the tick")
	void datasourceOutageSkipsTick() throws Exception {
		DataSource dataSource = mock(DataSource.class);
		when(dataSource.getConnection()).thenThrow(new SQLException("db down"));
		AlertEventRepository outbox = mock(AlertEventRepository.class);
		AlertDispatcher dispatcher =
				new AlertDispatcher(outbox, mock(AlertmanagerClient.class), ENABLED, dataSource,
						mock(ApplicationEventPublisher.class));

		dispatcher.dispatch();

		verify(outbox, never()).claimDue(any(Instant.class), anyInt());
	}

	@Test
	@DisplayName("claim failure aborts the tick without throwing")
	void claimFailureAbortsTick() throws Exception {
		Harness harness = harness(List.of(event()));
		when(harness.outbox().claimDue(any(Instant.class), anyInt()))
				.thenThrow(new RuntimeException("db down"));

		harness.dispatcher().dispatch();

		verify(harness.client(), never()).post(any());
	}

	@Test
	@DisplayName("empty detector name routes to the Unknown alert")
	@SuppressWarnings("unchecked")
	void emptyDetectorRoutesUnknown() throws Exception {
		AlertEvent row = new AlertEvent("sha-e", "KEY:hex", "", "warning", Instant.now(), "{}", "", "");
		Harness harness = harness(List.of(row));
		ArgumentCaptor<List<Map<String, Object>>> batch = ArgumentCaptor.forClass(List.class);
		when(harness.client().post(batch.capture()))
				.thenReturn(new AlertmanagerClient.PostResult(true, false, false));

		harness.dispatcher().dispatch();

		Map<String, Object> labels = (Map<String, Object>) batch.getValue().get(0).get("labels");
		assertThat(labels.get("alertname")).isEqualTo("CacheRelayBudgetUnknown");
	}

	@Test
	@DisplayName("alert payload carries alertname, scope, detector, and severity labels")
	@SuppressWarnings("unchecked")
	void payloadCarriesLabels() throws Exception {
		AlertEvent row = event();
		Harness harness = harness(List.of(row));
		ArgumentCaptor<List<Map<String, Object>>> batch = ArgumentCaptor.forClass(List.class);
		when(harness.client().post(batch.capture()))
				.thenReturn(new AlertmanagerClient.PostResult(true, false, false));

		harness.dispatcher().dispatch();

		Map<String, Object> element = batch.getValue().get(0);
		Map<String, Object> labels = (Map<String, Object>) element.get("labels");
		assertThat(labels.get("alertname")).isEqualTo("CacheRelayBudgetBurnStatic");
		assertThat(labels.get("scope")).isEqualTo("KEY:hex");
		assertThat(labels.get("severity")).isEqualTo("warning");
	}
}
