package io.github.kxng0109.cacherelay.ledger;

import io.github.kxng0109.cacherelay.ledger.queue.DisruptorUsageLedgerQueue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Feeds {@link TokenUsageEvent} records into the lock-free ledger ring buffer (PERF-03).
 *
 * <p>The listener runs on the dedicated {@code ledgerExecutor} because of the {@code @Async}
 * annotation, and {@code offer} itself is sub-microsecond and never blocks (saturation
 * overflows to the durable spillway journal), so the publishing thread never waits on JDBC —
 * not even under {@code CallerRuns} saturation. Batching, single-SELECT deduplication,
 * flush-time metrics, and database-failure handling (spillway journal + replay) live in
 * {@link io.github.kxng0109.cacherelay.ledger.queue.MicroBatchLedgerWriter}: this listener
 * performs no repository access. The pre-PERF-03 per-row path (existence SELECT + save,
 * dead-letter file, staging-table replay) was removed as superseded; the shared staging
 * table continues to serve settlement gap rows via {@code LedgerStagingDrainer}. The
 * gateway's hot path never depends on this listener succeeding.</p>
 */
@Slf4j
@Component
public class UsageLedgerListener {

	private final DisruptorUsageLedgerQueue queue;

	/**
	 * @param queue the lock-free ring buffer feeding the micro-batch writer
	 */
	@Autowired
	public UsageLedgerListener(DisruptorUsageLedgerQueue queue) {
		this.queue = queue;
	}

	/**
	 * Enqueues one completed request for batched persistence.
	 *
	 * @param event the usage and cost record
	 */
	@EventListener
	@Async("ledgerExecutor")
	public void onTokenUsage(TokenUsageEvent event) {
		try {
			boolean queued = queue.offer(event);
			if (!queued) {
				log.debug("Usage event not queued (null or overflow); spillway journal handles persistence");
			}
		} catch (RuntimeException ex) {
			log.warn("Could not enqueue usage event: {}", ex.getMessage());
		}
	}
}
