package io.github.kxng0109.cacherelay.auth;

import java.util.List;
import java.util.Optional;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Webhook receiver secrets bound from
 * {@code gateway.sso.webhooks.registrations[]}, one entry per Spring
 * registration id that accepts IdP push events.
 *
 * <p>Receivers only invalidate watermarks (forcing a prompt sweep re-check);
 * they never revoke directly. Each IdP authenticates differently: GitHub via
 * HMAC-SHA256, Okta via a header secret, Entra via a client-state secret,
 * Google via a channel-token echo. Registrations without an entry accept no
 * push events.</p>
 *
 * @param registrations per-registration webhook secrets, never {@code null}
 */
@ConfigurationProperties("gateway.sso.webhooks")
@Validated
public record SsoWebhookProperties(
		@Valid List<RegistrationWebhook> registrations
) {

	/**
	 * The documented defaults: no receivers accept push events.
	 */
	public static final SsoWebhookProperties DEFAULTS = new SsoWebhookProperties(List.of());

	/**
	 * Canonical constructor normalizing absent lists to empty.
	 */
	public SsoWebhookProperties {
		registrations = registrations == null ? List.of() : List.copyOf(registrations);
	}

	/**
	 * Finds the webhook entry for one Spring registration id.
	 *
	 * @param registrationId Spring registration id, never {@code null}
	 * @return the entry when configured
	 */
	public Optional<RegistrationWebhook> forRegistration(String registrationId) {
		for (RegistrationWebhook entry : registrations) {
			if (entry.registrationId().equals(registrationId)) {
				return Optional.of(entry);
			}
		}
		return Optional.empty();
	}

	/**
	 * Webhook secrets for one Spring registration id. Only the secret matching
	 * the registration's IdP is used; the rest stay blank.
	 *
	 * @param registrationId    Spring registration id, never blank
	 * @param githubSecret      GitHub webhook secret (HMAC-SHA256)
	 * @param oktaSecret        Okta event-hook header secret
	 * @param entraClientState  Entra subscription client state
	 * @param googleChannelToken Google watch channel token
	 */
	public record RegistrationWebhook(
			@NotBlank String registrationId,
			@DefaultValue("") String githubSecret,
			@DefaultValue("") String oktaSecret,
			@DefaultValue("") String entraClientState,
			@DefaultValue("") String googleChannelToken
	) {

		/**
		 * Canonical constructor normalizing absent secrets to blank.
		 */
		public RegistrationWebhook {
			githubSecret = githubSecret == null ? "" : githubSecret;
			oktaSecret = oktaSecret == null ? "" : oktaSecret;
			entraClientState = entraClientState == null ? "" : entraClientState;
			googleChannelToken = googleChannelToken == null ? "" : googleChannelToken;
		}
	}
}
