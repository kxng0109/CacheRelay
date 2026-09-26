package io.github.kxng0109.cacherelay.ledger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SpillwayJournalManager Unit Test Suite")
class SpillwayJournalManagerTest {

	@TempDir
	Path tempDir;

	private Path journalPath;
	private SpillwayJournalManager journalManager;
	private SimpleMeterRegistry meterRegistry;

	@BeforeEach
	void setUp() {
		journalPath = tempDir.resolve("spillway-test.log");
		meterRegistry = new SimpleMeterRegistry();
		journalManager = new SpillwayJournalManager(
				journalPath.toString(),
				new ObjectMapper(),
				meterRegistry
		);
	}

	@AfterEach
	void cleanUp() throws IOException {
		// Windows releases file handles asynchronously; recursive delete with short retries is
		// deterministic here, and it removes staging *.replay.* files as well as the journal itself.
		for (int attempt = 0; attempt < 3; attempt++) {
			if (!Files.exists(tempDir)) {
				return;
			}
			try (var paths = Files.walk(tempDir)) {
				paths.sorted(Comparator.reverseOrder()).forEach(p -> {
					try {
						Files.deleteIfExists(p);
					} catch (IOException ignored) {
						// Retried in the next attempt.
					}
				});
			}
			if (!Files.exists(tempDir)) {
				return;
			}
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	@Test
	@DisplayName("Should append records and replay them faithfully")
	void shouldAppendAndReplayRecords() throws IOException {
		UUID reqId1 = UUID.randomUUID();
		UUID reqId2 = UUID.randomUUID();

		TokenUsageEvent e1 = new TokenUsageEvent(
				reqId1, "tenant-a", "anthropic", "claude-sonnet-5",
				100, 50, 150, 200, 1500, Instant.now()
		);
		TokenUsageEvent e2 = new TokenUsageEvent(
				reqId2, "tenant-b", "openai", "gpt-5.6-luna",
				200, 100, 300, 400, 3000, Instant.now(),
				150, 50, 0, 10, 2800, 2800, "hash123"
		);

		journalManager.append(e1, "Database timeout");
		journalManager.append(e2, "Connection pool exhausted");

		assertThat(Files.exists(journalPath)).isTrue();
		List<String> lines = Files.readAllLines(journalPath);
		assertThat(lines).hasSize(2);

		List<TokenUsageEvent> replayed = new ArrayList<>();
		int replayedCount = journalManager.replayPendingRecords(replayed::add);

		assertThat(replayedCount).isEqualTo(2);
		assertThat(replayed).hasSize(2);
		assertThat(replayed.get(0).requestId()).isEqualTo(reqId1);
		assertThat(replayed.get(1).requestId()).isEqualTo(reqId2);
		assertThat(replayed.get(1).requestHash()).isEqualTo("hash123");
		assertThat(replayed.get(1).cacheReadTokens()).isEqualTo(50L);

		// Journal should now be empty after replay
		assertThat(Files.exists(journalPath)).isFalse();
	}

	@Test
	@DisplayName("Replay on non-existent or zero-byte journal returns zero cleanly")
	void shouldHandleNonExistentJournal() throws IOException {
		int replayed = journalManager.replayPendingRecords(e -> {
		});
		assertThat(replayed).isEqualTo(0);

		// Zero-byte file
		Files.createFile(journalPath);
		int zeroByteReplayed = journalManager.replayPendingRecords(e -> {
		});
		assertThat(zeroByteReplayed).isEqualTo(0);
	}

	@Test
	@DisplayName("FS-B12: empty orphan replay files are skipped without error")
	void shouldSkipEmptyOrphanReplayFiles() throws IOException {
		Files.createFile(tempDir.resolve("spillway-test.log.replay.123"));

		int replayed = journalManager.replayPendingRecords(e -> {
		});

		assertThat(replayed).isEqualTo(0);
	}

	@Test
	@DisplayName("Consumer failure preserves every record durably for replay after recovery")
	void shouldPreserveFailedRecordsDurablyAndReplayAfterRecovery() throws IOException {
		List<TokenUsageEvent> events = List.of(
				createEvent("tenant-1"),
				createEvent("tenant-2"),
				createEvent("tenant-3")
		);
		journalManager.appendBatch(events, "Database outage");

		int failedReplay = journalManager.replayPendingRecords(e -> {
			throw new RuntimeException("DB still unreachable");
		});

		assertThat(failedReplay).as("replayed count on total failure").isEqualTo(0);
		assertThat(Files.exists(journalPath)).as("journal consumed").isFalse();
		assertThat(findSiblingFiles(".dead-letter.")).as("durable dead-letter files").hasSize(1);

		List<TokenUsageEvent> recovered = new ArrayList<>();
		int recoveredCount = journalManager.replayPendingRecords(recovered::add);

		assertThat(recoveredCount).as("replayed count after recovery").isEqualTo(3);
		assertThat(recovered).extracting(TokenUsageEvent::requestId)
				.containsExactlyInAnyOrder(
						events.get(0).requestId(),
						events.get(1).requestId(),
						events.get(2).requestId()
				);
		assertThat(findSiblingFiles(".dead-letter.")).as("dead-letter drained").isEmpty();
	}

	@Test
	@DisplayName("Orphaned replay staging from a crash is recovered on the next pass")
	void shouldRecoverOrphanedReplayFilesAtStartup() throws IOException {
		TokenUsageEvent e1 = createEvent("tenant-1");
		TokenUsageEvent e2 = createEvent("tenant-2");
		journalManager.appendBatch(List.of(e1, e2), "outage");
		// Simulate a crash between the atomic move and staging cleanup.
		Path orphan = Path.of(journalPath + ".replay.12345");
		Files.move(journalPath, orphan);

		List<TokenUsageEvent> replayed = new ArrayList<>();
		int count = journalManager.replayPendingRecords(replayed::add);

		assertThat(count).as("replayed count").isEqualTo(2);
		assertThat(replayed).extracting(TokenUsageEvent::requestId)
				.containsExactlyInAnyOrder(e1.requestId(), e2.requestId());
		assertThat(Files.exists(orphan)).as("orphan consumed").isFalse();
	}

	@Test
	@DisplayName("Malformed lines are quarantined, never fabricated into ledger rows")
	void shouldQuarantineMalformedLinesInsteadOfFabricatingRows() throws IOException {
		UUID fabricatedId = UUID.randomUUID();
		TokenUsageEvent valid = createEvent("tenant-valid");
		journalManager.append(valid, "outage");
		String fabricatedLine = "{\"requestId\":\"" + fabricatedId + "\",\"error\":\"serialization_failed\"}";
		Files.writeString(
				journalPath,
				"not-json\n{\"foo\":\"bar\"}\n" + fabricatedLine + "\n",
				StandardCharsets.UTF_8,
				StandardOpenOption.APPEND
		);

		List<TokenUsageEvent> replayed = new ArrayList<>();
		int count = journalManager.replayPendingRecords(replayed::add);

		assertThat(count).as("replayed count").isEqualTo(1);
		assertThat(replayed).extracting(TokenUsageEvent::requestId).containsExactly(valid.requestId());
		Path quarantine = Path.of(journalPath + ".quarantine");
		assertThat(Files.exists(quarantine)).as("quarantine exists").isTrue();
		assertThat(Files.readAllLines(quarantine)).as("quarantined lines").hasSize(3);
	}

	@Test
	@DisplayName("Gracefully skips unparseable or malformed journal lines and deserializes without timestamp")
	void shouldSkipMalformedJournalLines() throws IOException {
		UUID reqId = UUID.randomUUID();
		// Line with valid requestId but no timestamp
		String noTimestampLine = "{\"requestId\":\"" + reqId + "\",\"ownerId\":\"t-notime\"}\n";
		Files.writeString(
				journalPath,
				"not-json\n{\"foo\":\"bar\"}\n   \n" + noTimestampLine,
				StandardCharsets.UTF_8
		);

		List<TokenUsageEvent> replayed = new ArrayList<>();
		int count = journalManager.replayPendingRecords(replayed::add);
		assertThat(count).isEqualTo(1);
		assertThat(replayed.getFirst().requestId()).isEqualTo(reqId);
		assertThat(replayed.getFirst().timestamp()).isNotNull();
		Path quarantine = Path.of(journalPath + ".quarantine");
		assertThat(Files.exists(quarantine)).as("quarantine exists").isTrue();
		assertThat(Files.readAllLines(quarantine)).as("quarantined lines").hasSize(2);
	}

	@Test
	@DisplayName("Handles serialization and I/O failures gracefully")
	void shouldHandleSerializationAndIoFailures() throws Exception {
		ObjectMapper failingMapper = mock(ObjectMapper.class);
		when(failingMapper.writeValueAsString(any()))
		                   .thenThrow(new RuntimeException("Simulated JSON failure"));

		SpillwayJournalManager failingMgr = new SpillwayJournalManager(
				journalPath.toString(),
				failingMapper,
				meterRegistry
		);
		TokenUsageEvent event = new TokenUsageEvent(
				UUID.randomUUID(), "tenant", "openai", "gpt-4o",
				10, 5, 15, 20, 100, Instant.now()
		);
		failingMgr.append(event, "test error");
		assertThat(Files.exists(journalPath)).isTrue();
		List<String> lines = Files.readAllLines(journalPath);
		assertThat(lines.getFirst()).contains("serialization_failed");

		// Test appendBatch I/O exception when writing to a directory path
		Path dirAsFile = tempDir.resolve("dir-as-file");
		try {
			Files.createDirectory(dirAsFile);
			SpillwayJournalManager unwriteableMgr = new SpillwayJournalManager(
					dirAsFile.toString(),
					new ObjectMapper(),
					meterRegistry
			);
			// Writing directly to directory as file path throws IOException, which is caught and logged
			unwriteableMgr.append(event, "err");
		} finally {
			Files.deleteIfExists(dirAsFile);
		}
	}

	@Test
	@SuppressWarnings("DataFlowIssue")
	@DisplayName("Gracefully handles null events and constructor defaults")
	void shouldHandleNullBatchAndDefaults() throws IOException {
		journalManager.appendBatch(null, null);
		assertThat(Files.exists(journalPath)).isFalse();

		// Constructor with null meterRegistry
		SpillwayJournalManager nullRegMgr = new SpillwayJournalManager(
				journalPath.toString(),
				new ObjectMapper(),
				null
		);
		assertThat(nullRegMgr).isNotNull();

		// Relative path without parent
		Path relativePath = Path.of("spillway-relative-test.log");
		try {
			SpillwayJournalManager relMgr = new SpillwayJournalManager(
					relativePath.toString(),
					new ObjectMapper(),
					meterRegistry
			);
			TokenUsageEvent event = new TokenUsageEvent(
					UUID.randomUUID(), "t", null, null,
					10, 5, 15, 20, 100, Instant.now()
			);
			relMgr.append(event, null);
			assertThat(Files.exists(relativePath)).isTrue();
			relMgr.replayPendingRecords(e -> {
			});
		} finally {
			Files.deleteIfExists(relativePath);
		}
	}

	private static TokenUsageEvent createEvent(String tenant) {
		return new TokenUsageEvent(
				UUID.randomUUID(), tenant, "openai", "gpt-5.6-luna",
				100, 50, 150, 200, 1500, Instant.now()
		);
	}

	private List<Path> findSiblingFiles(String infix) throws IOException {
		String prefix = journalPath.getFileName().toString() + infix;
		try (var paths = Files.list(tempDir)) {
			return paths.filter(p -> p.getFileName().toString().startsWith(prefix)).toList();
		}
	}
}
