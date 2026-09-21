package io.github.kxng0109.cacherelay.ledger;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("LedgerStagingDrainer drain paths without a database")
class LedgerStagingDrainerUnitTest {

	private final LedgerStagingRepository staging = mock(LedgerStagingRepository.class);
	private final UsageLedgerRepository ledger = mock(UsageLedgerRepository.class);
	private final AbstractPlatformTransactionManager transactionManager = new NoOpTransactionManager();

	/**
	 * Executes transaction callbacks inline without any resource, so drain paths are
	 * unit-testable without PostgreSQL.
	 */
	private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {
		@Override
		protected Object doGetTransaction() {
			return new Object();
		}

		@Override
		protected void doBegin(Object transaction, TransactionDefinition definition) {
		}

		@Override
		protected void doCommit(DefaultTransactionStatus status) {
		}

		@Override
		protected void doRollback(DefaultTransactionStatus status) {
		}
	}

	private final LedgerStagingDrainer drainer =
			new LedgerStagingDrainer(staging, ledger, transactionManager, new SimpleMeterRegistry());

	private static TokenUsageEvent eventFor(UUID requestId) {
		return new TokenUsageEvent(
				requestId, "owner-1", "openai", "gpt-5.6-sol",
				100, 50, 150, 200, 1500, Instant.now(),
				100, 0, 0, 0, 1500, 1500, null
		);
	}

	private static LedgerStagingEntry claimedRow(UUID requestId, String claimedBy) {
		LedgerStagingEntry row = LedgerStagingEntry.pendingFrom(eventFor(requestId));
		row.setStatus("CLAIMED");
		row.setClaimedBy(claimedBy);
		return row;
	}

	private String drainerPodId() {
		return drainerPodId(drainer);
	}

	private static String drainerPodId(LedgerStagingDrainer target) {
		try {
			java.lang.reflect.Field field = LedgerStagingDrainer.class.getDeclaredField("podId");
			field.setAccessible(true);
			return (String) field.get(target);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private void stubClaim(LedgerStagingEntry row) {
		when(staging.resetStaleClaims(any())).thenReturn(0);
		when(staging.claimBatch(anyString(), anyInt())).thenReturn(List.of(row));
	}

	@Test
	@DisplayName("resolvePodId prefers the hostname and falls back to a random pod id")
	void resolvePodIdBranches() {
		assertThat(LedgerStagingDrainer.resolvePodId("worker-7")).isEqualTo("worker-7");
		assertThat(LedgerStagingDrainer.resolvePodId(null)).startsWith("pod-");
		assertThat(LedgerStagingDrainer.resolvePodId("   ")).startsWith("pod-");
	}

	@Test
	@DisplayName("missing rows skip without touching the ledger")
	void missingRowSkips() {
		LedgerStagingEntry row = claimedRow(UUID.randomUUID(), drainerPodId());
		stubClaim(row);
		when(staging.findById(any())).thenReturn(Optional.empty());

		drainer.drain();

		verify(ledger, never()).save(any());
		verify(staging, never()).save(any());
	}

	@Test
	@DisplayName("failed rows park for retry with backoff")
	void failedRowParksForRetry() {
		UUID requestId = UUID.randomUUID();
		LedgerStagingEntry row = claimedRow(requestId, drainerPodId());
		stubClaim(row);
		when(staging.findById(any())).thenReturn(Optional.of(row));
		when(ledger.existsByRequestId(requestId))
				.thenThrow(new RuntimeException("db down"))
				.thenReturn(false);

		drainer.drain();

		verify(staging).save(row);
		assertThat(row.getStatus()).isEqualTo("PENDING");
		assertThat(row.getNextRetryAt()).isAfter(Instant.now().minusSeconds(60));
	}

	@Test
	@DisplayName("replayed rows are marked done")
	void replayedRowMarkedDone() {
		UUID requestId = UUID.randomUUID();
		LedgerStagingEntry row = claimedRow(requestId, drainerPodId());
		stubClaim(row);
		when(staging.findById(any())).thenReturn(Optional.of(row));

		drainer.drain();

		verify(ledger).save(any(UsageLedgerEntry.class));
		verify(staging).save(row);
		assertThat(row.getStatus()).isEqualTo(LedgerStagingEntry.DONE);
	}

	@Test
	@DisplayName("already-ledgered rows are benign duplicates")
	void duplicateRowMarkedDone() {
		UUID requestId = UUID.randomUUID();
		LedgerStagingEntry row = claimedRow(requestId, drainerPodId());
		stubClaim(row);
		when(staging.findById(any())).thenReturn(Optional.of(row));
		when(ledger.existsByRequestId(requestId)).thenReturn(true);

		drainer.drain();

		verify(ledger, never()).save(any());
		verify(staging).save(row);
		assertThat(row.getStatus()).isEqualTo(LedgerStagingEntry.DONE);
	}

	@Test
	@DisplayName("rows vanishing mid-drain are tolerated")
	void vanishingRowTolerated() {
		LedgerStagingEntry row = claimedRow(UUID.randomUUID(), drainerPodId());
		stubClaim(row);
		when(staging.findById(any())).thenReturn(Optional.of(row), Optional.empty());

		drainer.drain();

		verify(staging, never()).save(any());
	}

	@Test
	@DisplayName("metric failures never break the drain")
	void metricFailureTolerated() {
		MeterRegistry failingRegistry = mock(MeterRegistry.class);
		when(failingRegistry.counter(anyString(), any(String[].class)))
				.thenThrow(new RuntimeException("metrics down"));
		LedgerStagingDrainer failingMetricsDrainer =
				new LedgerStagingDrainer(staging, ledger, transactionManager, failingRegistry);
		LedgerStagingEntry row = claimedRow(UUID.randomUUID(), drainerPodId(failingMetricsDrainer));
		stubClaim(row);
		when(staging.findById(any())).thenReturn(Optional.of(row));

		failingMetricsDrainer.drain();

		verify(staging).save(any(LedgerStagingEntry.class));
	}
}
