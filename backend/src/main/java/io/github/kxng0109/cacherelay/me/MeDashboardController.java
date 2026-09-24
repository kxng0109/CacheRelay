package io.github.kxng0109.cacherelay.me;

import java.time.Instant;
import java.util.UUID;

import io.github.kxng0109.cacherelay.admin.dto.LedgerSummaryResponse;
import io.github.kxng0109.cacherelay.auth.JwtService;
import io.github.kxng0109.cacherelay.auth.UserAccountRepository;
import io.github.kxng0109.cacherelay.config.OpenApiConfig;
import io.github.kxng0109.cacherelay.ledger.DashboardService;
import io.github.kxng0109.cacherelay.ledger.DashboardView;
import io.github.kxng0109.cacherelay.security.filter.KeyAuthFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Personal dashboard surface under {@code /v1/me/usage} for
 * session-authenticated accounts. The owner scope always derives from the
 * session identity server-side: callers can only ever read their own usage.
 * Freshness coordinates ride on response headers so the summary shape stays
 * identical to the admin ledger shape.
 */
@RestController
@RequestMapping("/v1/me/usage")
@RequiredArgsConstructor
@Tag(name = "Self-service usage", description = "Session-authenticated personal usage dashboard")
public class MeDashboardController {

	/**
	 * Freshness header carrying when the view was computed.
	 */
	public static final String HEADER_GENERATED_AT = DashboardService.HEADER_GENERATED_AT;

	/**
	 * Freshness header carrying the newest ledger row counted.
	 */
	public static final String HEADER_WATERMARK = DashboardService.HEADER_WATERMARK;

	private final DashboardService dashboards;

	private final JwtService sessions;

	private final UserAccountRepository users;

	/**
	 * Returns the caller's personal usage summary.
	 *
	 * @param authorization session bearer token
	 * @param from          window start inclusive, or trailing default when absent
	 * @param to            window end inclusive, or now when absent
	 * @return HTTP 200 OK with the caller's summary and freshness headers
	 */
	@Operation(
			summary = "Get my usage dashboard",
			description = "Returns token consumption, costs, and breakdowns across the caller's owned keys.",
			security = @SecurityRequirement(name = OpenApiConfig.SCHEME_BEARER_AUTH)
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Personal summary",
					content = @Content(mediaType = "application/json",
							schema = @Schema(implementation = LedgerSummaryResponse.class))),
			@ApiResponse(responseCode = "400", description = "Invalid window"),
			@ApiResponse(responseCode = "401", description = "Invalid session"),
			@ApiResponse(responseCode = "429", description = "Dashboard view rate exceeded")
	})
	@GetMapping
	public ResponseEntity<LedgerSummaryResponse> myUsage(
			@Parameter(description = "Session bearer token", hidden = true)
			@RequestHeader("Authorization") String authorization,
			@Parameter(description = "Start timestamp (ISO-8601)")
			@RequestParam(value = "from", required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@Parameter(description = "End timestamp (ISO-8601)")
			@RequestParam(value = "to", required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
		UUID userId = requireSelf(authorization);
		DashboardView view = dashboards.getPersonal(userId, from, to);
		return ResponseEntity.ok()
				.header(HEADER_GENERATED_AT, view.generatedAt().toString())
				.header(HEADER_WATERMARK, view.watermark().toString())
				.body(view.summary());
	}

	private UUID requireSelf(String authorization) {
		String token = authorization == null ? "" : authorization.trim();
		if (token.startsWith(KeyAuthFilter.AUTH_SCHEME)) {
			token = token.substring(KeyAuthFilter.AUTH_SCHEME.length()).trim();
		}
		final Jwt decoded;
		try {
			decoded = sessions.validate(token);
		} catch (RuntimeException invalid) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid session");
		}
		if (decoded == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid session");
		}
		final UUID userId;
		try {
			userId = UUID.fromString(decoded.getSubject());
		} catch (RuntimeException malformed) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid session");
		}
		boolean active = users.findById(userId).map(account -> !account.isDisabled()).orElse(false);
		if (!active) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid session");
		}
		return userId;
	}
}
