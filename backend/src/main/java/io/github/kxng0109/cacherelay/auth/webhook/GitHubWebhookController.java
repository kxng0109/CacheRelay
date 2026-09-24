package io.github.kxng0109.cacherelay.auth.webhook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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
 * GitHub push receiver: HMAC-validated membership events invalidate
 * revalidation watermarks so the sweep re-checks promptly.
 *
 * <p>Verification precedes parsing on the exact raw bytes. Ping and
 * uninteresting events answer 200 without touching stores. Membership and
 * organization removals resolve affected links by numeric id first, then by
 * login, and stamp their watermarks at the epoch. Nothing here revokes
 * directly; the sweep owns revocation minutes later. Redeliveries are
 * naturally idempotent (re-stamping the epoch).</p>
 */
@RestController
@RequestMapping("/v1/sso/webhooks/github")
@RequiredArgsConstructor
@Tag(name = "SSO webhooks", description = "IdP push-event receivers")
public class GitHubWebhookController {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private final SsoWebhookProperties webhookProperties;

	private final WebhookInvalidator invalidator;

	private final AuthAuditService audit;

	/**
	 * Receives one GitHub delivery.
	 *
	 * @param signature HMAC signature header, or {@code null}
	 * @param event     event name header, or {@code null}
	 * @param request   raw servlet request for the exact body bytes
	 * @return HTTP 200 on accepted or ignored deliveries
	 */
	@Operation(summary = "Receive GitHub push events",
			description = "HMAC-validated membership events invalidate revalidation watermarks.")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Accepted or ignored"),
			@ApiResponse(responseCode = "400", description = "Signed but malformed"),
			@ApiResponse(responseCode = "401", description = "Bad signature"),
			@ApiResponse(responseCode = "404", description = "Receiver unconfigured")
	})
	@PostMapping
	public ResponseEntity<Void> receive(
			@Parameter(description = "HMAC-SHA256 signature", hidden = true)
			@RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
			@Parameter(description = "Event name", hidden = true)
			@RequestHeader(value = "X-GitHub-Event", required = false) String event,
			@Parameter(description = "Delivery id", hidden = true)
			@RequestHeader(value = "X-GitHub-Delivery", required = false) String delivery,
			HttpServletRequest request) {
		Optional<SsoWebhookProperties.RegistrationWebhook> entry =
				webhookProperties.forRegistration("github");
		if (entry.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "receiver unconfigured");
		}
		byte[] body = rawBody(request);
		if (!validSignature(body, signature, entry.get().githubSecret())) {
			audit.record(AuthAuditService.ACTION_WEBHOOK_AUTH, AuthAuditService.SEVERITY_WARN,
					"webhook:github", "/v1/sso/webhooks/github",
					AuthAuditService.OUTCOME_FAILURE, null, delivery);
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "bad signature");
		}
		if (!"membership".equals(event) && !"organization".equals(event)) {
			return ResponseEntity.ok().build();
		}
		JsonNode root;
		try {
			root = MAPPER.readTree(body);
		} catch (RuntimeException malformed) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "malformed payload");
		}
		invalidateFor(root);
		return ResponseEntity.ok().build();
	}

	/**
	 * Verifies an HMAC-SHA256 signature in constant time.
	 *
	 * @param body      exact raw bytes, never {@code null}
	 * @param signature header value, or {@code null}
	 * @param secret    webhook secret, never {@code null}
	 * @return {@code true} on exact match
	 */
	static boolean validSignature(byte[] body, String signature, String secret) {
		if (signature == null || !signature.startsWith("sha256=")) {
			return false;
		}
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			StringBuilder hex = new StringBuilder("sha256=");
			for (byte octet : mac.doFinal(body)) {
				hex.append(Character.forDigit((octet >> 4) & 0xF, 16));
				hex.append(Character.forDigit(octet & 0xF, 16));
			}
			return MessageDigest.isEqual(hex.toString().getBytes(StandardCharsets.UTF_8),
					signature.getBytes(StandardCharsets.UTF_8));
		} catch (RuntimeException | NoSuchAlgorithmException | InvalidKeyException failed) {
			return false;
		}
	}

	private byte[] rawBody(HttpServletRequest request) {
		try {
			return request.getInputStream().readAllBytes();
		} catch (IOException failed) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unreadable body");
		}
	}

	private void invalidateFor(JsonNode root) {
		JsonNode member = root.path("member");
		JsonNode membershipUser = root.path("membership").path("user");
		String id = text(member.path("id"));
		if (id == null) {
			id = text(membershipUser.path("id"));
		}
		String login = text(member.path("login"));
		if (login == null) {
			login = text(membershipUser.path("login"));
		}
		if (id == null && login == null) {
			return;
		}
		if (id == null || !invalidator.invalidate("github", id)) {
			invalidator.invalidate("github", login);
		}
	}

	private String text(JsonNode node) {
		String value = node.asString(null);
		return value == null || value.isBlank() ? null : value;
	}
}
