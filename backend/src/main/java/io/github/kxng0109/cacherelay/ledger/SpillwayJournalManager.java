package io.github.kxng0109.cacherelay.ledger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Resilient, append-only disk spillway journal protecting usage and FinOps billing records from data loss during
 * database connectivity outages or downstream queue saturation.
 */
@Slf4j
@Component
public class SpillwayJournalManager {

	private final Path journalPath;
	private final ObjectMapper objectMapper;
	private final MeterRegistry meterRegistry;
	private final ReentrantLock lock = new ReentrantLock();

	/**
	 * Creates a new spillway journal manager.
	 *
	 * @param deadLetterPath configured file path for dead letter / spillway journal
	 * @param objectMapper   Jackson object mapper for JSON serialization
	 * @param meterRegistry  metrics registry for tracking spillway writes and replays
	 */
	@Autowired
	public SpillwayJournalManager(
			@Value("${gateway.ledger.dead-letter-path:logs/ledger-deadletter.log}") String deadLetterPath,
			ObjectMapper objectMapper,
			@Nullable MeterRegistry meterRegistry
	) {
		this.journalPath = Path.of(deadLetterPath);
		this.objectMapper = objectMapper;
		this.meterRegistry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
	}

	/**
	 * Appends a single usage event to the durable disk journal.
	 *
	 * @param event the usage event to persist
	 * @param error optional error description
	 */
	public void append(TokenUsageEvent event, @Nullable String error) {
		appendBatch(List.of(event), error);
	}

	/**
	 * Appends a batch of usage events to the durable disk journal.
	 *
	 * <p>Spill counters increment only after the bytes reach the file; a
	 * failed append increments the spill-failure counter instead, so counters
	 * always reflect actual writes.</p>
	 *
	 * @param events the batch of events to persist
	 * @param error  optional error description
	 */
	public void appendBatch(List<TokenUsageEvent> events, @Nullable String error) {
		if (events == null || events.isEmpty()) {
			return;
		}
		lock.lock();
		try {
			if (journalPath.getParent() != null) {
				Files.createDirectories(journalPath.getParent());
			}
			StringBuilder sb = new StringBuilder();
			for (TokenUsageEvent event : events) {
				String line = serializeEvent(event, error);
				sb.append(line).append('\n');
			}
			writeBatch(journalPath, sb.toString());
			for (TokenUsageEvent event : events) {
				recordSpillwayMetric(event.provider());
			}
		} catch (IOException ex) {
			log.error(
					"Catastrophic failure: Unable to append {} events to spillway journal at {}: {}",
					events.size(), journalPath, ex.getMessage()
			);
			recordSpillWriteFailureMetric();
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Replays pending journal entries through a consumer and clears the replayed entries.
	 *
	 * <p>Zero-loss semantics: the staging file is deleted only when every
	 * recovered record is accepted. Any consumer failure preserves the staging
	 * file as a durable {@code .dead-letter.<nanos>} sibling that is retried
	 * on later passes. Crash orphans ({@code .replay.*}) and dead letters are
	 * scanned and replayed on every pass, so the scheduled replay is also the
	 * startup recovery path. Lines that cannot be parsed are preserved in a
	 * {@code .quarantine} file instead of reaching the ledger.</p>
	 *
	 * @param consumer processor for deserialized usage events
	 * @return count of successfully replayed records
	 */
	public int replayPendingRecords(Consumer<TokenUsageEvent> consumer) {
		lock.lock();
		try {
			int totalReplayed = 0;
			for (Path orphan : listSiblingFiles(".replay.")) {
				totalReplayed += replayFile(orphan, consumer);
			}
			if (Files.exists(journalPath) && Files.size(journalPath) > 0) {
				Path stagingPath = Path.of(journalPath + ".replay." + System.nanoTime());
				Files.move(journalPath, stagingPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				totalReplayed += replayFile(stagingPath, consumer);
			}
			for (Path deadLetter : listSiblingFiles(".dead-letter.")) {
				totalReplayed += replayFile(deadLetter, consumer);
			}
			return totalReplayed;
		} catch (IOException ex) {
			log.error("Failed to process replay on journal {}: {}", journalPath, ex.getMessage());
			return 0;
		} finally {
			lock.unlock();
		}
	}

	private int replayFile(Path file, Consumer<TokenUsageEvent> consumer) throws IOException {
		if (!Files.exists(file) || Files.size(file) == 0) {
			return 0;
		}
		List<TokenUsageEvent> recovered = new ArrayList<>();
		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				String trimmed = line.trim();
				if (trimmed.isEmpty()) {
					continue;
				}
				TokenUsageEvent event = deserializeEvent(trimmed);
				if (event != null) {
					recovered.add(event);
				} else {
					quarantineLine(trimmed);
				}
			}
		}

		int replayedCount = 0;
		for (TokenUsageEvent event : recovered) {
			try {
				consumer.accept(event);
				replayedCount++;
				recordReplayMetric(event.provider());
			} catch (RuntimeException ex) {
				log.warn(
						"Replay failed for request {}: {}; preserving the staging file as a dead letter",
						event.requestId(),
						ex.getMessage()
				);
				recordDeadLetterMetric(event.provider());
			}
		}
		if (replayedCount == recovered.size()) {
			Files.deleteIfExists(file);
		} else {
			Path deadLetter = Path.of(journalPath + ".dead-letter." + System.nanoTime());
			Files.move(file, deadLetter, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}

		log.info("Successfully replayed {} usage records from spillway journal", replayedCount);
		return replayedCount;
	}

	private List<Path> listSiblingFiles(String infix) throws IOException {
		Path dir = journalPath.getParent() != null
				? journalPath.getParent()
				: journalPath.toAbsolutePath().getParent();
		if (dir == null || !Files.isDirectory(dir)) {
			return List.of();
		}
		String prefix = journalPath.getFileName().toString() + infix;
		try (var paths = Files.list(dir)) {
			return paths.filter(p -> p.getFileName().toString().startsWith(prefix)).toList();
		}
	}

	private void quarantineLine(String rawLine) {
		try {
			Path quarantine = Path.of(journalPath + ".quarantine");
			writeBatch(quarantine, rawLine + '\n');
			recordQuarantineMetric();
		} catch (IOException ex) {
			log.error("Failed to quarantine unparseable journal line: {}", ex.getMessage());
		}
	}

	/**
	 * Appends one batch to the journal file and forces it to stable storage. Batching amortizes
	 * the fsync across the whole batch; without the force an OS crash could lose acknowledged
	 * spill records the ledger assumes durable.
	 *
	 * @param path    journal file (parent directories are created)
	 * @param content batch bytes (already newline-terminated per record)
	 * @throws IOException when the bytes cannot be persisted
	 */
	static void writeBatch(Path path, String content) throws IOException {
		if (path.getParent() != null) {
			Files.createDirectories(path.getParent());
		}
		ByteBuffer buffer = StandardCharsets.UTF_8.encode(content);
		try (FileChannel channel = FileChannel.open(
				path, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
			while (buffer.hasRemaining()) {
				channel.write(buffer);
			}
			channel.force(false);
		}
	}

	private String serializeEvent(TokenUsageEvent event, @Nullable String error) {
		try {
			return objectMapper.writeValueAsString(new SpillwayRecord(
					event.requestId().toString(),
					event.ownerId(),
					event.provider(),
					event.model(),
					event.promptTokens(),
					event.completionTokens(),
					event.totalTokens(),
					event.durationMs(),
					event.costUsdMicros(),
					event.timestamp().toString(),
					event.uncachedPromptTokens(),
					event.cacheReadTokens(),
					event.cacheWriteTokens(),
					event.reasoningTokens(),
					event.effectiveCostMicros(),
					event.billedCostMicros(),
					event.requestHash(),
					error != null ? error : "unknown"
			));
		} catch (Exception ex) {
			return "{\"requestId\":\"" + event.requestId() + "\",\"error\":\"serialization_failed\"}";
		}
	}

	private @Nullable TokenUsageEvent deserializeEvent(String jsonLine) {
		try {
			JsonNode node = objectMapper.readTree(jsonLine);
			if (!node.has("requestId")) {
				return null;
			}
			if ("serialization_failed".equals(node.path("error").asString(""))) {
				return null;
			}
			UUID requestId = UUID.fromString(node.get("requestId").asString());
			String ownerId = node.path("ownerId").asString("unknown");
			String provider = node.path("provider").asString("unknown");
			String model = node.path("model").asString("unknown");
			long promptTokens = node.path("promptTokens").asLong(0);
			long completionTokens = node.path("completionTokens").asLong(0);
			long totalTokens = node.path("totalTokens").asLong(promptTokens + completionTokens);
			long durationMs = node.path("durationMs").asLong(0);
			long costUsdMicros = node.path("costUsdMicros").asLong(0);
			Instant timestamp = node.has("timestamp")
					? Instant.parse(node.get("timestamp").asString())
					: Instant.now();
			long uncachedPromptTokens = node.path("uncachedPromptTokens").asLong(promptTokens);
			long cacheReadTokens = node.path("cacheReadTokens").asLong(0);
			long cacheWriteTokens = node.path("cacheWriteTokens").asLong(0);
			long reasoningTokens = node.path("reasoningTokens").asLong(0);
			long effectiveCostMicros = node.path("effectiveCostMicros").asLong(costUsdMicros);
			long billedCostMicros = node.path("billedCostMicros").asLong(costUsdMicros);
			String requestHash = node.has("requestHash") && !node.get("requestHash").isNull()
					? node.get("requestHash").asString()
					: null;

			return new TokenUsageEvent(
					requestId, ownerId, provider, model,
					promptTokens, completionTokens, totalTokens,
					durationMs, costUsdMicros, timestamp,
					uncachedPromptTokens, cacheReadTokens, cacheWriteTokens,
					reasoningTokens, effectiveCostMicros, billedCostMicros, requestHash
			);
		} catch (Exception ex) {
			log.warn("Failed to deserialize spillway journal line: {}", ex.getMessage());
			return null;
		}
	}

	private void recordDeadLetterMetric(String provider) {
		Counter.builder("cacherelay.ledger.dead_letter.skip")
		       .baseUnit("records")
		       .tag("provider", provider != null && !provider.isBlank() ? provider : "unknown")
		       .register(meterRegistry)
		       .increment();
	}

	private void recordSpillwayMetric(String provider) {
		Counter.builder("cacherelay.ledger.dead_letter")
		       .baseUnit("records")
		       .tag("provider", provider != null && !provider.isBlank() ? provider : "unknown")
		       .register(meterRegistry)
		       .increment();
	}

	private void recordReplayMetric(String provider) {
		Counter.builder("cacherelay.ledger.spillway.replayed")
		       .baseUnit("records")
		       .tag("provider", provider != null && !provider.isBlank() ? provider : "unknown")
		       .register(meterRegistry)
		       .increment();
	}

	private void recordSpillWriteFailureMetric() {
		Counter.builder("cacherelay.ledger.deadletter.failures")
		       .description("Spillway journal append failures leaving records memory-only")
		       .baseUnit("records")
		       .register(meterRegistry)
		       .increment();
	}

	private void recordQuarantineMetric() {
		Counter.builder("cacherelay.ledger.quarantined")
		       .description("Unparseable spillway journal lines preserved in quarantine")
		       .baseUnit("records")
		       .register(meterRegistry)
		       .increment();
	}

	private record SpillwayRecord(
			String requestId,
			@Nullable String ownerId,
			String provider,
			String model,
			long promptTokens,
			long completionTokens,
			long totalTokens,
			long durationMs,
			long costUsdMicros,
			String timestamp,
			long uncachedPromptTokens,
			long cacheReadTokens,
			long cacheWriteTokens,
			long reasoningTokens,
			long effectiveCostMicros,
			long billedCostMicros,
			@Nullable String requestHash,
			String error
	) {
	}
}
