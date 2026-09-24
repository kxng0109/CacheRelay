package io.github.kxng0109.cacherelay.auth;

import java.util.Locale;
import java.util.regex.Pattern;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fail-fast gate for first-login backfill configuration: every backfill entry
 * must name a well-formed scope and supply its mode's credentials, otherwise
 * the application refuses to start instead of denying first logins at runtime.
 */
@Configuration
public class SsoBackfillConfig {

	/**
	 * Environment property carrying the Okta OAuth service-app private key (PEM).
	 */
	public static final String OKTA_PRIVATE_KEY_PROPERTY = "GATEWAY_SSO_OKTA_PRIVATE_KEY";

	/**
	 * Environment property carrying the registered JWK key id for the Okta key.
	 */
	public static final String OKTA_KEY_ID_PROPERTY = "GATEWAY_SSO_OKTA_KEY_ID";

	/**
	 * Environment property carrying the Google service-account JSON key.
	 */
	public static final String GOOGLE_SA_JSON_PROPERTY = "GATEWAY_SSO_GOOGLE_SERVICE_ACCOUNT_JSON";

	/**
	 * Environment property carrying the delegated admin subject for Directory reads.
	 */
	public static final String GOOGLE_ADMIN_SUBJECT_PROPERTY = "GATEWAY_SSO_GOOGLE_ADMIN_SUBJECT";

	private static final Pattern TENANT_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9.-]*$");

	private static final Pattern OKTA_DOMAIN_PATTERN =
			Pattern.compile("^https://[A-Za-z0-9.-]+(?::\\d+)?/?$");

	private static final Pattern WORKSPACE_DOMAIN_PATTERN =
			Pattern.compile("^[A-Za-z0-9][A-Za-z0-9.-]*\\.[A-Za-z]{2,}$");

	private static final Pattern GITHUB_ORG_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9-]*$");

	private final SsoBackfillProperties backfillProperties;

	private final Environment environment;

	/**
	 * Creates the gate.
	 *
	 * @param backfillProperties per-registration backfill entries, never {@code null}
	 * @param environment        runtime environment for credentials, never {@code null}
	 */
	public SsoBackfillConfig(SsoBackfillProperties backfillProperties, Environment environment) {
		this.backfillProperties = backfillProperties;
		this.environment = environment;
	}

	/**
	 * Validates every backfill entry, failing startup on the first
	 * misconfiguration with an operator-actionable message.
	 */
	@PostConstruct
	public void validate() {
		for (SsoBackfillProperties.RegistrationBackfill entry : backfillProperties.registrations()) {
			validateScope(entry);
			// Exhaustive by mode: when adding a BackfillMode, add its case here
			// (NONE entries throw below). SsoBackfillConfigTest.allModesHandled fails until then.
			switch (entry.mode()) {
				case NONE -> throw new IllegalStateException(
						"gateway.sso.backfill entry for registration '"
								+ entry.registrationId() + "' sets mode NONE: remove the entry"
								+ " (claims-only is the default without it)");
				case ENTRA_GRAPH -> validateEntra(entry);
				case OKTA_API -> validateOkta(entry);
				case GOOGLE_DIRECTORY -> validateGoogle(entry);
				case GITHUB_API -> validateGithub(entry);
			}
		}
	}

	private void validateScope(SsoBackfillProperties.RegistrationBackfill entry) {
		if (entry.apiScope().isBlank()) {
			throw new IllegalStateException("gateway.sso.backfill entry for registration '"
					+ entry.registrationId() + "' needs a scope (tenant id, workspace domain,"
					+ " or org login depending on the mode)");
		}
	}

	private void validateEntra(SsoBackfillProperties.RegistrationBackfill entry) {
		requireRegistration(entry, "azure");
		requireScope(entry, TENANT_PATTERN, "an Entra tenant id or domain");
		requireProperty("SSO_AZURE_CLIENT_ID", entry);
		requireProperty("SSO_AZURE_CLIENT_SECRET", entry);
		requireProperty("SSO_AZURE_TENANT", entry);
	}

	private void validateOkta(SsoBackfillProperties.RegistrationBackfill entry) {
		requireRegistration(entry, "okta");
		requireScope(entry, OKTA_DOMAIN_PATTERN, "an https Okta domain URL");
		requireProperty("SSO_OKTA_CLIENT_ID", entry);
		requireProperty(OKTA_PRIVATE_KEY_PROPERTY, entry);
		requireProperty(OKTA_KEY_ID_PROPERTY, entry);
	}

	private void validateGoogle(SsoBackfillProperties.RegistrationBackfill entry) {
		requireRegistration(entry, "google");
		requireScope(entry, WORKSPACE_DOMAIN_PATTERN, "a Workspace domain");
		String keyJson = requireProperty(GOOGLE_SA_JSON_PROPERTY, entry);
		JsonNode key;
		try {
			key = JsonMapper.builder().build().readTree(keyJson);
		} catch (Exception malformed) {
			throw new IllegalStateException(GOOGLE_SA_JSON_PROPERTY + " for registration '"
					+ entry.registrationId() + "' is not valid JSON", malformed);
		}
		if (!key.hasNonNull("client_email") || !key.hasNonNull("private_key")) {
			throw new IllegalStateException(GOOGLE_SA_JSON_PROPERTY + " for registration '"
					+ entry.registrationId() + "' needs client_email and private_key");
		}
		String admin = requireProperty(GOOGLE_ADMIN_SUBJECT_PROPERTY, entry);
		if (!admin.trim().toLowerCase(Locale.ROOT)
				.endsWith("@" + entry.apiScope().trim().toLowerCase(Locale.ROOT))) {
			throw new IllegalStateException(GOOGLE_ADMIN_SUBJECT_PROPERTY + " for registration '"
					+ entry.registrationId() + "' must belong to the '" + entry.apiScope()
					+ "' domain");
		}
	}

	private void validateGithub(SsoBackfillProperties.RegistrationBackfill entry) {
		requireRegistration(entry, "github");
		requireScope(entry, GITHUB_ORG_PATTERN, "a GitHub org login");
	}

	private void requireRegistration(SsoBackfillProperties.RegistrationBackfill entry,
			String expected) {
		if (!entry.registrationId().equals(expected)) {
			throw new IllegalStateException("gateway.sso.backfill mode " + entry.mode()
					+ " supports only the '" + expected + "' registration, not '"
					+ entry.registrationId() + "'");
		}
	}

	private void requireScope(SsoBackfillProperties.RegistrationBackfill entry, Pattern shape,
			String description) {
		if (!shape.matcher(entry.apiScope().trim()).matches()) {
			throw new IllegalStateException("gateway.sso.backfill scope '"
					+ entry.apiScope() + "' for registration '" + entry.registrationId()
					+ "' must be " + description);
		}
	}

	private String requireProperty(String name,
			SsoBackfillProperties.RegistrationBackfill entry) {
		String value = environment.getProperty(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " is required for gateway.sso.backfill"
					+ " on registration '" + entry.registrationId() + "'");
		}
		return value;
	}
}
