package io.github.kxng0109.cacherelay.auth;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SsoBackfillConfig startup gate")
class SsoBackfillConfigTest {

	private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

	private StandardEnvironment environment(String... pairs) {
		StandardEnvironment environment = new StandardEnvironment();
		MockPropertySource source = new MockPropertySource("test");
		for (int index = 0; index < pairs.length; index += 2) {
			source.withProperty(pairs[index], pairs[index + 1]);
		}
		environment.getPropertySources().addFirst(source);
		return environment;
	}

	private SsoBackfillProperties.RegistrationBackfill entry(String registrationId,
			BackfillMode mode, String scope) {
		return new SsoBackfillProperties.RegistrationBackfill(registrationId, mode, scope);
	}

	private SsoBackfillProperties props(SsoBackfillProperties.RegistrationBackfill... entries) {
		return new SsoBackfillProperties(List.of(entries));
	}

	private StandardEnvironment fullHouse() {
		return environment(
				"SSO_AZURE_CLIENT_ID", "azure-id",
				"SSO_AZURE_CLIENT_SECRET", "azure-secret",
				"SSO_AZURE_TENANT", "tenant-1",
				"SSO_OKTA_CLIENT_ID", "okta-id",
				SsoBackfillConfig.OKTA_PRIVATE_KEY_PROPERTY, "pem-bytes",
				SsoBackfillConfig.OKTA_KEY_ID_PROPERTY, "key-1",
				SsoBackfillConfig.GOOGLE_SA_JSON_PROPERTY,
				"{\"client_email\":\"sa@example.iam.gserviceaccount.com\","
						+ "\"private_key\":\"key-bytes\"}",
				SsoBackfillConfig.GOOGLE_ADMIN_SUBJECT_PROPERTY, "reader@example.com");
	}

	@Test
	@DisplayName("entries bind from indexed properties with relaxed naming")
	void bindsFromPrefix() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MockPropertySource("test")
				.withProperty("gateway.sso.backfill.registrations[0].registration-id", "azure")
				.withProperty("gateway.sso.backfill.registrations[0].mode", "ENTRA_GRAPH")
				.withProperty("gateway.sso.backfill.registrations[0].api-scope", "tenant-1"));

		SsoBackfillProperties bound = Binder.get(environment)
				.bind("gateway.sso.backfill", SsoBackfillProperties.class)
				.get();

		assertThat(bound.registrations()).hasSize(1);
		assertThat(bound.forRegistration("azure")).isPresent();
		assertThat(bound.forRegistration("azure").get().mode()).isEqualTo(BackfillMode.ENTRA_GRAPH);
		assertThat(bound.forRegistration("azure").get().apiScope()).isEqualTo("tenant-1");
		assertThat(bound.forRegistration("okta")).isEmpty();
		assertThat(VALIDATOR.validate(bound)).isEmpty();
		assertThat(SsoBackfillProperties.DEFAULTS.registrations()).isEmpty();
	}

	@Test
	@DisplayName("empty properties pass silently")
	void emptyPasses() {
		new SsoBackfillConfig(SsoBackfillProperties.DEFAULTS, fullHouse()).validate();
	}

	@Test
	@DisplayName("NONE entries fail as pointless configuration")
	void noneEntryFails() {
		assertThatThrownBy(() -> new SsoBackfillConfig(
				props(entry("azure", BackfillMode.NONE, "tenant-1")), fullHouse()).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("remove the entry");
	}

	@Test
	@DisplayName("every non-none mode has a passing valid configuration")
	void allModesHandled() {
		SsoBackfillProperties bound = props(
				entry("azure", BackfillMode.ENTRA_GRAPH, "tenant-1"),
				entry("okta", BackfillMode.OKTA_API, "https://acme.okta.com"),
				entry("google", BackfillMode.GOOGLE_DIRECTORY, "example.com"),
				entry("github", BackfillMode.GITHUB_API, "acme-corp"));
		new SsoBackfillConfig(bound, fullHouse()).validate();
		Set<BackfillMode> covered = new HashSet<>();
		for (SsoBackfillProperties.RegistrationBackfill registration : bound.registrations()) {
			covered.add(registration.mode());
		}
		for (BackfillMode mode : BackfillMode.values()) {
			if (mode != BackfillMode.NONE) {
				assertThat(covered).as("gate coverage for mode %s", mode).contains(mode);
			}
		}
	}

	@Test
	@DisplayName("blank scopes fail with an actionable message")
	void blankScopeFails() {
		assertThatThrownBy(() -> new SsoBackfillConfig(
				props(entry("azure", BackfillMode.ENTRA_GRAPH, "  ")), fullHouse()).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("needs a scope");
	}

	@Test
	@DisplayName("modes reject foreign registrations")
	void modeRegistrationMismatchFails() {
		assertMismatch(entry("google", BackfillMode.ENTRA_GRAPH, "tenant-1"), "azure");
		assertMismatch(entry("azure", BackfillMode.OKTA_API, "https://acme.okta.com"), "okta");
		assertMismatch(entry("okta", BackfillMode.GOOGLE_DIRECTORY, "example.com"), "google");
		assertMismatch(entry("generic", BackfillMode.GITHUB_API, "acme-corp"), "github");
	}

	private void assertMismatch(SsoBackfillProperties.RegistrationBackfill mapping, String expected) {
		assertThatThrownBy(() -> new SsoBackfillConfig(props(mapping), fullHouse()).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("'" + expected + "'");
	}

	@Test
	@DisplayName("malformed scopes fail per mode")
	void malformedScopesFail() {
		assertScopeFails(entry("azure", BackfillMode.ENTRA_GRAPH, "not a tenant!!"), "tenant");
		assertScopeFails(entry("okta", BackfillMode.OKTA_API, "http://acme.okta.com/x"),
				"Okta domain");
		assertScopeFails(entry("google", BackfillMode.GOOGLE_DIRECTORY, "nodot"),
				"Workspace domain");
		assertScopeFails(entry("github", BackfillMode.GITHUB_API, "bad org!!"), "org login");
	}

	private void assertScopeFails(SsoBackfillProperties.RegistrationBackfill mapping, String fragment) {
		assertThatThrownBy(() -> new SsoBackfillConfig(props(mapping), fullHouse()).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(fragment);
	}

	@Test
	@DisplayName("missing credentials fail per mode")
	void missingCredentialsFail() {
		assertThatThrownBy(() -> new SsoBackfillConfig(
				props(entry("azure", BackfillMode.ENTRA_GRAPH, "tenant-1")),
				environment("SSO_AZURE_CLIENT_ID", "azure-id")).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("SSO_AZURE_CLIENT_SECRET");

		assertThatThrownBy(() -> new SsoBackfillConfig(
				props(entry("okta", BackfillMode.OKTA_API, "https://acme.okta.com")),
				environment("SSO_OKTA_CLIENT_ID", "okta-id")).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(SsoBackfillConfig.OKTA_PRIVATE_KEY_PROPERTY);

		assertThatThrownBy(() -> new SsoBackfillConfig(
				props(entry("okta", BackfillMode.OKTA_API, "https://acme.okta.com")),
				environment("SSO_OKTA_CLIENT_ID", "okta-id",
						SsoBackfillConfig.OKTA_PRIVATE_KEY_PROPERTY, "pem-bytes")).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(SsoBackfillConfig.OKTA_KEY_ID_PROPERTY);

		assertThatThrownBy(() -> new SsoBackfillConfig(
				props(entry("google", BackfillMode.GOOGLE_DIRECTORY, "example.com")),
				environment()).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(SsoBackfillConfig.GOOGLE_SA_JSON_PROPERTY);
	}

	@Test
	@DisplayName("admin subjects outside the domain fail")
	void adminSubjectDomainMismatchFails() {
		assertThatThrownBy(() -> new SsoBackfillConfig(
				props(entry("google", BackfillMode.GOOGLE_DIRECTORY, "example.com")),
				environment(SsoBackfillConfig.GOOGLE_SA_JSON_PROPERTY,
						"{\"client_email\":\"a@b\",\"private_key\":\"k\"}",
						SsoBackfillConfig.GOOGLE_ADMIN_SUBJECT_PROPERTY,
						"reader@other.com")).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("must belong to");
	}

	@Test
	@DisplayName("malformed service-account JSON fails with cause")
	void malformedServiceAccountFails() {
		assertThatThrownBy(() -> new SsoBackfillConfig(
				props(entry("google", BackfillMode.GOOGLE_DIRECTORY, "example.com")),
				environment(SsoBackfillConfig.GOOGLE_SA_JSON_PROPERTY, "{oops")).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("not valid JSON");
	}

	@Test
	@DisplayName("service-account JSON without key fields fails")
	void serviceAccountMissingFieldsFails() {
		assertThatThrownBy(() -> new SsoBackfillConfig(
				props(entry("google", BackfillMode.GOOGLE_DIRECTORY, "example.com")),
				environment(SsoBackfillConfig.GOOGLE_SA_JSON_PROPERTY,
						"{\"type\":\"service_account\"}",
						SsoBackfillConfig.GOOGLE_ADMIN_SUBJECT_PROPERTY,
						"reader@example.com")).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("client_email");
	}

	@Test
	@DisplayName("service-account JSON with email but no key fails")
	void serviceAccountMissingKeyFails() {
		assertThatThrownBy(() -> new SsoBackfillConfig(
				props(entry("google", BackfillMode.GOOGLE_DIRECTORY, "example.com")),
				environment(SsoBackfillConfig.GOOGLE_SA_JSON_PROPERTY,
						"{\"client_email\":\"sa@example.iam.gserviceaccount.com\"}",
						SsoBackfillConfig.GOOGLE_ADMIN_SUBJECT_PROPERTY,
						"reader@example.com")).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("private_key");
	}

	@Test
	@DisplayName("blank credentials fail like missing ones")
	void blankCredentialsFail() {
		assertThatThrownBy(() -> new SsoBackfillConfig(
				props(entry("azure", BackfillMode.ENTRA_GRAPH, "tenant-1")),
				environment("SSO_AZURE_CLIENT_ID", "azure-id",
						"SSO_AZURE_CLIENT_SECRET", "  ",
						"SSO_AZURE_TENANT", "tenant-1")).validate())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("SSO_AZURE_CLIENT_SECRET");
	}

	@Test
	@DisplayName("blank registration ids and scopes violate the constraints")
	void constraints() {
		assertThat(VALIDATOR.validate(
				new SsoBackfillProperties.RegistrationBackfill("", BackfillMode.ENTRA_GRAPH,
						"tenant-1"))).isNotEmpty();
		assertThat(VALIDATOR.validate(
				new SsoBackfillProperties.RegistrationBackfill("azure", BackfillMode.ENTRA_GRAPH,
						""))).isNotEmpty();
		assertThat(VALIDATOR.validate(
				new SsoBackfillProperties.RegistrationBackfill("azure", null, "tenant-1")))
				.isEmpty();
	}
}
