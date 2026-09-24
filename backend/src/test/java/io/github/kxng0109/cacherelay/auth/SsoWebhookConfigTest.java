package io.github.kxng0109.cacherelay.auth;

import java.util.List;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SsoWebhookConfig startup gate")
class SsoWebhookConfigTest {

	private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

	private SsoWebhookProperties.RegistrationWebhook entry(String registrationId,
			String githubSecret, String oktaSecret, String entraClientState,
			String googleChannelToken) {
		return new SsoWebhookProperties.RegistrationWebhook(registrationId, githubSecret,
				oktaSecret, entraClientState, googleChannelToken);
	}

	private SsoWebhookProperties props(SsoWebhookProperties.RegistrationWebhook... entries) {
		return new SsoWebhookProperties(List.of(entries));
	}

	@Test
	@DisplayName("entries bind from indexed properties with relaxed naming")
	void bindsFromPrefix() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MockPropertySource("test")
				.withProperty("gateway.sso.webhooks.registrations[0].registration-id", "github")
				.withProperty("gateway.sso.webhooks.registrations[0].github-secret", "s3"));

		SsoWebhookProperties bound = Binder.get(environment)
				.bind("gateway.sso.webhooks", SsoWebhookProperties.class)
				.get();

		assertThat(bound.registrations()).hasSize(1);
		assertThat(bound.forRegistration("github")).isPresent();
		assertThat(bound.forRegistration("github").get().githubSecret()).isEqualTo("s3");
		assertThat(bound.forRegistration("okta")).isEmpty();
		assertThat(VALIDATOR.validate(bound)).isEmpty();
		assertThat(SsoWebhookProperties.DEFAULTS.registrations()).isEmpty();
	}

	@Test
	@DisplayName("empty properties pass silently")
	void emptyPasses() {
		new SsoWebhookConfig(SsoWebhookProperties.DEFAULTS).validate();
	}

	@Test
	@DisplayName("blank secrets fail per registration")
	void blankSecretsFail() {
		assertThatThrownBy(() -> new SsoWebhookConfig(props(
				entry("github", "  ", "", "", ""))).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("github-secret");
		assertThatThrownBy(() -> new SsoWebhookConfig(props(
				entry("okta", "", "", "", ""))).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("okta-secret");
		assertThatThrownBy(() -> new SsoWebhookConfig(props(
				entry("azure", "", "", " ", ""))).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("entra-client-state");
		assertThatThrownBy(() -> new SsoWebhookConfig(props(
				entry("azure-b2c", "", "", "", ""))).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("entra-client-state");
		assertThatThrownBy(() -> new SsoWebhookConfig(props(
				entry("google", "", "", "", ""))).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("google-channel-token");
	}

	@Test
	@DisplayName("null secrets normalize to blank and fail")
	void nullSecretsNormalize() {
		SsoWebhookProperties.RegistrationWebhook entry = new SsoWebhookProperties
				.RegistrationWebhook("github", null, null, null, null);

		assertThat(entry.githubSecret()).isEmpty();
		assertThatThrownBy(() -> new SsoWebhookConfig(props(entry)).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("github-secret");
	}

	@Test
	@DisplayName("short secrets fail like missing ones")
	void shortSecretsFail() {
		assertThatThrownBy(() -> new SsoWebhookConfig(props(
				entry("github", "too-short", "", "", ""))).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("at least 32 characters");
	}

	@Test
	@DisplayName("unknown registrations fail")
	void unknownRegistrationFails() {
		assertThatThrownBy(() -> new SsoWebhookConfig(props(
				entry("generic", "", "", "", "x"))).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("'generic'");
	}

	@Test
	@DisplayName("every supported registration has a passing configuration")
	void allModesHandled() {
		SsoWebhookProperties bound = props(
				entry("github", "0123456789abcdef0123456789abcdef", "", "", ""),
				entry("okta", "", "0123456789abcdef0123456789abcdef", "", ""),
				entry("azure", "", "", "0123456789abcdef0123456789abcdef", ""),
				entry("azure-b2c", "", "", "0123456789abcdef0123456789abcdef", ""),
				entry("google", "", "", "", "0123456789abcdef0123456789abcdef"));
		new SsoWebhookConfig(bound).validate();
		assertThat(bound.registrations()).hasSize(5);
	}

	@Test
	@DisplayName("blank registration ids violate the constraints")
	void constraints() {
		assertThat(VALIDATOR.validate(entry("", "s3", "", "", ""))).isNotEmpty();
		assertThat(VALIDATOR.validate(entry("github", "s3", "", "", ""))).isEmpty();
	}
}
