package io.github.kxng0109.cacherelay.auth.webhook;

import java.io.IOException;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Entra change-notification receiver: validation handshake plus user and
 * group-member changes that invalidate revalidation watermarks.
 *
 * <p>Validation answers the URL-decoded token as {@code text/plain}. Every
 * notification's {@code clientState} must equal the configured secret, or
 * the delivery is discarded as untrusted. Changed users resolve by object
 * id; member deltas resolve the member id; group-only changes carry no user
 * and are left to the sweep. Fast 200s keep Graph's 3s budget.</p>
 */
@RestController
@RequestMapping("/v1/sso/webhooks/entra")
@RequiredArgsConstructor
@Tag(name = "SSO webhooks", description = "IdP push-event receivers")
public class EntraWebhookController {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private final SsoWebhookProperties webhookProperties;

	private final WebhookInvalidator invalidator;

	private final AuthAuditService audit;

	/**
	 * Receives validation handshakes and change notifications.
	 *
	 * @param validationToken validation token on handshakes, or {@code null}
	 * @param request         raw servlet request for the exact body bytes
	 * @return HTTP 200 with the token, or HTTP 200/202 on notifications
	 */
	@Operation(summary = "Receive Entra change notifications",
			description = "Validation handshake plus client-state validated user changes.")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Validated or accepted"),
			@ApiResponse(responseCode = "400", description = "Signed but malformed"),
			@ApiResponse(responseCode = "401", description = "Bad client state"),
			@ApiResponse(responseCode = "404", description = "Receiver unconfigured")
	})
	@PostMapping
	public ResponseEntity<String> receive(
			@Parameter(description = "Validation token on handshakes", hidden = true)
			@RequestParam(value = "validationToken", required = false) String validationToken,
			HttpServletRequest request) {
		Optional<String> secret = clientState();
		if (secret.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "receiver unconfigured");
		}
		if (validationToken != null) {
			return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(validationToken);
		}
		byte[] body = rawBody(request);
		JsonNode root;
		try {
			root = MAPPER.readTree(body);
		} catch (RuntimeException malformed) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "malformed payload");
		}
		JsonNode values = root.path("value");
		if (!values.isArray()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "malformed payload");
		}
		for (JsonNode notification : values) {
			if (!secret.get().equals(notification.path("clientState").asString(null))) {
				audit.record(AuthAuditService.ACTION_WEBHOOK_AUTH, AuthAuditService.SEVERITY_WARN,
						"webhook:entra", "/v1/sso/webhooks/entra",
						AuthAuditService.OUTCOME_FAILURE, null, null);
				throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "bad client state");
			}
			invalidateResource(notification.path("resource").asString(null));
		}
		return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body("accepted");
	}

	private Optional<String> clientState() {
		return webhookProperties.forRegistration("azure")
				.map(SsoWebhookProperties.RegistrationWebhook::entraClientState);
	}

	private byte[] rawBody(HttpServletRequest request) {
		try {
			return request.getInputStream().readAllBytes();
		} catch (IOException failed) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unreadable body");
		}
	}

	private void invalidateResource(String resource) {
		if (resource == null || resource.isBlank()) {
			return;
		}
		String[] segments = resource.split("/");
		if (segments.length == 2 && "users".equalsIgnoreCase(segments[0])) {
			invalidator.invalidate("azure", segments[1]);
		} else if (segments.length == 4 && "groups".equalsIgnoreCase(segments[0])
				&& "members".equalsIgnoreCase(segments[2])) {
			invalidator.invalidate("azure", segments[3]);
		}
	}
}
