package io.github.kxng0109.cacherelay.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for programmatic SSO registration: configured providers build, blank stays out.
 */
@DisplayName("SsoRegistrationConfig")
class SsoRegistrationConfigTest {

	@Test
	@DisplayName("empty environment fails fast instead of registering nothing")
	void emptyYieldsEmpty() {
		SsoRegistrationConfig config = new SsoRegistrationConfig(new MockEnvironment());

		assertThatThrownBy(config::ssoClientRegistrationRepository)
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("every IdP activates from its env credentials")
	void allProvidersActivate() {
		MockEnvironment environment = new MockEnvironment()
				.withProperty("SSO_GOOGLE_CLIENT_ID", "google-id")
				.withProperty("SSO_GOOGLE_CLIENT_SECRET", "google-secret")
				.withProperty("SSO_GITHUB_CLIENT_ID", "github-id")
				.withProperty("SSO_GITHUB_CLIENT_SECRET", "github-secret")
				.withProperty("SSO_AZURE_CLIENT_ID", "azure-id")
				.withProperty("SSO_AZURE_CLIENT_SECRET", "azure-secret")
				.withProperty("SSO_AZURE_TENANT", "tenant-1")
				.withProperty("SSO_AZURE_B2C_CLIENT_ID", "b2c-id")
				.withProperty("SSO_AZURE_B2C_CLIENT_SECRET", "b2c-secret")
				.withProperty("SSO_AZURE_B2C_TENANT", "b2ctenant")
				.withProperty("SSO_AZURE_B2C_POLICY", "policy-1")
				.withProperty("SSO_OKTA_CLIENT_ID", "okta-id")
				.withProperty("SSO_OKTA_CLIENT_SECRET", "okta-secret")
				.withProperty("SSO_OKTA_ISSUER", "https://example.okta.com/oauth2/default")
				.withProperty("SSO_GENERIC_CLIENT_ID", "generic-id")
				.withProperty("SSO_GENERIC_CLIENT_SECRET", "generic-secret")
				.withProperty("SSO_GENERIC_AUTHORIZATION_URI", "https://sso/auth")
				.withProperty("SSO_GENERIC_TOKEN_URI", "https://sso/token")
				.withProperty("SSO_GENERIC_USERINFO_URI", "https://sso/me")
				.withProperty("SSO_GENERIC_JWKSET_URI", "https://sso/keys");
		SsoRegistrationConfig config = new SsoRegistrationConfig(environment);

		var repository = config.ssoClientRegistrationRepository();

		assertThat(repository.findByRegistrationId("google")).isNotNull();
		assertThat(repository.findByRegistrationId("github")).isNotNull();
		assertThat(repository.findByRegistrationId("azure")).isNotNull();
		assertThat(repository.findByRegistrationId("azure").getClientId()).isEqualTo("azure-id");
		assertThat(repository.findByRegistrationId("azure-b2c")).isNotNull();
		assertThat(repository.findByRegistrationId("okta")).isNotNull();
		assertThat(repository.findByRegistrationId("generic")).isNotNull();
		assertThat(repository.findByRegistrationId("generic").getScopes())
				.contains("openid", "profile", "email");
	}

	@Test
	@DisplayName("partial generic URIs fail fast until complete")
	void partialGenericUris() {
		MockEnvironment missingJwks = new MockEnvironment()
				.withProperty("SSO_GENERIC_CLIENT_ID", "generic-id")
				.withProperty("SSO_GENERIC_CLIENT_SECRET", "generic-secret")
				.withProperty("SSO_GENERIC_AUTHORIZATION_URI", "https://sso/auth")
				.withProperty("SSO_GENERIC_TOKEN_URI", "https://sso/token")
				.withProperty("SSO_GENERIC_USERINFO_URI", "https://sso/me");
		assertThatThrownBy(
				() -> new SsoRegistrationConfig(missingJwks).ssoClientRegistrationRepository())
				.isInstanceOf(IllegalArgumentException.class);

		MockEnvironment idOnly = new MockEnvironment()
				.withProperty("SSO_GENERIC_CLIENT_ID", "generic-id");
		assertThatThrownBy(
				() -> new SsoRegistrationConfig(idOnly).ssoClientRegistrationRepository())
				.isInstanceOf(IllegalArgumentException.class);
	}
}
