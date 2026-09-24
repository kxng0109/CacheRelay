package io.github.kxng0109.cacherelay.capture;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import io.github.kxng0109.cacherelay.auth.RefreshService;
import io.github.kxng0109.cacherelay.security.guardrail.pii.EphemeralPiiVault;
import io.github.kxng0109.cacherelay.security.guardrail.pii.PiiAnonymizer;
import io.github.kxng0109.cacherelay.security.guardrail.secret.IngressSecretScanner;
import io.github.kxng0109.cacherelay.security.guardrail.secret.SecretScanResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

/**
 * Background segment writer: drains accepted capture events in batches,
 * redacts PII with throwaway vaults, gates on secret scans, and appends
 * JSONL records plus sidecar manifest lines to hourly per-jurisdiction
 * segments.
 *
 * <p>Only redacted placeholders persist — never raw bodies, vaults, keys, or
 * mappings. A credential hit suppresses both bodies and keeps metadata with
 * the rule id and masked anchor. Segments rotate on hour or size; fsync runs
 * on rotation and close, so an OS crash can lose at most the open segment
 * tail (documented, analytics-grade durability).</p>
 */
@Slf4j
@Component
public class CaptureWriter implements AutoCloseable {

	private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyyMMdd-HH");

	private static final Pattern SAFE_JURISDICTION = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

	private final CaptureService service;

	private final CaptureProperties properties;

	private final PiiAnonymizer pii;

	private final IngressSecretScanner secrets;

	private final ObjectMapper mapper;

	private final Clock clock;

	private final Path dir;

	private final Map<String, OpenSegment> open = new LinkedHashMap<>();

	private final Map<String, Integer> generations = new HashMap<>();

	private final AtomicLong written = new AtomicLong();

	private volatile boolean closed;

	private Thread worker;

	/**
	 * Creates the writer (call {@link #start()} to drain).
	 *
	 * @param service    event source, never {@code null}
	 * @param properties capture ceilings, never {@code null}
	 * @param pii        PII redactor, never {@code null}
	 * @param secrets    credential gate, never {@code null}
	 * @param mapper     JSON mapper, never {@code null}
	 * @param clock      clock for rotation stamps, never {@code null}
	 */
	public CaptureWriter(CaptureService service, CaptureProperties properties,
			PiiAnonymizer pii, IngressSecretScanner secrets, ObjectMapper mapper, Clock clock) {
		this.service = service;
		this.properties = properties;
		this.pii = pii;
		this.secrets = secrets;
		this.mapper = mapper;
		this.clock = clock;
		this.dir = Path.of(properties.captureDir());
		try {
			Files.createDirectories(dir);
		} catch (IOException failed) {
			throw new IllegalStateException("Capture directory unavailable", failed);
		}
	}

	/**
	 * Starts the single background drain thread (daemon, parks on empty).
	 */
	@PostConstruct
	public void start() {
		worker = new Thread(this::loop, "capture-writer");
		worker.setDaemon(true);
		worker.start();
	}

	private void loop() {
		while (!closed) {
			try {
				CaptureEvent first = service.queue().poll(1L, TimeUnit.SECONDS);
				if (first == null) {
					continue;
				}
				List<CaptureEvent> batch = new ArrayList<>(512);
				batch.add(first);
				service.queue().drainTo(batch, 499);
				writeBatch(batch);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			} catch (RuntimeException failed) {
				log.debug("Dropping capture batch: {}", String.valueOf(failed.getMessage()));
			}
		}
	}

	/**
	 * Writes one batch synchronously (tests and shutdown paths).
	 *
	 * @param batch events, never {@code null}
	 */
	void writeBatch(List<CaptureEvent> batch) {
		for (CaptureEvent event : batch) {
			try {
				writeRecord(event);
			} catch (RuntimeException failed) {
				log.debug("Dropping poison capture record: {}",
						String.valueOf(failed.getMessage()));
			}
		}
		flushAll();
	}

	private void writeRecord(CaptureEvent event) {
		CaptureProperties.CaptureRule rule = properties.ruleFor(event.ownerId()).orElse(null);
		if (rule == null) {
			rule = properties.ruleFor(event.keyHash()).orElse(null);
		}
		String jurisdiction = rule == null ? "default" : rule.jurisdiction();
		int ttlDays = rule == null ? properties.defaultTtlDays()
				: rule.effectiveTtlDays(properties.defaultTtlDays(), properties.strictTtlDays());
		Instant expiresAt = event.capturedAt().plusSeconds(ttlDays * 86400L);
		boolean[] promptTruncated = new boolean[1];
		boolean[] outputTruncated = new boolean[1];
		String prompt = truncate(event.promptJson(), promptTruncated);
		String output = event.outputJson() == null ? null
				: truncate(event.outputJson(), outputTruncated);
		String redactedPrompt = pii.anonymize(prompt, new EphemeralPiiVault());
		String redactedOutput = output == null ? null
				: pii.anonymize(output, new EphemeralPiiVault());
		SecretScanResult promptScan = scan(redactedPrompt);
		SecretScanResult outputScan = output == null ? SecretScanResult.clean()
				: scan(redactedOutput);
		String suppressedBy = null;
		String maskedAnchor = null;
		if (promptScan.detected() || outputScan.detected()) {
			SecretScanResult hit = promptScan.detected() ? promptScan : outputScan;
			suppressedBy = hit.ruleId();
			maskedAnchor = hit.maskedToken();
			redactedPrompt = null;
			redactedOutput = null;
		}
		String ownerHash = event.ownerId() == null ? null
				: RefreshService.sha256Hex(event.ownerId());
		Map<String, Object> record = new LinkedHashMap<>();
		record.put("request_id", event.requestId().toString());
		record.put("owner_hash", ownerHash);
		record.put("model", event.model());
		record.put("provider", event.provider());
		record.put("streaming", event.streaming());
		record.put("prompt_tokens", event.promptTokens());
		record.put("completion_tokens", event.completionTokens());
		record.put("prompt_truncated", promptTruncated[0]);
		record.put("output_truncated", outputTruncated[0]);
		record.put("suppressed_by", suppressedBy);
		record.put("masked_anchor", maskedAnchor);
		record.put("prompt", redactedPrompt);
		record.put("output", redactedOutput);
		record.put("captured_at", event.capturedAt().toString());
		record.put("expires_at", expiresAt.toString());
		Map<String, Object> manifest = new LinkedHashMap<>();
		manifest.put("request_id", event.requestId().toString());
		manifest.put("owner_hash", ownerHash);
		manifest.put("jurisdiction", jurisdiction);
		manifest.put("expires_at", expiresAt.toString());
		try {
			String line = mapper.writeValueAsString(record);
			String manifestLine = mapper.writeValueAsString(manifest);
			append(jurisdiction, event.capturedAt(), line, manifestLine);
			written.incrementAndGet();
		} catch (Exception failed) {
			log.debug("Dropping capture record: {}", String.valueOf(failed.getMessage()));
		}
	}

	private SecretScanResult scan(String text) {
		if (text == null) {
			return SecretScanResult.clean();
		}
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		return secrets.scan(bytes, text);
	}

	private String truncate(String text, boolean[] truncated) {
		int max = properties.maxCharsPerField();
		if (text.length() <= max) {
			truncated[0] = false;
			return text;
		}
		truncated[0] = true;
		return text.substring(0, max);
	}

	private void append(String jurisdiction, Instant capturedAt, String line,
			String manifestLine) {
		String safeJurisdiction = SAFE_JURISDICTION.matcher(jurisdiction).matches() ? jurisdiction
				: "default";
		String hour = capturedAt.atZone(ZoneOffset.UTC).format(HOUR);
		String base = "capture-" + safeJurisdiction + "-" + hour;
		int generation = generations.getOrDefault(base, 0);
		String name = generation == 0 ? base + ".jsonl" : base + "-g" + generation + ".jsonl";
		OpenSegment segment = open.get(name);
		try {
			byte[] recordBytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
			byte[] manifestBytes = (manifestLine + "\n").getBytes(StandardCharsets.UTF_8);
			if (segment == null) {
				segment = open(name);
				open.put(name, segment);
			}
			if (segment.bytes() > 0
					&& segment.bytes() + recordBytes.length > properties.maxSegmentBytes()) {
				rotate(segment);
				generation++;
				generations.put(base, generation);
				name = base + "-g" + generation + ".jsonl";
				segment = open(name);
				open.put(name, segment);
			}
			segment.out.write(recordBytes);
			segment.manifest.write(manifestBytes);
			open.put(name, segment.withBytes(segment.bytes() + recordBytes.length));
		} catch (IOException failed) {
			throw new IllegalStateException("Capture append failed", failed);
		}
	}

	private OpenSegment open(String name) throws IOException {
		Path dataPath = dir.resolve(name);
		long existing = Files.exists(dataPath) ? Files.size(dataPath) : 0L;
		FileOutputStream dataFile = new FileOutputStream(dataPath.toFile(), true);
		FileOutputStream manifestFile = new FileOutputStream(
				dir.resolve(name + ".meta.jsonl").toFile(), true);
		OutputStream data = new BufferedOutputStream(dataFile, 65536);
		OutputStream manifest = new BufferedOutputStream(manifestFile, 65536);
		return new OpenSegment(data, manifest, dataFile.getChannel(), manifestFile.getChannel(),
				existing);
	}

	private void rotate(OpenSegment segment) {
		if (segment == null) {
			return;
		}
		try {
			segment.out.flush();
			segment.manifest.flush();
			segment.dataChannel.force(true);
			segment.manifestChannel.force(true);		} catch (IOException failed) {
			log.debug("Capture rotate flush failed: {}", String.valueOf(failed.getMessage()));
		} finally {
			try {
				segment.out.close();
				segment.manifest.close();
			} catch (IOException failed) {
				log.debug("Capture rotate close failed: {}", String.valueOf(failed.getMessage()));
			}
		}
	}

	private void flushAll() {
		for (OpenSegment segment : open.values()) {
			try {
				segment.out.flush();
				segment.manifest.flush();
			} catch (IOException failed) {
				log.debug("Capture flush failed: {}", String.valueOf(failed.getMessage()));
			}
		}
	}

	/**
	 * Stops the drain thread, flushing and syncing open segments. Unwritten
	 * queue depth is left to the service counters.
	 */
	@PreDestroy
	@Override
	public void close() {
		closed = true;
		if (worker != null) {
			worker.interrupt();
			try {
				worker.join(5000L);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
		}
		for (OpenSegment segment : open.values()) {
			rotate(segment);
		}
		open.clear();
	}

	/**
	 * @return persisted records so far
	 */
	public long written() {
		return written.get();
	}

	private record OpenSegment(OutputStream out, OutputStream manifest, FileChannel dataChannel,
			FileChannel manifestChannel, long bytes) {

		OpenSegment withBytes(long bytes) {
			return new OpenSegment(out, manifest, dataChannel, manifestChannel, bytes);
		}
	}
}
