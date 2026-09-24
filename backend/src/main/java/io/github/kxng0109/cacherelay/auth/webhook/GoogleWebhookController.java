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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Google Workspace push receiver: channel-token validated user change events
 * that invalidate revalidation watermarks.
 *
 * <p>Directory push carries no signature; trust comes from matching the
 * channel id, channel token, and resource id against the configured entry.
 * Sync messages and unknown channels answer 200 without touching stores.
 * Event bodies carry the immutable user id, which links store as subject.</p>
 */
@RestController
@RequestMapping("/v1/sso/webhooks/google")
@RequiredArgsConstructor
@Tag(name = "SSO webhooks", description = "IdP push-event receivers")
public class GoogleWebhookController {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private final SsoWebhookProperties webhookProperties;

	private final WebhookInvalidator invalidator;

	private final AuthAuditService audit;

	/**
	 * Receives one Directory push message.
	 *
	 * @param channelId  channel id header, or {@code null}
	 * @param token      channel token header, or {@code null}
	 * @param resourceId resource id header, or {@code null}
	 * @param state      resource state header, or {@code null}
	 * @param request    raw servlet request for the event body
	 * @return HTTP 200 on accepted, ignored, or malformed deliveries
	 */
	@Operation(summary = "Receive Google Workspace push events",
			description = "Channel-token validated user changes invalidate watermarks.")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Accepted or ignored"),
			@ApiResponse(responseCode = "401", description = "Unknown channel or token"),
			@ApiResponse(responseCode = "404", description = "Receiver unconfigured")
	})
	@PostMapping
	public ResponseEntity<Void> receive(
			@Parameter(description = "Channel id", hidden = true)
			@RequestHeader(value = "X-Goog-Channel-ID", required = false) String channelId,
			@Parameter(description = "Channel token", hidden = true)
			@RequestHeader(value = "X-Goog-Channel-Token", required = false) String token,
			@Parameter(description = "Resource id", hidden = true)
			@RequestHeader(value = "X-Goog-Resource-ID", required = false) String resourceId,
			@Parameter(description = "Resource state", hidden = true)
			@RequestHeader(value = "X-Goog-Resource-State", required = false) String state,
			HttpServletRequest request) {
		Optional<SsoWebhookProperties.RegistrationWebhook> entry =
				webhookProperties.forRegistration("google");
		if (entry.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "receiver unconfigured");
		}
		if (!matches(entry.get(), channelId, token, resourceId)) {			audit.record(AuthAuditService.ACTION_WEBHOOK_AUTH, AuthAuditService.SEVERITY_WARN,
					"webhook:google", "/v1/sso/webhooks/google",
					AuthAuditService.OUTCOME_FAILURE, null, channelId);
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unknown channel");
		}
		if (!"sync".equalsIgnoreCase(state)) {
			byte[] body = rawBody(request);
			try {
				JsonNode root = MAPPER.readTree(body);
				invalidator.invalidate("google", root.path("id").asString(null));
			} catch (RuntimeException malformed) {
				return ResponseEntity.ok().build();
			}
		}
		return ResponseEntity.ok().build();
	}

	private boolean matches(SsoWebhookProperties.RegistrationWebhook entry, String channelId,
			String token, String resourceId) {
		return channelId != null && !channelId.isBlank()
				&& resourceId != null && !resourceId.isBlank()
				&& entry.googleChannelToken().equals(token);
	}

	private byte[] rawBody(HttpServletRequest request) {
		try {
			return request.getInputStream().readAllBytes();
		} catch (IOException failed) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unreadable body");
		}
	}
}
