package io.github.kxng0109.cacherelay.auth.webhook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;

import io.github.kxng0109.cacherelay.auth.AuthAuditService;
import io.github.kxng0109.cacherelay.auth.SsoWebhookProperties;
import io.github.kxng0109.cacherelay.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Okta event-hook receiver: one-time verification handshake plus lifecycle
 * events that invalidate revalidation watermarks.
 *
 * <p>Okta signs nothing; trust comes from the configured header secret over
 * HTTPS, verified in constant time before parsing. User-lifecycle events
 * resolve User targets by id; the sweep owns revocation minutes later.
 * Responses stay empty and immediate (3s budget, one retry).</p>
 */
@RestController
@RequestMapping("/v1/sso/webhooks/okta")
@RequiredArgsConstructor
@Tag(name = "SSO webhooks", description = "IdP push-event receivers")
public class OktaWebhookController {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private final SsoWebhookProperties webhookProperties;

	private final WebhookInvalidator invalidator;

	private final AuthAuditService audit;

	/**
	 * Answers Okta's one-time endpoint verification.
	 *
	 * @param challenge verification challenge header, or {@code null}
	 * @return HTTP 200 with the echoed challenge
	 */
	@Operation(summary = "Verify the Okta event hook",
			description = "Echoes Okta's one-time verification challenge.")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Verified"),
			@ApiResponse(responseCode = "400", description = "Missing challenge"),
			@ApiResponse(responseCode = "404", description = "Receiver unconfigured")
	})
	@GetMapping
	public ResponseEntity<Map<String, String>> verify(
			@Parameter(description = "Verification challenge", hidden = true)
			@RequestHeader(value = "x-okta-verification-challenge", required = false)
			String challenge) {
		Optional<String> secret = oktaSecret();
		if (secret.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "receiver unconfigured");
		}
		if (challenge == null || challenge.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing challenge");
		}
		return ResponseEntity.ok(Map.of("verification", challenge));
	}

	/**
	 * Receives one Okta event-hook delivery.
	 *
	 * @param authorization configured header secret, or {@code null}
	 * @param request       raw servlet request for the exact body bytes
	 * @return HTTP 200 empty on accepted, ignored, or malformed deliveries
	 */
	@Operation(summary = "Receive Okta lifecycle events",
			description = "Header-secret validated user-lifecycle events invalidate watermarks.")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Accepted or ignored"),
			@ApiResponse(responseCode = "401", description = "Bad secret"),
			@ApiResponse(responseCode = "404", description = "Receiver unconfigured")
	})
	@PostMapping
	public ResponseEntity<Void> receive(
			@Parameter(description = "Configured hook secret", hidden = true)
			@RequestHeader(value = "Authorization", required = false) String authorization,
			HttpServletRequest request) {
		Optional<String> secret = oktaSecret();
		if (secret.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "receiver unconfigured");
		}
		if (!secretMatches(authorization, secret.get())) {
			audit.record(AuthAuditService.ACTION_WEBHOOK_AUTH, AuthAuditService.SEVERITY_WARN,
					"webhook:okta", "/v1/sso/webhooks/okta", AuthAuditService.OUTCOME_FAILURE,
					null, null);
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "bad secret");
		}
		byte[] body = rawBody(request);
		JsonNode root;
		try {
			root = MAPPER.readTree(body);
		} catch (RuntimeException malformed) {
			return ResponseEntity.ok().build();
		}
		JsonNode events = root.path("data").path("events");
		if (events.isArray()) {
			for (JsonNode event : events) {
				String type = event.path("eventType").asString(null);
				if (type == null || !type.startsWith("user.lifecycle.")) {
					continue;
				}
				for (JsonNode target : event.path("target")) {
					if (!"User".equals(target.path("type").asString(null))) {
						continue;
					}
					invalidator.invalidate("okta", target.path("id").asString(null));
				}
			}
		}
		return ResponseEntity.ok().build();
	}

	private Optional<String> oktaSecret() {
		return webhookProperties.forRegistration("okta")
				.map(SsoWebhookProperties.RegistrationWebhook::oktaSecret);
	}

	private boolean secretMatches(String presented, String expected) {
		if (presented == null || expected == null || expected.isBlank()) {
			return false;
		}
		return MessageDigest.isEqual(
				presented.getBytes(StandardCharsets.UTF_8),
				expected.getBytes(StandardCharsets.UTF_8));
	}

	private byte[] rawBody(HttpServletRequest request) {
		try {
			return request.getInputStream().readAllBytes();
		} catch (IOException failed) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unreadable body");
		}
	}
}
