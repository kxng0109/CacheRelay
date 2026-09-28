package io.github.kxng0109.cacherelay.admin;

import io.github.kxng0109.cacherelay.config.OpenApiConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Authenticated local receiver for Alertmanager webhooks (FS-B17): the
 * compose Alertmanager routes detector alerts here instead of a dead URL.
 * Under {@code /v1/admin/**}, so {@link AdminAuthFilter} authenticates every
 * call — Alertmanager must send the admin key (configure
 * {@code http_config.authorization} if the receiver ever leaves loopback).
 *
 * <p>Receipt only: batches are validated, counted, and logged for the
 * operator trail. Delivery to humans rides the notification fanout, not this
 * endpoint.</p>
 */
@Slf4j
@RestController
@RequestMapping("/v1/admin/alerts/webhook")
@Tag(name = "Admin - Alert Webhook", description = "Alertmanager receiver (authenticated)")
public class AdminAlertWebhookController {

	static final int MAX_ALERTS_PER_BATCH = 100;

	private final ObjectMapper objectMapper;

	private volatile Counter received;

	/**
	 * Creates the receiver.
	 *
	 * @param objectMapper JSON codec, never {@code null}
	 * @param registry     metric registry, never {@code null}
	 */
	@Autowired
	public AdminAlertWebhookController(ObjectMapper objectMapper, MeterRegistry registry) {
		this.objectMapper = objectMapper;
		this.received = Counter.builder("alertmanager.webhook.received.total")
				.description("Alertmanager webhook alerts accepted")
				.register(registry != null ? registry : new SimpleMeterRegistry());
	}

	/**
	 * Accepts one Alertmanager webhook batch.
	 *
	 * @param body webhook JSON (array of alerts), never {@code null}
	 * @return 200 with the accepted count, or 400 for empty/oversized batches
	 */
	@Operation(
			summary = "Receive Alertmanager webhook batch",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@PostMapping(consumes = "application/json", produces = "application/json")
	public ResponseEntity<JsonNode> receive(@RequestBody @Nullable JsonNode body) {
		if (body == null || !body.isArray() || body.isEmpty()
				|| body.size() > MAX_ALERTS_PER_BATCH) {
			return ResponseEntity.badRequest().body(objectMapper.createObjectNode()
					.put("error", "batch must be a non-empty array of at most "
							+ MAX_ALERTS_PER_BATCH + " alerts"));
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
