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

@DisplayName("SsoClaimProperties")
class SsoClaimPropertiesTest {

	private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	@DisplayName("defaults carry no mappings for legacy behavior")
	void defaults() {
		SsoClaimProperties props = SsoClaimProperties.DEFAULTS;

		assertThat(props.registrations()).isEmpty();
		assertThat(props.forRegistration("azure")).isEmpty();
		assertThat(VALIDATOR.validate(props)).isEmpty();
	}

	@Test
	@DisplayName("registrations bind from indexed properties with relaxed naming")
	void bindsFromPrefix() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MockPropertySource("test")
				.withProperty("gateway.sso.teams.registrations[0].registration-id", "azure")
				.withProperty("gateway.sso.teams.registrations[0].org-slug", "acme")
				.withProperty("gateway.sso.teams.registrations[0].groups-claim", "groups")
				.withProperty("gateway.sso.teams.registrations[0].roles-claim", "roles")
				.withProperty("gateway.sso.teams.registrations[0].tenant-claim", "tid")
				.withProperty("gateway.sso.teams.registrations[0].allowed-tenants[0]", "tid-1")
				.withProperty("gateway.sso.teams.registrations[0].group-patterns[0]", "eng-*:MEMBER")
				.withProperty("gateway.sso.teams.registrations[0].group-patterns[1]", "eng-leads:LEAD"));

		SsoClaimProperties bound = Binder.get(environment)
				.bind("gateway.sso.teams", SsoClaimProperties.class)
				.get();

		assertThat(bound.registrations()).hasSize(1);
		SsoClaimProperties.RegistrationTeams mapping = bound.forRegistration("azure").orElseThrow();
		assertThat(mapping.orgSlug()).isEqualTo("acme");
		assertThat(mapping.groupsClaim()).isEqualTo("groups");
		assertThat(mapping.tenantClaim()).isEqualTo("tid");
		assertThat(mapping.allowedTenants()).containsExactly("tid-1");
		assertThat(mapping.groupPatterns()).containsExactly("eng-*:MEMBER", "eng-leads:LEAD");
		assertThat(VALIDATOR.validate(bound)).isEmpty();
		assertThat(bound.forRegistration("okta")).isEmpty();
	}

	@Test
	@DisplayName("absent claim names fall back to groups and roles")
	void claimNamesDefault() {
		SsoClaimProperties.RegistrationTeams mapping = new SsoClaimProperties.RegistrationTeams(
				"google", "acme", null, null, null, null, null);

		assertThat(mapping.groupsClaim()).isEqualTo("groups");
		assertThat(mapping.rolesClaim()).isEqualTo("roles");
		assertThat(mapping.tenantClaim()).isEmpty();
		assertThat(mapping.allowedTenants()).isEmpty();
		assertThat(mapping.groupPatterns()).isEmpty();
		assertThat(VALIDATOR.validate(mapping)).isEmpty();
	}

	@Test
	@DisplayName("blank claim names fall back to groups and roles")
	void blankClaimNamesDefault() {
		SsoClaimProperties.RegistrationTeams mapping = new SsoClaimProperties.RegistrationTeams(
				"github", "acme", "  ", "", null, null, null);

		assertThat(mapping.groupsClaim()).isEqualTo("groups");
		assertThat(mapping.rolesClaim()).isEqualTo("roles");
		assertThat(VALIDATOR.validate(mapping)).isEmpty();
	}

	@Test
	@DisplayName("blank registration ids and org slugs violate the constraints")
	void constraints() {
		assertThat(VALIDATOR.validate(new SsoClaimProperties.RegistrationTeams(
				"", "acme", "groups", "roles", "", List.of(), List.of()))).isNotEmpty();
		assertThat(VALIDATOR.validate(new SsoClaimProperties.RegistrationTeams(
				"azure", "", "groups", "roles", "", List.of(), List.of()))).isNotEmpty();
		assertThat(VALIDATOR.validate(new SsoClaimProperties.RegistrationTeams(
				"azure", "acme", "groups", "roles", "", List.of(), List.of()))).isEmpty();
		assertThat(VALIDATOR.validate(new SsoClaimProperties(List.of(
				new SsoClaimProperties.RegistrationTeams("", "acme", "groups", "roles", "",
						List.of(), List.of()))))).isNotEmpty();
		assertThat(VALIDATOR.validate(new SsoClaimProperties(List.of(
				new SsoClaimProperties.RegistrationTeams("azure", "acme", "groups", "roles", "",
						List.of(), List.of()))))).isEmpty();
	}
}
