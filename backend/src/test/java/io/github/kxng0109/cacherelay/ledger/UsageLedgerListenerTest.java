package io.github.kxng0109.cacherelay.ledger;

import io.github.kxng0109.cacherelay.ledger.queue.DisruptorUsageLedgerQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UsageLedgerListener}: events are offered to the ring buffer
 * (batched persistence lives in the writer) and the listener never throws.
 */
@DisplayName("UsageLedgerListener")
@SuppressWarnings("DataFlowIssue")
class UsageLedgerListenerTest {

	@Test
	@DisplayName("offers completed requests to the ring buffer")
	void offersEvent() {
		DisruptorUsageLedgerQueue queue = mock(DisruptorUsageLedgerQueue.class);
		when(queue.offer(any())).thenReturn(true);
		UsageLedgerListener listener = new UsageLedgerListener(queue);

		TokenUsageEvent event = event();
		listener.onTokenUsage(event);

		verify(queue).offer(event);
	}

	@Test
	@DisplayName("a spillover offer is logged, never thrown")
	void spilloverNeverThrows() {
		DisruptorUsageLedgerQueue queue = mock(DisruptorUsageLedgerQueue.class);
		when(queue.offer(any())).thenReturn(false);
		UsageLedgerListener listener = new UsageLedgerListener(queue);

		assertDoesNotThrow(() -> listener.onTokenUsage(event()));
	}

	@Test
	@DisplayName("a queue failure is logged, never thrown")
	void queueFailureNeverThrows() {
		DisruptorUsageLedgerQueue queue = mock(DisruptorUsageLedgerQueue.class);
		when(queue.offer(any()))
				.thenThrow(new RuntimeException("queue broken"));
		UsageLedgerListener listener = new UsageLedgerListener(queue);

		assertDoesNotThrow(() -> listener.onTokenUsage(event()));
	}

	@Test
	@DisplayName("a null event is tolerated")
	void nullEventTolerated() {
		DisruptorUsageLedgerQueue queue = new DisruptorUsageLedgerQueue(
				1024, mock(SpillwayJournalManager.class));
		UsageLedgerListener listener = new UsageLedgerListener(queue);

		assertDoesNotThrow(() -> listener.onTokenUsage(null));
	}

	private static TokenUsageEvent event() {
		return new TokenUsageEvent(
				UUID.randomUUID(), "owner-1", "openai", "gpt-5.6-sol",
				10, 5, 15, 100, 4200, Instant.now()
		);
	}
}
