package io.github.kxng0109.cacherelay.capture;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import io.github.kxng0109.cacherelay.auth.RefreshService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CapturePurger")
class CapturePurgerTest {

	private static final Instant FIXED_NOW = Instant.parse("2026-09-23T12:00:00Z");

	private CaptureProperties properties(Path dir) {
		return new CaptureProperties(true, 1000, 100_000, dir.toString(), 32768,
				268435456L, 90, 7, List.of());
	}

	private CapturePurger purger(Path dir) {
		return new CapturePurger(properties(dir), JsonMapper.builder().build(),
				Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
	}

	private String row(String requestId, String ownerHash, Instant expiresAt) {
		return "{\"request_id\":\"" + requestId + "\",\"owner_hash\":"
				+ (ownerHash == null ? "null" : "\"" + ownerHash + "\"")
				+ ",\"expires_at\":\"" + expiresAt + "\"}";
	}

	private Path segment(Path dir, String name, String... lines) throws Exception {
		Path file = dir.resolve(name);
		Files.write(file, List.of(lines), StandardCharsets.UTF_8);
		Files.writeString(dir.resolve(name + ".meta.jsonl"), "", StandardCharsets.UTF_8);
		return file;
	}

	private List<String> dataLines(Path dir) throws Exception {
		try (Stream<Path> files = Files.list(dir)) {
			return files
					.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
					.filter(path -> !path.getFileName().toString().endsWith(".meta.jsonl"))
					.filter(path -> !path.getFileName().toString().endsWith(".tmp"))
					.sorted()
					.flatMap(path -> {
						try {
							return Files.readAllLines(path, StandardCharsets.UTF_8).stream();
						} catch (Exception failed) {
							throw new IllegalStateException(failed);
						}
					})
					.toList();
		}
	}

	@Test
	@DisplayName("the scheduled entry purges with the wall clock")
	void scheduledEntryPurges(@TempDir Path dir) throws Exception {
		segment(dir, "capture-default-20260923-12.jsonl",
				row("r-1", "hash-1", FIXED_NOW.plusSeconds(3600)));

		purger(dir).purgeExpired();

		assertThat(dataLines(dir)).hasSize(1);
	}

	@Test
	@DisplayName("expired segments delete with their sidecars")
	void expiredSegmentsDelete(@TempDir Path dir) throws Exception {
		segment(dir, "capture-default-20250601-12.jsonl",
				row("r-1", "hash-1", FIXED_NOW.minusSeconds(10)));
		segment(dir, "capture-default-20260923-12.jsonl",
				row("r-2", "hash-1", FIXED_NOW.plusSeconds(3600)));

		purger(dir).purge(FIXED_NOW);

		assertThat(dataLines(dir)).hasSize(1);
		assertThat(dataLines(dir).getFirst()).contains("r-2");
		try (Stream<Path> files = Files.list(dir)) {
			assertThat(files.map(path -> path.getFileName().toString()).toList())
					.noneMatch(name -> name.contains("20250601"));
		}
	}

	@Test
	@DisplayName("strict jurisdictions expire sooner")
	void strictJurisdictionExpiresSooner(@TempDir Path dir) throws Exception {
		segment(dir, "capture-strict-20260915-12.jsonl",
				row("r-1", "hash-1", FIXED_NOW.plusSeconds(3600)));
		segment(dir, "capture-default-20260915-12.jsonl",
				row("r-2", "hash-1", FIXED_NOW.plusSeconds(3600)));

		purger(dir).purge(FIXED_NOW);

		assertThat(dataLines(dir)).hasSize(1);
		assertThat(dataLines(dir).getFirst()).contains("r-2");
	}

	@Test
	@DisplayName("row TTLs rewrite segments keeping live and corrupt lines")
	void rowTtlRewrites(@TempDir Path dir) throws Exception {
		segment(dir, "capture-default-20260923-12.jsonl",
				row("r-live", "hash-1", FIXED_NOW.plusSeconds(3600)),
				row("r-dead", "hash-1", FIXED_NOW.minusSeconds(10)),
				"{\"request_id\":\"r-noexpiry\"}",
				"{\"request_id\":\"r-badexpiry\",\"expires_at\":\"garbage\"}",
				"not-json{{{");

		purger(dir).purge(FIXED_NOW);

		assertThat(dataLines(dir)).hasSize(4);
		assertThat(String.join("\n", dataLines(dir))).contains("r-live");
		assertThat(String.join("\n", dataLines(dir))).contains("r-noexpiry");
		assertThat(String.join("\n", dataLines(dir))).contains("r-badexpiry");
		assertThat(String.join("\n", dataLines(dir))).contains("not-json{{{");
		assertThat(String.join("\n", dataLines(dir))).doesNotContain("r-dead");
	}

	@Test
	@DisplayName("owner erasure rewrites segments and counts them")
	void ownerErasure(@TempDir Path dir) throws Exception {
		String hash = RefreshService.sha256Hex("owner-1");
		segment(dir, "capture-default-20260923-12.jsonl",
				row("r-1", hash, FIXED_NOW.plusSeconds(3600)),
				row("r-2", "other-hash", FIXED_NOW.plusSeconds(3600)));
		segment(dir, "capture-default-20260923-11.jsonl",
				row("r-3", "other-hash", FIXED_NOW.plusSeconds(3600)));

		int rewritten = purger(dir).purgeOwner("owner-1");

		assertThat(rewritten).isEqualTo(1);
		assertThat(dataLines(dir)).hasSize(2);
		assertThat(String.join("\n", dataLines(dir))).doesNotContain("r-1");
	}

	@Test
	@DisplayName("hour-less files fall back to row TTLs")
	void hourLessFilesRewrite(@TempDir Path dir) throws Exception {
		segment(dir, "notes.jsonl",
				row("r-1", "hash-1", FIXED_NOW.plusSeconds(3600)),
				row("r-2", "hash-1", FIXED_NOW.minusSeconds(10)));
		segment(dir, "capture-.jsonl",
				row("r-3", "hash-1", FIXED_NOW.plusSeconds(3600)));

		purger(dir).purge(FIXED_NOW);

		assertThat(dataLines(dir)).hasSize(2);
		assertThat(String.join("\n", dataLines(dir))).contains("r-1");
		assertThat(String.join("\n", dataLines(dir))).contains("r-3");
	}

	@Test
	@DisplayName("missing directories are a quiet no-op")
	void missingDirectoryNoop(@TempDir Path dir) {
		CapturePurger purger = purger(dir.resolve("absent"));

		purger.purge(FIXED_NOW);

		assertThat(purger.purgeOwner("owner-1")).isZero();
	}
}
