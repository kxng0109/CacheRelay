package io.github.kxng0109.cacherelay.admin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import io.github.kxng0109.cacherelay.auth.AuthAuditService;
import io.github.kxng0109.cacherelay.auth.RefreshService;
import io.github.kxng0109.cacherelay.capture.CaptureProperties;
import io.github.kxng0109.cacherelay.capture.CapturePurger;
import io.github.kxng0109.cacherelay.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Captured usage content reads under {@code /v1/admin/capture}: bounded
 * recent-record scans plus owner erasure. Every access is audit-logged;
 * records carry redacted placeholders only, never raw bodies.
 */
@RestController
@RequestMapping("/v1/admin/capture")
@RequiredArgsConstructor
@Tag(name = "Admin - Capture", description = "Captured usage content reads and erasure")
public class AdminCaptureController {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private final CaptureProperties properties;

	private final CapturePurger purger;

	private final AuthAuditService audit;

	private final Clock clock;

	/**
	 * Reads recent captured records, newest segments first.
	 *
	 * @param ownerId owner id filter, or {@code null} for all owners
	 * @param limit   row cap between 1 and 100
	 * @param since   lower bound, or {@code null}
	 * @param request current request (admin attribution)
	 * @return HTTP 200 OK with redacted records
	 */
	@Operation(
			summary = "Read recent captured records",
			description = "Bounded scan of recent capture segments with expired rows filtered.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Redacted records",
					content = @Content(mediaType = "application/json",
							array = @ArraySchema(schema = @Schema(implementation = Object.class)))),
			@ApiResponse(responseCode = "400", description = "Invalid limit or window"),
			@ApiResponse(responseCode = "401", description = "Unauthorized")
	})
	@GetMapping("/recent")
	public ResponseEntity<List<JsonNode>> recent(
			@Parameter(description = "Owner id filter")
			@RequestParam(value = "owner", required = false) String ownerId,
			@Parameter(description = "Row cap between 1 and 100")
			@RequestParam(value = "limit", defaultValue = "20") int limit,
			@Parameter(description = "Lower bound (ISO-8601)")
			@RequestParam(value = "since", required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
			HttpServletRequest request) {
		if (limit < 1 || limit > 100) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"Parameter 'limit' must be between 1 and 100");
		}
		String ownerHash = ownerId == null ? null : RefreshService.sha256Hex(ownerId);
		List<JsonNode> rows = scan(ownerHash, since, limit);
		Object attribution = request.getAttribute(AdminAuthFilter.ATTRIBUTE_ADMIN_ID);
		String adminActor = attribution instanceof UUID uuid ? uuid.toString() : "master-key";
		audit.record(AuthAuditService.ACTION_CAPTURE_READ, AuthAuditService.SEVERITY_INFO,
				adminActor, "/v1/admin/capture/recent", AuthAuditService.OUTCOME_SUCCESS,
				request.getRemoteAddr(), request.getHeader("X-Request-ID"));
		return ResponseEntity.ok(rows);
	}

	/**
	 * Erases one owner's captured rows across segments.
	 *
	 * @param ownerId owner id, never {@code null}
	 * @param request current request (admin attribution)
	 * @return HTTP 200 OK with the rewritten segment count
	 */
	@Operation(
			summary = "Erase one owner's captured rows",
			description = "Rewrites segments dropping the owner's rows (erasure requests).",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Erasure complete"),
			@ApiResponse(responseCode = "401", description = "Unauthorized")
	})
	@DeleteMapping("/owner/{ownerId}")
	public ResponseEntity<Integer> eraseOwner(
			@Parameter(description = "Owner id")
			@PathVariable("ownerId") String ownerId,
			HttpServletRequest request) {
		int rewritten = purger.purgeOwner(ownerId);
		Object attribution = request.getAttribute(AdminAuthFilter.ATTRIBUTE_ADMIN_ID);
		String adminActor = attribution instanceof UUID uuid ? uuid.toString() : "master-key";
		audit.record(AuthAuditService.ACTION_CAPTURE_READ, AuthAuditService.SEVERITY_INFO,
				adminActor, "/v1/admin/capture/owner/" + ownerId,
				AuthAuditService.OUTCOME_SUCCESS, request.getRemoteAddr(),
				request.getHeader("X-Request-ID"));
		return ResponseEntity.ok(rewritten);
	}

	private List<JsonNode> scan(String ownerHash, Instant since, int limit) {
		List<JsonNode> rows = new ArrayList<>();
		Path dir = Path.of(properties.captureDir());
		if (!Files.isDirectory(dir)) {
			return rows;
		}
		List<Path> segments;
		try (Stream<Path> files = Files.list(dir)) {
			segments = files
					.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
					.filter(path -> !path.getFileName().toString().endsWith(".meta.jsonl"))
					.sorted(Comparator.comparing(
							path -> path.getFileName().toString(), Comparator.reverseOrder()))
					.limit(24)
					.toList();
		} catch (Exception failed) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "capture store unreadable");
		}
		Instant now = clock.instant();
		for (Path segment : segments) {
			List<String> lines;
			try {
				lines = Files.readAllLines(segment, StandardCharsets.UTF_8);
			} catch (Exception failed) {
				continue;
			}
			for (int index = lines.size() - 1; index >= 0; index--) {
				JsonNode row;
				try {
					row = MAPPER.readTree(lines.get(index));
				} catch (Exception corrupt) {
					continue;
				}
				if (expired(row, now) || (since != null && !capturedAfter(row, since))) {
					continue;
				}
				if (ownerHash != null
						&& !ownerHash.equals(row.path("owner_hash").asString(null))) {
					continue;
				}
				rows.add(row);
				if (rows.size() >= limit) {
					return rows;
				}
			}
		}
		return rows;
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

	private boolean capturedAfter(JsonNode row, Instant since) {
		String capturedAt = row.path("captured_at").asString(null);
		if (capturedAt == null) {
			return false;
		}
		try {
			return !Instant.parse(capturedAt).isBefore(since);
		} catch (Exception malformed) {
			return false;
		}
	}
}
