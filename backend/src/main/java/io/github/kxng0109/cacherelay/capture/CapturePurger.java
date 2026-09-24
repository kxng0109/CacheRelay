package io.github.kxng0109.cacherelay.capture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.github.kxng0109.cacherelay.auth.RefreshService;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retention enforcement for capture segments: whole-segment deletes past
 * jurisdiction TTL, row-level rewrites for individually expired rows and
 * owner erasure (GDPR Art.17). Rewrites are atomic file moves; corrupt lines
 * survive rewrites (fail-safe against data loss, whole-file TTL still
 * applies).
 */
@Slf4j
@Component
public class CapturePurger {

	private static final Pattern HOUR = Pattern.compile("\\d{8}-\\d{2}$");

	private final CaptureProperties properties;

	private final JsonMapper mapper;

	private final Clock clock;

	/**
	 * Creates the purger.
	 *
	 * @param properties capture ceilings, never {@code null}
	 * @param mapper     JSON mapper, never {@code null}
	 * @param clock      clock for expiry, never {@code null}
	 */
	public CapturePurger(CaptureProperties properties, JsonMapper mapper, Clock clock) {
		this.properties = properties;
		this.mapper = mapper;
		this.clock = clock;
	}

	/**
	 * Nightly purge of expired segments and rows.
	 */
	@Scheduled(cron = "${gateway.capture.purge-cron:0 0 3 * * *}")
	public void purgeExpired() {
		purge(clock.instant());
	}

	/**
	 * Purges expired segments and rows as of one instant (tests).
	 *
	 * @param now cutoff instant, never {@code null}
	 */
	void purge(Instant now) {
		Path dir = Path.of(properties.captureDir());
		if (!Files.isDirectory(dir)) {
			return;
		}
		List<Path> segments;
		try (Stream<Path> files = Files.list(dir)) {
			segments = files
					.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
					.filter(path -> !path.getFileName().toString().endsWith(".meta.jsonl"))
					.toList();
		} catch (IOException failed) {
			log.debug("Capture purge listing failed: {}", String.valueOf(failed.getMessage()));
			return;
		}
		for (Path segment : segments) {
			purgeSegment(segment, now);
		}
	}

	/**
	 * Erases one owner's rows across recent segments (erasure requests).
	 *
	 * @param ownerId owner id, never {@code null}
	 * @return rewritten segments count
	 */
	public int purgeOwner(String ownerId) {
		String hash = RefreshService.sha256Hex(ownerId);
		Path dir = Path.of(properties.captureDir());
		if (!Files.isDirectory(dir)) {
			return 0;
		}
		int rewritten = 0;
		List<Path> segments;
		try (Stream<Path> files = Files.list(dir)) {
			segments = files
					.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
					.filter(path -> !path.getFileName().toString().endsWith(".meta.jsonl"))
					.toList();
		} catch (IOException failed) {
			log.debug("Capture owner purge listing failed: {}",
					String.valueOf(failed.getMessage()));
			return 0;
		}
		for (Path segment : segments) {
			if (rewrite(segment, clock.instant(), hash)) {
				rewritten++;
			}
		}
		return rewritten;
	}

	private void purgeSegment(Path segment, Instant now) {
		String name = segment.getFileName().toString();
		String hour = hourOf(name);
		String jurisdiction = jurisdictionOf(name);
		if (hour != null) {
			int ttlDays = "strict".equals(jurisdiction) ? properties.strictTtlDays()
					: properties.defaultTtlDays();
			LocalDate day = LocalDate.parse(hour.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
			if (day.plusDays(ttlDays).atStartOfDay(ZoneOffset.UTC).toInstant().isBefore(now)) {
				deletePair(segment);
				return;
			}
		}
		rewrite(segment, now, null);
	}

	private boolean rewrite(Path segment, Instant now, String ownerHash) {
		List<String> kept = new ArrayList<>();
		boolean changed = false;
		List<String> lines;
		try {
			lines = Files.readAllLines(segment, StandardCharsets.UTF_8);
		} catch (IOException failed) {
			log.debug("Capture rewrite read failed: {}", String.valueOf(failed.getMessage()));
			return false;
		}
		for (String line : lines) {
			JsonNode row;
			try {
				row = mapper.readTree(line);
			} catch (Exception corrupt) {
				kept.add(line);
				continue;
			}
			if (expired(row, now) || (ownerHash != null
					&& ownerHash.equals(row.path("owner_hash").asString(null)))) {
				changed = true;
				continue;
			}
			kept.add(line);
		}
		if (!changed) {
			return false;
		}
		try {
			Path temporary = segment.resolveSibling(segment.getFileName() + ".tmp");
			Files.write(temporary, kept, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
					StandardOpenOption.WRITE);
			Files.move(temporary, segment, StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
			return true;
		} catch (IOException failed) {
			log.debug("Capture rewrite write failed: {}", String.valueOf(failed.getMessage()));
			return false;
		}
	}

	private boolean expired(JsonNode row, Instant now) {
		String expiresAt = row.path("expires_at").asString(null);
		if (expiresAt == null) {
			return false;
		}
		try {
			return !Instant.parse(expiresAt).isAfter(now);
		} catch (Exception malformed) {
			return false;
		}
	}

	private void deletePair(Path segment) {
		try {
			Files.deleteIfExists(segment);
			Files.deleteIfExists(Path.of(segment + ".meta.jsonl"));
		} catch (IOException failed) {
			log.debug("Capture segment delete failed: {}", String.valueOf(failed.getMessage()));
		}
	}

	private String hourOf(String name) {
		String base = stripGeneration(name.substring(0, name.length() - ".jsonl".length()));
		String tail = base.length() >= 11 ? base.substring(base.length() - 11) : "";
		return HOUR.matcher(tail).matches() ? tail : null;
	}

	private String jurisdictionOf(String name) {
		String base = stripGeneration(name.substring(0, name.length() - ".jsonl".length()));
		String withoutHour = base.replaceAll("-\\d{8}-\\d{2}$", "");
		String jurisdiction = withoutHour.startsWith("capture-")
				? withoutHour.substring("capture-".length())
				: withoutHour;
		return jurisdiction.isEmpty() ? "default" : jurisdiction;
	}

	private String stripGeneration(String base) {
		return base.replaceAll("-g\\d+$", "");
	}
}
