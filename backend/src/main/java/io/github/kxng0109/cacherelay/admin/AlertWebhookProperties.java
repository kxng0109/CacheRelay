package io.github.kxng0109.cacherelay.admin;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the Alertmanager webhook surface under
 * {@code gateway.alerts}.
 *
 * <p>Alertmanager cannot present admin credentials, so the dedicated
 * receiver ({@code POST /v1/alerts/webhook}) validates a bearer credential
 * against this secret instead. Injected exclusively from the runtime
 * environment via {@code GATEWAY_ALERTS_WEBHOOK_SECRET}; no default is
 * provided. Outside the {@code dev}/{@code test} profiles the application
 * fails fast when the secret is absent, blank, or shorter than 32 bytes —
 * the same length policy as the other gateway secrets.</p>
 *
 * @since 1.8.0
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "gateway.alerts")
public class AlertWebhookProperties {

	/**
	 * Shared bearer secret for the Alertmanager webhook receiver.
	 */
	private String webhookSecret = "";
}
