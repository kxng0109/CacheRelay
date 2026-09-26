package io.github.kxng0109.cacherelay.ledger.queue;

import io.github.kxng0109.cacherelay.ledger.SpillwayJournalManager;
import io.github.kxng0109.cacherelay.ledger.TokenUsageEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@DisplayName("DisruptorUsageLedgerQueue Unit Test Suite")
class DisruptorUsageLedgerQueueTest {

	private SpillwayJournalManager spillwayJournal;
	private DisruptorUsageLedgerQueue queue;

	@BeforeEach
	void setUp() {
		spillwayJournal = mock(SpillwayJournalManager.class);
		// Small power-of-two queue for testing bounds: capacity = 1024
		queue = new DisruptorUsageLedgerQueue(1024, spillwayJournal);
	}

	@Test
	@DisplayName("Should offer and drain events in FIFO order and respect maxElements bound")
	void shouldOfferAndDrainEvents() throws Exception {
		TokenUsageEvent e1 = createEvent("tenant-1");
		TokenUsageEvent e2 = createEvent("tenant-2");

		assertThat(queue.offer(e1)).isTrue();
		assertThat(queue.offer(e2)).isTrue();
		assertThat(queue.size()).isEqualTo(2);

		// Drain only 1 element (tests drained == maxElements branch)
		List<TokenUsageEvent> partialDrain = new ArrayList<>();
		int partialCount = queue.drainTo(partialDrain, 1);
		assertThat(partialCount).isEqualTo(1);
		assertThat(partialDrain).containsExactly(e1);
		assertThat(queue.size()).isEqualTo(1);

		// Drain remaining
		List<TokenUsageEvent> remainingDrain = new ArrayList<>();
		int remainingCount = queue.drainTo(remainingDrain, 10);
		assertThat(remainingCount).isEqualTo(1);
		assertThat(remainingDrain).containsExactly(e2);
		assertThat(queue.size()).isEqualTo(0);

		// Test in-flight slot (event == null branch)
		java.lang.reflect.Field prodSeqField = DisruptorUsageLedgerQueue.class.getDeclaredField("producerSequence");
		prodSeqField.setAccessible(true);
		java.util.concurrent.atomic.AtomicLong prodSeq = (java.util.concurrent.atomic.AtomicLong) prodSeqField.get(queue);

		java.lang.reflect.Field consSeqField = DisruptorUsageLedgerQueue.class.getDeclaredField("consumerSequence");
		consSeqField.setAccessible(true);
		java.util.concurrent.atomic.AtomicLong consSeq = (java.util.concurrent.atomic.AtomicLong) consSeqField.get(queue);

		// Simulate head < tail but buffer slot is null (in-flight producer)
		consSeq.set(0);
		prodSeq.set(1);
		List<TokenUsageEvent> inFlightDrain = new ArrayList<>();
		int inFlightCount = queue.drainTo(inFlightDrain, 10);
		assertThat(inFlightCount).isEqualTo(0); // breaks out because slot event is null
	}

	@Test
	@DisplayName("Should overflow to spillway journal when ring buffer is saturated")
	void shouldOverflowWhenSaturated() {
		// Create a tiny queue with capacity 1024
		DisruptorUsageLedgerQueue smallQueue = new DisruptorUsageLedgerQueue(1024, spillwayJournal);

		for (int i = 0; i < 1024; i++) {
			smallQueue.offer(createEvent("tenant-" + i));
		}

		// 1025th event must trigger spillway overflow via the async spiller batch
		TokenUsageEvent overflowEvent = createEvent("tenant-overflow");
		boolean offered = smallQueue.offer(overflowEvent);

		assertThat(offered).isFalse();
		verify(spillwayJournal, timeout(5000)).appendBatch(anyList(), anyString());
	}

	@Test
	@SuppressWarnings("DataFlowIssue")
	@DisplayName("Offering null returns false without throwing exception")
	void shouldRejectNullOffer() {
		assertThat(queue.offer(null)).isFalse();
	}

	@Test
	@DisplayName("Capacity rounding and accessors behave deterministically")
	void shouldTestCapacityCalculations() {
		// Exact power of two
		DisruptorUsageLedgerQueue q1 = new DisruptorUsageLedgerQueue(2048, spillwayJournal);
		assertThat(q1.capacity()).isEqualTo(2048);

		// Non-power of two rounds up
		DisruptorUsageLedgerQueue q2 = new DisruptorUsageLedgerQueue(1500, spillwayJournal);
		assertThat(q2.capacity()).isEqualTo(2048);

		// Below minimum rounds up to 1024
		DisruptorUsageLedgerQueue q3 = new DisruptorUsageLedgerQueue(100, spillwayJournal);
		assertThat(q3.capacity()).isEqualTo(1024);
	}

	private static TokenUsageEvent createEvent(String tenant) {
		return new TokenUsageEvent(
				UUID.randomUUID(), tenant, "openai", "gpt-5.6-sol",
				100, 50, 150, 120, 1000, Instant.now()
		);
	}

	@Test
	@DisplayName("Saturated offers never block the request thread on spill I/O")
	void saturatedOfferNeverBlocksOnSpillIo() {
		SpillwayJournalManager blockingJournal = mock(SpillwayJournalManager.class);
		doAnswer(invocation -> {
			try {
				Thread.sleep(30_000L);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
			return null;
		}).when(blockingJournal).append(any(TokenUsageEvent.class), anyString());
		doAnswer(invocation -> {
			try {
				Thread.sleep(30_000L);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
			return null;
		}).when(blockingJournal).appendBatch(anyList(), anyString());
		DisruptorUsageLedgerQueue saturated = new DisruptorUsageLedgerQueue(1024, blockingJournal);
		for (int i = 0; i < 1024; i++) {
			assertThat(saturated.offer(createEvent("tenant-" + i))).as("fill").isTrue();
		}

		assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
			for (int i = 0; i < 100; i++) {
				assertThat(saturated.offer(createEvent("spill-" + i))).as("spill returns").isFalse();
			}
		});
	}

	@Test
	@SuppressWarnings("unchecked")
	@DisplayName("Dead-producer holes are skipped without wedging the ring")
	void deadProducerHoleSkipped() throws Exception {
		DisruptorUsageLedgerQueue holey = new DisruptorUsageLedgerQueue(1024, spillwayJournal);
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			TokenUsageEvent event = createEvent("tenant-" + i);
			ids.add(event.requestId());
			assertThat(holey.offer(event)).isTrue();
		}
		// Crash a producer between sequence claim and slot write: the slot stays empty
		// while the tail has moved past it.
		Field bufferField = DisruptorUsageLedgerQueue.class.getDeclaredField("buffer");
		bufferField.setAccessible(true);
		AtomicReferenceArray<TokenUsageEvent> buffer =
				(AtomicReferenceArray<TokenUsageEvent>) bufferField.get(holey);
		buffer.set(2, null);

		List<TokenUsageEvent> drained = new ArrayList<>();
		int count = holey.drainTo(drained, 10);

		assertThat(count).as("drained across the hole").isEqualTo(4);
		assertThat(drained).extracting(TokenUsageEvent::requestId)
				.containsExactly(ids.get(0), ids.get(1), ids.get(3), ids.get(4));
		TokenUsageEvent fresh = createEvent("fresh");
		assertThat(holey.offer(fresh)).as("offer after hole").isTrue();
		List<TokenUsageEvent> more = new ArrayList<>();
		assertThat(holey.drainTo(more, 10)).as("ring healthy after hole").isEqualTo(1);
		assertThat(more.getFirst().requestId()).isEqualTo(fresh.requestId());
	}

	@Test
	@DisplayName("FS-B12: offers divert to the journal once the writer stops accepting")
	void offerWhenNotAcceptingSpillsToJournal() {
		DisruptorUsageLedgerQueue closed = new DisruptorUsageLedgerQueue(1024, spillwayJournal);
		closed.setAccepting(false);

		TokenUsageEvent event = createEvent("tenant-9");

		assertThat(closed.offer(event)).isFalse();
		verify(spillwayJournal).append(event, "Ledger writer stopped");
	}

	@Test
	@DisplayName("FS-B12: a full spill buffer falls back to a counted synchronous append")
	void spillBufferSaturationFallsBackSynchronously() throws Exception {
		DisruptorUsageLedgerQueue saturated = new DisruptorUsageLedgerQueue(1024, spillwayJournal);
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		saturated.setMeterRegistry(registry);
		producerSequenceOf(saturated).set(1024L);
		ArrayBlockingQueue<TokenUsageEvent> spill = spillBufferOf(saturated);
		for (int i = 0; i < DisruptorUsageLedgerQueue.SPILL_BUFFER_CAPACITY; i++) {
			assertThat(spill.offer(createEvent("fill"))).isTrue();
		}

		TokenUsageEvent victim = createEvent("victim");

		assertThat(saturated.offer(victim)).isFalse();
		verify(spillwayJournal).append(victim, "Spill buffer saturated");
		assertThat(registry.get("cacherelay.ledger.spillover.sync_fallback")
				.counter().count()).isEqualTo(1.0);
		saturated.drainSpillover();
	}

	@Test
	@DisplayName("FS-B12: failed spill batches are re-queued and delivered once the journal recovers")
	void spillLoopRequeuesFailedBatches() throws Exception {
		DisruptorUsageLedgerQueue recovering = new DisruptorUsageLedgerQueue(1024, spillwayJournal);
		doThrow(new RuntimeException("disk hiccup")).doNothing()
				.when(spillwayJournal).appendBatch(anyList(), anyString());
		producerSequenceOf(recovering).set(1024L);

		TokenUsageEvent event = createEvent("requeue");

		assertThat(recovering.offer(event)).isFalse();
		verify(spillwayJournal, timeout(5000).times(2)).appendBatch(anyList(), anyString());
		recovering.drainSpillover();
		verify(spillwayJournal, never()).append(any(), anyString());
	}

	@Test
	@DisplayName("FS-B12: interrupting the spiller stops its thread promptly")
	void spillerInterruptStopsThread() throws Exception {
		DisruptorUsageLedgerQueue interruptible =
				new DisruptorUsageLedgerQueue(1024, spillwayJournal);
		producerSequenceOf(interruptible).set(1024L);
		assertThat(interruptible.offer(createEvent("kick"))).isFalse();

		Thread spiller = spillerThreadOf(interruptible);
		assertThat(spiller).isNotNull();

		spiller.interrupt();
		spiller.join(5000L);

		assertThat(spiller.isAlive()).isFalse();
		interruptible.drainSpillover();
	}

	@Test
	@DisplayName("FS-B12: drainSpillover persists buffered events without a running spiller")
	void drainSpilloverDrainsRemainder() throws Exception {
		DisruptorUsageLedgerQueue idle = new DisruptorUsageLedgerQueue(1024, spillwayJournal);
		ArrayBlockingQueue<TokenUsageEvent> spill = spillBufferOf(idle);
		TokenUsageEvent first = createEvent("a");
		TokenUsageEvent second = createEvent("b");
		spill.offer(first);
		spill.offer(second);

		idle.drainSpillover();

		verify(spillwayJournal).appendBatch(
				argThat(list -> list.size() == 2
						&& list.get(0).requestId().equals(first.requestId())),
				eq("Shutdown spill drain"));
	}

	@Test
	@DisplayName("FS-B12: interrupting the drain caller preserves the interrupt flag")
	void drainSpilloverJoinInterruptRestoresFlag() throws Exception {
		DisruptorUsageLedgerQueue live = new DisruptorUsageLedgerQueue(1024, spillwayJournal);
		producerSequenceOf(live).set(1024L);
		assertThat(live.offer(createEvent("kick"))).isFalse();
		assertThat(spillerThreadOf(live)).isNotNull();

		Thread.currentThread().interrupt();
		try {
			live.drainSpillover();
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		} finally {
			Thread.interrupted();
			live.drainSpillover();
		}
	}

	@Test
	@DisplayName("FS-B12: draining with a zero bound retrieves nothing")
	void drainToZeroMaxDrainsNothing() {
		assertThat(queue.offer(createEvent("tenant-1"))).isTrue();

		List<TokenUsageEvent> target = new ArrayList<>();

		assertThat(queue.drainTo(target, 0)).isEqualTo(0);
		assertThat(target).isEmpty();
	}

	@Test
	@DisplayName("FS-B12: repeat saturation reuses the single spiller thread")
	void repeatSaturationReusesSpillerThread() throws Exception {
		DisruptorUsageLedgerQueue busy = new DisruptorUsageLedgerQueue(1024, spillwayJournal);
		producerSequenceOf(busy).set(1024L);

		assertThat(busy.offer(createEvent("one"))).isFalse();
		Thread first = spillerThreadOf(busy);
		assertThat(busy.offer(createEvent("two"))).isFalse();

		assertThat(spillerThreadOf(busy)).isSameAs(first);
		busy.drainSpillover();
	}

	private static AtomicLong producerSequenceOf(DisruptorUsageLedgerQueue queue) throws Exception {
		Field field = DisruptorUsageLedgerQueue.class.getDeclaredField("producerSequence");
		field.setAccessible(true);
		return (AtomicLong) field.get(queue);
	}

	private static ArrayBlockingQueue<TokenUsageEvent> spillBufferOf(
			DisruptorUsageLedgerQueue queue) throws Exception {
		Field field = DisruptorUsageLedgerQueue.class.getDeclaredField("spillBuffer");
		field.setAccessible(true);
		return (ArrayBlockingQueue<TokenUsageEvent>) field.get(queue);
	}

	private static Thread spillerThreadOf(DisruptorUsageLedgerQueue queue) throws Exception {
		Field field = DisruptorUsageLedgerQueue.class.getDeclaredField("spillerThread");
		field.setAccessible(true);
		return (Thread) field.get(queue);
	}
}
