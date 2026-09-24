package io.github.kxng0109.cacherelay.auth;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/**
 * Fail-fast gate for webhook receiver secrets: every listed registration
 * must supply its IdP's secret, otherwise the application refuses to start
 * instead of silently accepting no push events (or worse, accepting
 * unauthenticated ones).
 */
@Configuration
public class SsoWebhookConfig {

	private final SsoWebhookProperties webhookProperties;

	/**
	 * Creates the gate.
	 *
	 * @param webhookProperties per-registration webhook secrets, never {@code null}
	 */
	public SsoWebhookConfig(SsoWebhookProperties webhookProperties) {
		this.webhookProperties = webhookProperties;
	}

	/**
	 * Validates every webhook entry, failing startup on the first
	 * misconfiguration with an operator-actionable message.
	 */
	@PostConstruct
	public void validate() {
		for (SsoWebhookProperties.RegistrationWebhook entry
				: webhookProperties.registrations()) {
			switch (entry.registrationId()) {
				case "github" -> requireSecret(entry, entry.githubSecret(), "github-secret");
				case "okta" -> requireSecret(entry, entry.oktaSecret(), "okta-secret");
				case "azure", "azure-b2c" ->
						requireSecret(entry, entry.entraClientState(), "entra-client-state");
				case "google" -> requireSecret(entry, entry.googleChannelToken(),
						"google-channel-token");
				default -> throw new IllegalStateException("gateway.sso.webhooks entry for"
						+ " registration '" + entry.registrationId() + "' names an unsupported"
						+ " IdP (expected github, okta, azure, azure-b2c, or google)");
			}
		}
	}

	private void requireSecret(SsoWebhookProperties.RegistrationWebhook entry, String secret,
			String property) {
		if (secret.isBlank() || secret.trim().length() < 32) {
			throw new IllegalStateException("gateway.sso.webhooks entry for registration '"
					+ entry.registrationId() + "' needs " + property + " of at least 32 characters");
		}
	}
}
