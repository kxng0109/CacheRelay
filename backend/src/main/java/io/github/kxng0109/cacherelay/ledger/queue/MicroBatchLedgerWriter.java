package io.github.kxng0109.cacherelay.ledger.queue;

import io.github.kxng0109.cacherelay.ledger.LedgerStagingEntry;
import io.github.kxng0109.cacherelay.ledger.LedgerStagingRepository;
import io.github.kxng0109.cacherelay.ledger.SpillwayJournalManager;
import io.github.kxng0109.cacherelay.ledger.TokenUsageEvent;
import io.github.kxng0109.cacherelay.ledger.UsageLedgerEntry;
import io.github.kxng0109.cacherelay.ledger.UsageLedgerRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-throughput micro-batching ledger writer consuming the lock-free ring buffer and flushing batches to PostgreSQL
 * with dual triggers (batch size $\ge 5,000$ or interval $\ge 50\text{ms}$).
 *
 * <p>Employs transactional batching, circuit-breaking to the durable spillway journal during database outages,
 * and background journal replay upon database recovery.</p>
 */
@Slf4j
@Component
public class MicroBatchLedgerWriter implements SmartLifecycle {

	private final DisruptorUsageLedgerQueue queue;
	private final UsageLedgerRepository repository;
	private final SpillwayJournalManager spillwayJournal;
	private final MeterRegistry meterRegistry;

	private final int maxBatchSize;
	private final long flushIntervalMs;
	private final long replayInitialDelayMs;
	private final long replayIntervalMs;
	private final int shutdownAwaitSeconds;
	private final AtomicBoolean running = new AtomicBoolean(false);
	private @Nullable ScheduledExecutorService scheduler;
	private volatile @Nullable LedgerStagingRepository stagingRepository;

	/**
	 * Wires the shared staging table for cross-instance replay of failed batches.
	 * Optional on purpose: without it, failed batches fall back to the per-pod
	 * spillway journal only.
	 *
	 * @param stagingRepository the shared staging repository, if available
	 */
	@Autowired
	public void setStagingRepository(@Nullable LedgerStagingRepository stagingRepository) {
		this.stagingRepository = stagingRepository;
	}

	/**
	 * Creates a new micro-batch ledger writer.
	 *
	 * @param queue           the lock-free ring buffer
	 * @param repository      the JPA ledger repository
	 * @param spillwayJournal fallback journal on database outage
	 * @param meterRegistry   metrics registry
	 * @param maxBatchSize    maximum rows in one bulk flush (default 5,000)
	 * @param flushIntervalMs maximum duration before flushing a partial batch (default 50ms)
	 * @param replayInitialDelayMs initial delay before the first spillway replay pass (default 30,000ms)
	 * @param replayIntervalMs     interval between spillway replay passes (default 60,000ms)
	 * @param shutdownAwaitSeconds graceful shutdown drain timeout in seconds (default 5s)
	 */
	@Autowired
	public MicroBatchLedgerWriter(
			DisruptorUsageLedgerQueue queue,
			UsageLedgerRepository repository,
			SpillwayJournalManager spillwayJournal,
			@Nullable MeterRegistry meterRegistry,
			@Value("${gateway.ledger.batch.max-size:5000}") int maxBatchSize,
			@Value("${gateway.ledger.batch.interval-ms:50}") long flushIntervalMs,
			@Value("${gateway.ledger.replay.initial-delay-ms:30000}") long replayInitialDelayMs,
			@Value("${gateway.ledger.replay.interval-ms:60000}") long replayIntervalMs,
			@Value("${gateway.ledger.shutdown-await-seconds:5}") int shutdownAwaitSeconds
	) {
		this.queue = queue;
		this.repository = repository;
		this.spillwayJournal = spillwayJournal;
		this.meterRegistry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
		Gauge.builder("cacherelay.ledger.queue.depth", queue, DisruptorUsageLedgerQueue::size)
		     .description("Unflushed usage events waiting in the ring buffer")
		     .register(this.meterRegistry);
		this.maxBatchSize = Math.max(10, maxBatchSize);
		this.flushIntervalMs = Math.max(10L, flushIntervalMs);
		this.replayInitialDelayMs = Math.max(1_000L, replayInitialDelayMs);
		this.replayIntervalMs = Math.max(5_000L, replayIntervalMs);
		this.shutdownAwaitSeconds = Math.max(1, shutdownAwaitSeconds);
	}

	@Override
	public void start() {
		if (running.compareAndSet(false, true)) {
			scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("ledger-microbatch-", 0)
			                                                             .factory());
			scheduler.scheduleWithFixedDelay(this::flushCycle, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
			scheduler.scheduleWithFixedDelay(
					this::replayCycle, replayInitialDelayMs, replayIntervalMs, TimeUnit.MILLISECONDS);
			log.info(
					"MicroBatchLedgerWriter started with maxBatchSize={}, interval={}ms",
					maxBatchSize,
					flushIntervalMs
			);
		}
	}

	@Override
	public void stop() {
		if (running.compareAndSet(true, false) && scheduler != null) {
			scheduler.shutdown();
			try {
				if (!scheduler.awaitTermination(shutdownAwaitSeconds, TimeUnit.SECONDS)) {
					scheduler.shutdownNow();
				}
			} catch (InterruptedException ex) {
				scheduler.shutdownNow();
				Thread.currentThread().interrupt();
			}
			// Final drain
			flushCycle();
			log.info("MicroBatchLedgerWriter stopped cleanly");
		}
	}

	@Override
	public boolean isRunning() {
		return running.get();
	}

	/**
	 * Executes one flush cycle, draining up to {@code maxBatchSize} records from the queue and saving to PostgreSQL.
	 *
	 * @return count of flushed records
	 */
	public int flushCycle() {
		List<TokenUsageEvent> batch = new ArrayList<>(maxBatchSize);
		int drained = queue.drainTo(batch, maxBatchSize);
		if (drained == 0) {
			return 0;
		}

		List<TokenUsageEvent> fresh = dedupeEvents(batch);
		if (fresh.isEmpty()) {
			return 0;
		}
		List<UsageLedgerEntry> entries = new ArrayList<>(fresh.size());
		for (TokenUsageEvent event : fresh) {
			entries.add(new UsageLedgerEntry(
					event.requestId(),
					event.ownerId(),
					event.provider(),
					event.model(),
					safeInt(event.promptTokens()),
					safeInt(event.completionTokens()),
					safeInt(event.totalTokens()),
					event.costUsdMicros(),
					event.durationMs(),
					event.timestamp(),
					safeInt(event.uncachedPromptTokens()),
					safeInt(event.cacheReadTokens()),
					safeInt(event.cacheWriteTokens()),
					safeInt(event.reasoningTokens()),
					event.effectiveCostMicros(),
					event.billedCostMicros(),
					event.requestHash()
			));
		}

		try {
			long flushStartNanos = System.nanoTime();
			repository.saveAll(entries);
			Timer.builder("cacherelay.ledger.flush.seconds")
			     .description("PostgreSQL bulk-flush latency")
			     .register(meterRegistry)
			     .record(System.nanoTime() - flushStartNanos, TimeUnit.NANOSECONDS);
			DistributionSummary.builder("cacherelay.ledger.batch.size")
			                   .description("Rows per bulk flush")
			                   .register(meterRegistry)
			                   .record(fresh.size());
			recordBatchMetrics(fresh);
			return fresh.size();
		} catch (Exception ex) {
			log.warn(
					"Database bulk insert of {} records failed: {}; staging for shared replay",
					fresh.size(), ex.getMessage()
			);
			if (!stageForSharedReplay(fresh)) {
				spillwayJournal.appendBatch(fresh, "DB batch write failed: " + ex.getMessage());
			}
			return 0;
		}
	}

	/**
	 * Parks a failed batch in the shared staging table for replay by any instance
	 * (preserving the pre-PERF-03 listener contract at batch granularity). Duplicates
	 * already staged elsewhere are benign success; any other staging failure falls
	 * through to the per-pod spillway journal below.
	 *
	 * @param batch failed events
	 * @return {@code true} when the spillway path must be skipped
	 */
	private boolean stageForSharedReplay(List<TokenUsageEvent> batch) {
		LedgerStagingRepository staging = this.stagingRepository;
		if (staging == null || batch.isEmpty()) {
			return false;
		}
		try {
			List<LedgerStagingEntry> rows = new ArrayList<>(batch.size());
			for (TokenUsageEvent event : batch) {
				rows.add(LedgerStagingEntry.pendingFrom(event));
			}
			staging.saveAll(rows);
			staging.flush();
			return true;
		} catch (DataIntegrityViolationException duplicate) {
			return true;
		} catch (RuntimeException ex) {
			log.debug("Staging unavailable, falling back to disk journal: {}", ex.getMessage());
			return false;
		}
	}

	/**
	 * Filters a drained batch to fresh events (PERF-03): within-batch duplicates collapse
	 * to the first occurrence and already-persisted ids are removed with one batch
	 * existence SELECT (replacing the old per-row check), so retries can never double-record.
	 * A failed existence check persists everything (dedupe is best-effort; the unique
	 * constraint remains the final guard).
	 *
	 * @param batch drained events
	 * @return fresh events in original order
	 */
	private List<TokenUsageEvent> dedupeEvents(List<TokenUsageEvent> batch) {
		Set<UUID> kept = new HashSet<>();
		List<TokenUsageEvent> unique = new ArrayList<>(batch.size());
		for (TokenUsageEvent event : batch) {
			if (event != null && event.requestId() != null && kept.add(event.requestId())) {
				unique.add(event);
			}
		}
		if (unique.isEmpty()) {
			return unique;
		}
		List<UUID> ids = new ArrayList<>(unique.size());
		for (TokenUsageEvent event : unique) {
			ids.add(event.requestId());
		}
		Set<UUID> storedIds = new HashSet<>();
		try {
			List<UsageLedgerEntry> stored = repository.findByRequestIdIn(ids);
			if (stored != null) {
				for (UsageLedgerEntry existing : stored) {
					if (existing != null && existing.getRequestId() != null) {
						storedIds.add(existing.getRequestId());
					}
				}
			}
		} catch (Exception ex) {
			log.debug("Batch existence check unavailable, persisting without dedupe: {}", ex.getMessage());
			return unique;
		}
		if (storedIds.isEmpty()) {
			return unique;
		}
		List<TokenUsageEvent> fresh = new ArrayList<>(unique.size());
		for (TokenUsageEvent event : unique) {
			if (!storedIds.contains(event.requestId())) {
				fresh.add(event);
			}
		}
		return fresh;
	}

	void replayCycle() {
		if (!running.get()) {
			return;
		}
		try {
			spillwayJournal.replayPendingRecords(event -> {
				UsageLedgerEntry entry = new UsageLedgerEntry(
						event.requestId(),
						event.ownerId(),
						event.provider(),
						event.model(),
						safeInt(event.promptTokens()),
						safeInt(event.completionTokens()),
						safeInt(event.totalTokens()),
						event.costUsdMicros(),
						event.durationMs(),
						event.timestamp(),
						safeInt(event.uncachedPromptTokens()),
						safeInt(event.cacheReadTokens()),
						safeInt(event.cacheWriteTokens()),
						safeInt(event.reasoningTokens()),
						event.effectiveCostMicros(),
						event.billedCostMicros(),
						event.requestHash()
				);
				repository.save(entry);
			});
		} catch (Exception ex) {
			log.debug("Spillway replay deferred: Database currently unavailable ({})", ex.getMessage());
		}
	}

	private void recordBatchMetrics(List<TokenUsageEvent> batch) {
		for (TokenUsageEvent event : batch) {
			String provider = safeTag(event.provider());
			String model = safeTag(event.model());

			if (event.promptTokens() > 0) {
				Counter.builder("cacherelay.tokens")
				       .baseUnit("tokens")
				       .tag("provider", provider)
				       .tag("model", model)
				       .tag("type", "prompt")
				       .register(meterRegistry)
				       .increment(event.promptTokens());
			}
			if (event.completionTokens() > 0) {
				Counter.builder("cacherelay.tokens")
				       .baseUnit("tokens")
				       .tag("provider", provider)
				       .tag("model", model)
				       .tag("type", "completion")
				       .register(meterRegistry)
				       .increment(event.completionTokens());
			}
			// Always incremented, including zero-cost (cached, local) traffic: the
			// series must materialize so cost panels read 0 instead of no-data.
			// Burn-rate sums are unaffected (zeros add nothing).
			Counter.builder("cacherelay.cost.micros")
			       .baseUnit("micros")
			       .tag("provider", provider)
			       .tag("model", model)
			       .register(meterRegistry)
			       .increment(event.costUsdMicros());
		}
	}

	private static String safeTag(String value) {
		return (value == null || value.isBlank()) ? "unknown" : value;
	}

	private static int safeInt(long value) {
		return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
	}
}
