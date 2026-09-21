package io.github.kxng0109.cacherelay.ledger.queue;

import io.github.kxng0109.cacherelay.ledger.LedgerStagingRepository;
import io.github.kxng0109.cacherelay.ledger.SpillwayJournalManager;
import io.github.kxng0109.cacherelay.ledger.TokenUsageEvent;
import io.github.kxng0109.cacherelay.ledger.UsageLedgerEntry;
import io.github.kxng0109.cacherelay.ledger.UsageLedgerRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("MicroBatchLedgerWriter Unit Test Suite")
class MicroBatchLedgerWriterTest {

	private DisruptorUsageLedgerQueue queue;
	private UsageLedgerRepository repository;
	private SpillwayJournalManager spillwayJournal;
	private MicroBatchLedgerWriter writer;

	@BeforeEach
	void setUp() {
		spillwayJournal = mock(SpillwayJournalManager.class);
		queue = new DisruptorUsageLedgerQueue(1024, spillwayJournal);
		repository = mock(UsageLedgerRepository.class);
		writer = new MicroBatchLedgerWriter(
				queue,
				repository,
				spillwayJournal,
				new SimpleMeterRegistry(),
				100,
				50,
				30_000L,
				60_000L,
				5
		);
	}

	@AfterEach
	void tearDown() {
		if (writer.isRunning()) {
			writer.stop();
		}
	}

	@Test
	@DisplayName("Should start and stop lifecycle properly and handle idempotent calls")
	void shouldManageLifecycle() {
		assertThat(writer.isRunning()).isFalse();
		writer.stop(); // stop when not running

		writer.start();
		assertThat(writer.isRunning()).isTrue();
		writer.start(); // second start is idempotent

		writer.stop();
		assertThat(writer.isRunning()).isFalse();

		// Constructor with null registry
		MicroBatchLedgerWriter nullRegWriter = new MicroBatchLedgerWriter(
				queue, repository, spillwayJournal, null, 100, 50, 30_000L, 60_000L, 5
		);
		assertThat(nullRegWriter).isNotNull();
	}

	@Test
	@DisplayName("Flush cycle successfully drains queue and persists batch")
	void shouldFlushBatchSuccessfully() {
		TokenUsageEvent e1 = createEvent("tenant-1");
		TokenUsageEvent e2 = createEvent("tenant-2");
		queue.offer(e1);
		queue.offer(e2);

		int flushed = writer.flushCycle();

		assertThat(flushed).isEqualTo(2);
		verify(repository).saveAll(anyList());
		verifyNoInteractions(spillwayJournal);
	}

	@Test
	@DisplayName("Flush cycle diverts to spillway journal on database error")
	void shouldSpillToDiskOnDatabaseFailure() {
		TokenUsageEvent e1 = createEvent("tenant-fail");
		queue.offer(e1);

		doThrow(new RuntimeException("PostgreSQL connection refused"))
				.when(repository).saveAll(anyList());

		int flushed = writer.flushCycle();

		assertThat(flushed).isEqualTo(0);
		verify(spillwayJournal).appendBatch(anyList(), anyString());
	}

	@Test
	@DisplayName("Flush cycle on empty queue returns zero cleanly")
	void shouldHandleEmptyQueueFlush() {
		int flushed = writer.flushCycle();
		assertThat(flushed).isEqualTo(0);
		verifyNoInteractions(repository);
	}

	@Test
	@DisplayName("Replay cycle executes when running and skips when stopped")
	void shouldExecuteReplayCycle() {
		// When stopped
		writer.replayCycle();
		verifyNoInteractions(spillwayJournal);

		// When running
		writer.start();
		doAnswer(invocation -> {
			java.util.function.Consumer<TokenUsageEvent> consumer = invocation.getArgument(0);
			consumer.accept(createEvent("replayed-tenant"));
			return 1;
		}).when(spillwayJournal).replayPendingRecords(any());

		writer.replayCycle();
		verify(repository).save(any(io.github.kxng0109.cacherelay.ledger.UsageLedgerEntry.class));

		// When replay throws exception
		doThrow(new RuntimeException("DB unreachable")).when(spillwayJournal).replayPendingRecords(any());
		writer.replayCycle(); // should log debug and not throw
	}

	@Test
	@DisplayName("Handles zero-token, zero-cost, and oversized token metrics cleanly")
	void shouldHandleZeroAndOversizedMetrics() {
		TokenUsageEvent zeroEvent = new TokenUsageEvent(
				UUID.randomUUID(), "tenant-zero", "", "   ",
				0, 0, 0, 0, 0, Instant.now()
		);
		TokenUsageEvent oversizedEvent = new TokenUsageEvent(
				UUID.randomUUID(), "tenant-huge", null, null,
				Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 100, 5000, Instant.now()
		);
		queue.offer(zeroEvent);
		queue.offer(oversizedEvent);

		int flushed = writer.flushCycle();
		assertThat(flushed).isEqualTo(2);
		verify(repository).saveAll(anyList());
	}

	private static TokenUsageEvent createEvent(String tenant) {
		return new TokenUsageEvent(
				UUID.randomUUID(), tenant, "openai", "gpt-5.6-luna",
				100, 50, 150, 200, 1500, Instant.now()
		);
	}

	@Test
	@DisplayName("Flush cycle dedupes with one batch existence check (no per-row SELECT)")
	void shouldDedupeBatchWithSingleExistenceCheck() {
		UUID dupId = UUID.randomUUID();
		TokenUsageEvent dupA = new TokenUsageEvent(
				dupId, "tenant-1", "openai", "gpt-5.6-luna", 100, 50, 150, 200, 1500, Instant.now());
		TokenUsageEvent dupB = new TokenUsageEvent(
				dupId, "tenant-1", "openai", "gpt-5.6-luna", 100, 50, 150, 200, 1500, Instant.now());
		TokenUsageEvent fresh = createEvent("tenant-2");
		queue.offer(dupA);
		queue.offer(dupB);
		queue.offer(fresh);

		when(repository.findByRequestIdIn(anyCollection())).thenReturn(List.of());

		int flushed = writer.flushCycle();

		assertThat(flushed).isEqualTo(2);
		verify(repository, times(1)).findByRequestIdIn(anyCollection());
		ArgumentCaptor<List<UsageLedgerEntry>> savedCaptor = ArgumentCaptor.forClass(List.class);
		verify(repository).saveAll(savedCaptor.capture());
		assertThat(savedCaptor.getValue())
				.extracting(entry -> entry.getRequestId())
				.containsExactlyInAnyOrder(dupId, fresh.requestId());
	}

	@Test
	@DisplayName("Flush cycle drops stored ids and null ids before persisting")
	void shouldFilterStoredAndNullIds() {
		UUID storedId = UUID.randomUUID();
		TokenUsageEvent stored = new TokenUsageEvent(
				storedId, "tenant-1", "openai", "gpt-4o", 10, 5, 15, 100, 100, Instant.now());
		TokenUsageEvent nullId = new TokenUsageEvent(
				null, "tenant-1", "openai", "gpt-4o", 10, 5, 15, 100, 100, Instant.now());
		TokenUsageEvent fresh = createEvent("tenant-2");
		queue.offer(stored);
		queue.offer(nullId);
		queue.offer(fresh);

		UsageLedgerEntry storedRow = mock(UsageLedgerEntry.class);
		when(storedRow.getRequestId()).thenReturn(storedId);
		when(repository.findByRequestIdIn(anyCollection())).thenReturn(List.of(storedRow));

		int flushed = writer.flushCycle();

		assertThat(flushed).isEqualTo(1);
		ArgumentCaptor<List<UsageLedgerEntry>> savedCaptor = ArgumentCaptor.forClass(List.class);
		verify(repository).saveAll(savedCaptor.capture());
		assertThat(savedCaptor.getValue()).hasSize(1);
	}

	@Test
	@DisplayName("Flush cycle persists everything when the existence check fails")
	void shouldPersistAllOnExistenceFailure() {
		queue.offer(createEvent("tenant-1"));
		queue.offer(createEvent("tenant-2"));
		when(repository.findByRequestIdIn(anyCollection()))
				.thenThrow(new RuntimeException("read replica down"));

		int flushed = writer.flushCycle();

		assertThat(flushed).isEqualTo(2);
		verify(repository).saveAll(anyList());
	}

	@Test
	@DisplayName("Flush cycle falls back to the journal when staging fails")
	void shouldSpillWhenStagingFails() {
		LedgerStagingRepository staging = mock(LedgerStagingRepository.class);
		writer.setStagingRepository(staging);
		queue.offer(createEvent("tenant-1"));
		when(repository.findByRequestIdIn(anyCollection())).thenReturn(List.of());
		doThrow(new RuntimeException("DB down")).when(repository).saveAll(anyList());
		doThrow(new RuntimeException("staging down")).when(staging).saveAll(anyList());

		int flushed = writer.flushCycle();

		assertThat(flushed).isZero();
		verify(spillwayJournal).appendBatch(anyList(), anyString());
	}

	@Test
	@DisplayName("Flush cycle treats staged duplicates as benign success")
	void shouldTreatStagingDuplicatesAsSuccess() {
		LedgerStagingRepository staging = mock(LedgerStagingRepository.class);
		writer.setStagingRepository(staging);
		queue.offer(createEvent("tenant-1"));
		when(repository.findByRequestIdIn(anyCollection())).thenReturn(List.of());
		doThrow(new RuntimeException("DB down")).when(repository).saveAll(anyList());
		doThrow(new DataIntegrityViolationException("duplicate")).when(staging).saveAll(anyList());

		int flushed = writer.flushCycle();

		assertThat(flushed).isZero();
		verify(spillwayJournal, never()).appendBatch(anyList(), anyString());
	}

	@Test
	@DisplayName("Stop without start is a no-op")
	void shouldStopWithoutStart() {
		MicroBatchLedgerWriter idle = new MicroBatchLedgerWriter(
				queue, repository, spillwayJournal, null, 100, 50, 30_000L, 60_000L, 5);

		assertThatNoException().isThrownBy(idle::stop);
	}

	@Test
	@DisplayName("Flush cycle spills to the journal without staging when none is wired")
	void shouldSpillWithoutStaging() {
		queue.offer(createEvent("tenant-1"));
		when(repository.findByRequestIdIn(anyCollection())).thenReturn(List.of());
		doThrow(new RuntimeException("DB down")).when(repository).saveAll(anyList());

		int flushed = writer.flushCycle();

		assertThat(flushed).isZero();
		verify(spillwayJournal).appendBatch(anyList(), anyString());
	}

	@Test
	@DisplayName("Flush cycle returns zero when every id is null")
	void shouldReturnZeroForAllNullIds() {
		queue.offer(new TokenUsageEvent(
				null, "tenant-1", "openai", "gpt-4o", 10, 5, 15, 100, 100, Instant.now()));
		queue.offer(new TokenUsageEvent(
				null, "tenant-2", "openai", "gpt-4o", 10, 5, 15, 100, 100, Instant.now()));

		int flushed = writer.flushCycle();

		assertThat(flushed).isZero();
		verify(repository, never()).saveAll(anyList());
		verify(repository, never()).findByRequestIdIn(anyCollection());
	}

	@Test
	@DisplayName("Flush cycle persists everything when the existence check returns null")
	void shouldPersistAllOnNullExistence() {
		queue.offer(createEvent("tenant-1"));

		int flushed = writer.flushCycle();

		assertThat(flushed).isEqualTo(1);
		verify(repository).saveAll(anyList());
	}

	@Test
	@DisplayName("Start then stop shuts down cleanly and reports not running")
	void shouldStartAndStopCleanly() {
		writer.start();
		assertThat(writer.isRunning()).isTrue();

		writer.stop();

		assertThat(writer.isRunning()).isFalse();
	}

	@Test
	@DisplayName("Stop forces shutdown when a flush is stuck")
	void shouldShutdownNowOnStuckFlush() throws Exception {
		MicroBatchLedgerWriter stuckWriter = new MicroBatchLedgerWriter(
				queue, repository, spillwayJournal, null, 100, 10, 60_000L, 60_000L, 1);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		when(repository.findByRequestIdIn(anyCollection())).thenAnswer(invocation -> {
			entered.countDown();
			if (!release.await(30, TimeUnit.SECONDS)) {
				throw new IllegalStateException("flush not released");
			}
			return List.of();
		});
		queue.offer(createEvent("tenant-1"));
		stuckWriter.start();
		try {
			assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

			stuckWriter.stop();

			assertThat(stuckWriter.isRunning()).isFalse();
		} finally {
			release.countDown();
		}
	}

	@Test
	@DisplayName("Stop under interrupt restores the interrupt flag")
	void shouldRestoreInterruptOnStop() {
		writer.start();
		try {
			Thread.currentThread().interrupt();
			writer.stop();

			assertThat(Thread.interrupted()).isTrue();
			assertThat(writer.isRunning()).isFalse();
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	@DisplayName("Flush cycle records flush latency, batch size, and queue depth")
	void shouldRecordFlushMetrics() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		MicroBatchLedgerWriter metered = new MicroBatchLedgerWriter(
				queue, repository, spillwayJournal, registry, 100, 50, 30_000L, 60_000L, 5);
		queue.offer(createEvent("tenant-m"));
		queue.offer(createEvent("tenant-m"));
		queue.offer(createEvent("tenant-m"));

		assertThat(registry.get("cacherelay.ledger.queue.depth").gauge().value()).isEqualTo(3.0);

		int flushed = metered.flushCycle();

		assertThat(flushed).isEqualTo(3);
		assertThat(registry.get("cacherelay.ledger.flush.seconds").timer().count()).isEqualTo(1);
		assertThat(registry.get("cacherelay.ledger.batch.size").summary().max()).isEqualTo(3.0);
		assertThat(registry.get("cacherelay.ledger.queue.depth").gauge().value()).isEqualTo(0.0);
	}
}
