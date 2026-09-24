package io.github.kxng0109.cacherelay.capture;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

import io.github.kxng0109.cacherelay.security.guardrail.pii.PiiAnonymizer;
import io.github.kxng0109.cacherelay.security.guardrail.pii.PiiScanner;
import io.github.kxng0109.cacherelay.security.guardrail.secret.IngressSecretScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CaptureWriter")
class CaptureWriterTest {

	private static final Instant FIXED_NOW = Instant.parse("2026-09-23T12:00:00Z");

	private CaptureProperties properties(Path dir) {
		return new CaptureProperties(true, 1000, 100_000, dir.toString(), 32768,
				268435456L, 90, 7, List.of());
	}

	private CaptureWriter writer(Path dir) {
		CaptureService service = new CaptureService(properties(dir));
		return new CaptureWriter(service, properties(dir), new PiiAnonymizer(new PiiScanner()),
				new IngressSecretScanner(), JsonMapper.builder().build(),
				Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
	}

	private CaptureEvent event(String prompt, String output) {
		return new CaptureEvent(UUID.randomUUID(), "owner-1", "key-1", "gpt-4o", "openai",
				prompt, output, 10L, 5L, false, FIXED_NOW);
	}

	private List<String> segmentLines(Path dir) throws Exception {
		try (Stream<Path> files = Files.list(dir)) {
			List<Path> segments = files
					.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
					.filter(path -> !path.getFileName().toString().endsWith(".meta.jsonl"))
					.toList();
			assertThat(segments).hasSize(1);
			return Files.readAllLines(segments.getFirst(), StandardCharsets.UTF_8);
		}
	}

	@Test
	@DisplayName("records persist redacted with manifests and hashes")
	void writesRedactedRecord(@TempDir Path dir) throws Exception {
		CaptureWriter writer = writer(dir);

		writer.writeBatch(List.of(event(
				"{\"prompt\":\"Contact me at jane.doe@example.com please\"}",
				"{\"output\":\"Sure, emailing jane.doe@example.com soon\"}")));
		writer.close();

		List<String> lines = segmentLines(dir);
		assertThat(lines).hasSize(1);
		JsonNode record = JsonMapper.builder().build().readTree(lines.getFirst());
		assertThat(record.path("model").asString()).isEqualTo("gpt-4o");
		assertThat(record.path("provider").asString()).isEqualTo("openai");
		assertThat(record.path("prompt").asString()).doesNotContain("jane.doe@example.com");
		assertThat(record.path("output").asString()).doesNotContain("jane.doe@example.com");
		assertThat(record.path("prompt").asString()).contains("EMAIL");
		assertThat(record.path("owner_hash").asString()).hasSize(64);
		assertThat(record.path("suppressed_by").isNull()).isTrue();
		assertThat(record.path("expires_at").asString()).isNotBlank();
		assertThat(writer.written()).isEqualTo(1L);
		try (Stream<Path> files = Files.list(dir)) {
			assertThat(files.anyMatch(path -> path.getFileName().toString()
					.endsWith(".meta.jsonl"))).isTrue();
		}
	}

	@Test
	@DisplayName("credential hits in prompts suppress with prompt metadata")
	void secretInPromptSuppresses(@TempDir Path dir) throws Exception {
		CaptureWriter writer = writer(dir);
		String leaked = "ghp_1234567890abcdefghijklmnopqrstuvwxyz";

		writer.writeBatch(List.of(event(
				"{\"prompt\":\"token " + leaked + "\"}", "{\"output\":\"ok\"}")));
		writer.close();

		List<String> lines = segmentLines(dir);
		assertThat(lines).hasSize(1);
		JsonNode record = JsonMapper.builder().build().readTree(lines.getFirst());
		assertThat(record.path("prompt").isNull()).isTrue();
		assertThat(record.path("output").isNull()).isTrue();
		assertThat(record.path("suppressed_by").asString()).isNotBlank();
		assertThat(lines.getFirst()).doesNotContain(leaked);
	}

	@Test
	@DisplayName("null owners hash to null")
	void nullOwnerHashesNull(@TempDir Path dir) throws Exception {
		CaptureWriter writer = writer(dir);

		writer.writeBatch(List.of(new CaptureEvent(UUID.randomUUID(), null, null, "gpt-4o",
				"openai", "{\"prompt\":\"hi\"}", null, 10L, 5L, false, FIXED_NOW)));
		writer.close();

		List<String> lines = segmentLines(dir);
		JsonNode record = JsonMapper.builder().build().readTree(lines.getFirst());
		assertThat(record.path("owner_hash").isNull()).isTrue();
	}

	@Test
	@DisplayName("hostile jurisdictions fall back to default segments")
	void hostileJurisdictionFallsBack(@TempDir Path dir) throws Exception {
		CaptureProperties.CaptureRule rule =
				new CaptureProperties.CaptureRule("owner-1", "../../evil", null, false);
		CaptureProperties props = new CaptureProperties(true, 1000, 100_000, dir.toString(),
				32768, 268435456L, 90, 7, List.of(rule));
		CaptureService service = new CaptureService(props);
		CaptureWriter writer = new CaptureWriter(service, props,
				new PiiAnonymizer(new PiiScanner()), new IngressSecretScanner(),
				JsonMapper.builder().build(), Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

		writer.writeBatch(List.of(event("{\"prompt\":\"hi\"}", null)));
		writer.close();

		try (Stream<Path> files = Files.list(dir)) {
			assertThat(files.map(path -> path.getFileName().toString()).toList())
					.allMatch(name -> name.startsWith("capture-default-"));
		}
	}

	@Test
	@DisplayName("reopened segments resume byte accounting")
	void reopenedSegmentsResume(@TempDir Path dir) throws Exception {
		CaptureProperties props = new CaptureProperties(true, 1000, 100_000, dir.toString(),
				32768, 100L, 90, 7, List.of());
		java.util.function.		Supplier<CaptureWriter> open = () -> new CaptureWriter(
				new CaptureService(props), props, new PiiAnonymizer(new PiiScanner()),
				new IngressSecretScanner(), JsonMapper.builder().build(),
				Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
		CaptureWriter first = open.get();
		first.writeBatch(List.of(event("{\"prompt\":\"one\"}", null)));
		first.close();

		CaptureWriter second = open.get();
		second.writeBatch(List.of(event("{\"prompt\":\"two\"}", null)));
		second.close();

		try (Stream<Path> files = Files.list(dir)) {
			assertThat(files.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
					.filter(path -> !path.getFileName().toString().endsWith(".meta.jsonl"))
					.count()).isEqualTo(2L);
		}
	}

	@Test
	@DisplayName("credential hits suppress both bodies with metadata")
	void secretSuppressesBodies(@TempDir Path dir) throws Exception {
		CaptureWriter writer = writer(dir);
		String leaked = "ghp_1234567890abcdefghijklmnopqrstuvwxyz";

		writer.writeBatch(List.of(event("{\"prompt\":\"hi\"}",
				"{\"output\":\"token " + leaked + "\"}")));
		writer.close();

		List<String> lines = segmentLines(dir);
		assertThat(lines).hasSize(1);
		JsonNode record = JsonMapper.builder().build().readTree(lines.getFirst());
		assertThat(record.path("output").isNull()).isTrue();
		assertThat(record.path("prompt").isNull()).isTrue();
		assertThat(record.path("suppressed_by").asString()).isNotBlank();
		assertThat(record.path("masked_anchor").asString()).isNotBlank();
		assertThat(lines.getFirst()).doesNotContain(leaked);
	}

	@Test
	@DisplayName("oversized fields truncate with flags before redaction")
	void truncationFlags(@TempDir Path dir) throws Exception {
		CaptureProperties props = new CaptureProperties(true, 1000, 100_000, dir.toString(),
				1024, 268435456L, 90, 7, List.of());
		CaptureService service = new CaptureService(props);
		CaptureWriter writer = new CaptureWriter(service, props,
				new PiiAnonymizer(new PiiScanner()), new IngressSecretScanner(),
				JsonMapper.builder().build(), Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
		String big = "x".repeat(2000);

		writer.writeBatch(List.of(event("{\"prompt\":\"" + big + "\"}", null)));
		writer.close();

		List<String> lines = segmentLines(dir);
		JsonNode record = JsonMapper.builder().build().readTree(lines.getFirst());
		assertThat(record.path("prompt_truncated").asBoolean()).isTrue();
		assertThat(record.path("prompt").asString()).hasSize(1024);
		assertThat(record.path("output").isNull()).isTrue();
		assertThat(record.path("output_truncated").asBoolean()).isFalse();
	}

	@Test
	@DisplayName("hour changes rotate segments")
	void rotationOnHour(@TempDir Path dir) throws Exception {
		CaptureWriter writer = writer(dir);
		CaptureEvent first = event("{\"prompt\":\"one\"}", null);
		CaptureEvent second = new CaptureEvent(UUID.randomUUID(), "owner-1", "key-1", "gpt-4o",
				"openai", "{\"prompt\":\"two\"}", null, 10L, 5L, false,
				FIXED_NOW.plusSeconds(3600L));

		writer.writeBatch(List.of(first, second));
		writer.close();

		try (Stream<Path> files = Files.list(dir)) {
			assertThat(files.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
					.filter(path -> !path.getFileName().toString().endsWith(".meta.jsonl"))
					.count()).isEqualTo(2L);
		}
		assertThat(writer.written()).isEqualTo(2L);
	}

	@Test
	@DisplayName("tiny size caps rotate segments")
	void rotationOnSize(@TempDir Path dir) throws Exception {
		CaptureProperties props = new CaptureProperties(true, 1000, 100_000, dir.toString(),
				32768, 100L, 90, 7, List.of());
		CaptureService service = new CaptureService(props);
		CaptureWriter writer = new CaptureWriter(service, props,
				new PiiAnonymizer(new PiiScanner()), new IngressSecretScanner(),
				JsonMapper.builder().build(), Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

		writer.writeBatch(List.of(event("{\"prompt\":\"one\"}", null),
				event("{\"prompt\":\"two\"}", null)));
		writer.close();

		try (Stream<Path> files = Files.list(dir)) {
			assertThat(files.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
					.filter(path -> !path.getFileName().toString().endsWith(".meta.jsonl"))
					.count()).isEqualTo(2L);
		}
	}

	@Test
	@DisplayName("unusable directories fail fast")
	void unusableDirectoryFails(@TempDir Path dir) throws Exception {		Path file = dir.resolve("file");
		Files.writeString(file, "x", StandardCharsets.UTF_8);
		CaptureProperties props = new CaptureProperties(true, 1000, 100_000,
				file.resolve("nested").toString(), 32768, 268435456L, 90, 7, List.of());

		assertThatThrownBy(() -> new CaptureWriter(new CaptureService(props), props,
				new PiiAnonymizer(new PiiScanner()), new IngressSecretScanner(),
				JsonMapper.builder().build(), Clock.systemUTC()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Capture directory unavailable");
	}

	@Test
	@DisplayName("the background thread drains, survives poison, and stops")
	void backgroundLoopDrainsAndStops(@TempDir Path dir) throws Exception {
		CaptureProperties.CaptureRule rule =
				new CaptureProperties.CaptureRule("owner-1", "default", null, false);
		CaptureProperties props = new CaptureProperties(true, 0, 100_000, dir.toString(),
				32768, 268435456L, 90, 7, List.of(rule));
		CaptureService service = new CaptureService(props);
		CaptureWriter writer = new CaptureWriter(service, props,
				new PiiAnonymizer(new PiiScanner()), new IngressSecretScanner(),
				JsonMapper.builder().build(), Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

		writer.start();
		try {
			service.offer(event("{\"prompt\":\"one\"}", null));
			service.offer(new CaptureEvent(UUID.randomUUID(), "owner-1", "key-1",
					"gpt-4o", "openai", null, null, 10L, 5L, false, FIXED_NOW));
			service.offer(event("{\"prompt\":\"two\"}", null));
			long deadline = System.currentTimeMillis() + 15000L;
			while (writer.written() < 2L && System.currentTimeMillis() < deadline) {
				Thread.sleep(50L);
			}

			assertThat(writer.written()).isEqualTo(2L);
		} finally {
			writer.close();
		}
		assertThat(segmentLines(dir)).hasSize(2);
	}
}
