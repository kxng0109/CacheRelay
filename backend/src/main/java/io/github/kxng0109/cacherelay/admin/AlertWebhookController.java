package io.github.kxng0109.cacherelay.admin;

import io.github.kxng0109.cacherelay.auth.webhook.WebhookSecrets;
import io.github.kxng0109.cacherelay.config.OpenApiConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Dedicated Alertmanager webhook receiver (C5): the compose Alertmanager
 * delivers detector alerts here with a bearer credential instead of the
 * admin key.
 *
 * <p>Under {@code /v1/alerts/**}, so the security chain passes it through
 * and this controller validates the credential itself: the configured
 * {@code gateway.alerts.webhook-secret} is compared constant-time against
 * the {@code Authorization: Bearer} value. An unconfigured secret answers
 * 404 (the receiver does not exist); wrong or missing credentials answer
 * 401. Both carry no data. Batches stay bounded exactly like the operator
 * receiver ({@link AdminAlertWebhookController}).</p>
 *
 * <p>Receipt only: batches are validated, counted, and logged for the
 * operator trail. Delivery to humans rides the notification fanout, not this
 * endpoint.</p>
 */
@Slf4j
@RestController
@RequestMapping("/v1/alerts/webhook")
@Tag(name = "Alert Webhook", description = "Alertmanager receiver (webhook secret)")
public class AlertWebhookController {

	/**
	 * Bearer scheme prefix for the webhook credential.
	 */
	static final String BEARER_PREFIX = "Bearer ";

	private final AlertWebhookProperties properties;

	private final ObjectMapper objectMapper;

	private volatile Counter received;

	/**
	 * Creates the receiver.
	 *
	 * @param properties alert webhook configuration, never {@code null}
	 * @param objectMapper JSON codec, never {@code null}
	 * @param registry     metric registry, never {@code null}
	 */
	@Autowired
	public AlertWebhookController(AlertWebhookProperties properties, ObjectMapper objectMapper,
	                              MeterRegistry registry) {
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.received = Counter.builder("alertmanager.webhook.received.total")
				.description("Alertmanager webhook alerts accepted")
				.tag("receiver", "public")
				.register(registry != null ? registry : new SimpleMeterRegistry());
	}

	/**
	 * Accepts one Alertmanager webhook batch with a bearer credential.
	 *
	 * @param body webhook JSON (array of alerts), never {@code null}
	 * @param authorization {@code Authorization} header carrying the bearer secret
	 * @return 200 with the accepted count, 404 when unconfigured, 401 on bad
	 * credentials, or 400 for empty/oversized batches
	 */
	@Operation(summary = "Receive Alertmanager webhook batch (webhook secret)")
	@PostMapping(consumes = "application/json", produces = "application/json")
	public ResponseEntity<JsonNode> receive(@RequestBody @Nullable JsonNode body,
	                                        @RequestHeader(value = "Authorization", required = false)
	                                        @Nullable String authorization) {
		String secret = properties.getWebhookSecret();
		if (secret == null || secret.isBlank()) {
			return ResponseEntity.notFound().build();
		}
		String presented = authorization != null && authorization.startsWith(BEARER_PREFIX)
				? authorization.substring(BEARER_PREFIX.length())
				: null;
		if (!WebhookSecrets.constantTimeEquals(secret, presented)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		if (body == null || !body.isArray() || body.isEmpty()
				|| body.size() > AdminAlertWebhookController.MAX_ALERTS_PER_BATCH) {
			return ResponseEntity.badRequest().body(objectMapper.createObjectNode()
					.put("error", "batch must be a non-empty array of at most "
							+ AdminAlertWebhookController.MAX_ALERTS_PER_BATCH + " alerts"));
		}
		for (JsonNode alert : body) {
			JsonNode name = alert.path("labels").path("alertname");
			log.info("Alertmanager webhook received alert '{}' status '{}'",
					name.isString() ? name.asString() : "unknown",
					alert.path("status").isString() ? alert.path("status").asString() : "unknown");
			received.increment();
		}
		return ResponseEntity.ok(objectMapper.createObjectNode().put("received", body.size()));
	}
}
