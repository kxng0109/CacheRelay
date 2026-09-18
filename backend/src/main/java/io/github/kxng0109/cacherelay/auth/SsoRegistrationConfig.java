package io.github.kxng0109.cacherelay.auth;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.env.Environment;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * Builds the SSO provider set programmatically: every IdP is supported day one, and a
 * provider activates the moment its {@code SSO_*} client id (and secret) is supplied.
 * Blank stays inert — Boot rejects blank client-ids in static configuration, so static
 * blocks are not used. No discovery HTTP happens at startup; all endpoints are explicit.
 */
@Configuration
public class SsoRegistrationConfig {

	private final Environment environment;

	/**
	 * Creates the config.
	 *
	 * @param environment runtime environment (SSO_* credentials)
	 */
	public SsoRegistrationConfig(Environment environment) {
		this.environment = environment;
	}

	/**
	 * Creates the registration repository containing every configured provider. Only
	 * present when at least one IdP is configured; otherwise SSO stays inert and the
	 * OAuth2 endpoints fall through to default-deny.
	 *
	 * @return repository of configured providers
	 */
	@Bean
	@ConditionalOnExpression("'${SSO_GOOGLE_CLIENT_ID:}${SSO_GITHUB_CLIENT_ID:}${SSO_AZURE_CLIENT_ID:}${SSO_AZURE_B2C_CLIENT_ID:}${SSO_OKTA_CLIENT_ID:}${SSO_GENERIC_CLIENT_ID:}'.length() > 0")
	ClientRegistrationRepository ssoClientRegistrationRepository() {
		List<ClientRegistration> registrations = new ArrayList<>();
		google(registrations);
		github(registrations);
		azure(registrations);
		azureB2c(registrations);
		okta(registrations);
		generic(registrations);
		return new InMemoryClientRegistrationRepository(registrations);
	}

	private void google(List<ClientRegistration> registrations) {
		String id = environment.getProperty("SSO_GOOGLE_CLIENT_ID", "");
		String secret = environment.getProperty("SSO_GOOGLE_CLIENT_SECRET", "");
		if (id.isBlank()) {
			return;
		}
		registrations.add(CommonOAuth2Provider.GOOGLE.getBuilder("google")
				.clientId(id)
				.clientSecret(secret)
				.scope("openid", "profile", "email")
				.build());
	}

	private void github(List<ClientRegistration> registrations) {
		String id = environment.getProperty("SSO_GITHUB_CLIENT_ID", "");
		String secret = environment.getProperty("SSO_GITHUB_CLIENT_SECRET", "");
		if (id.isBlank()) {
			return;
		}
		registrations.add(CommonOAuth2Provider.GITHUB.getBuilder("github")
				.clientId(id)
				.clientSecret(secret)
				.scope("read:user", "user:email")
				.build());
	}

	private void azure(List<ClientRegistration> registrations) {
		String id = environment.getProperty("SSO_AZURE_CLIENT_ID", "");
		String secret = environment.getProperty("SSO_AZURE_CLIENT_SECRET", "");
		if (id.isBlank()) {
			return;
		}
		String tenant = environment.getProperty("SSO_AZURE_TENANT", "common");
		String base = "https://login.microsoftonline.com/" + tenant;
		registrations.add(baseRegistration("azure", "Microsoft", id, secret,
				base + "/oauth2/v2.0/authorize",
				base + "/oauth2/v2.0/token",
				"https://graph.microsoft.com/oidc/userinfo",
				base + "/discovery/v2.0/keys",
				"openid", "profile", "email"));
	}

	private void azureB2c(List<ClientRegistration> registrations) {
		String id = environment.getProperty("SSO_AZURE_B2C_CLIENT_ID", "");
		String secret = environment.getProperty("SSO_AZURE_B2C_CLIENT_SECRET", "");
		if (id.isBlank()) {
			return;
		}
		String tenant = environment.getProperty("SSO_AZURE_B2C_TENANT", "tenant");
		String policy = environment.getProperty("SSO_AZURE_B2C_POLICY", "policy");
		String base = "https://" + tenant + ".b2clogin.com/" + tenant + ".onmicrosoft.com/"
				+ policy;
		registrations.add(baseRegistration("azure-b2c", "Microsoft B2C", id, secret,
				base + "/oauth2/v2.0/authorize",
				base + "/oauth2/v2.0/token",
				base + "/openid/v2.0/userinfo",
				base + "/discovery/v2.0/keys",
				"openid", "profile", "email"));
	}

	private void okta(List<ClientRegistration> registrations) {
		String id = environment.getProperty("SSO_OKTA_CLIENT_ID", "");
		String secret = environment.getProperty("SSO_OKTA_CLIENT_SECRET", "");
		if (id.isBlank()) {
			return;
		}
		String issuer = environment.getProperty("SSO_OKTA_ISSUER",
				"https://example.okta.com/oauth2/default");
		registrations.add(baseRegistration("okta", "Okta", id, secret,
				issuer + "/v1/authorize",
				issuer + "/v1/token",
				issuer + "/v1/userinfo",
				issuer + "/v1/keys",
				"openid", "profile", "email"));
	}

	private void generic(List<ClientRegistration> registrations) {
		String id = environment.getProperty("SSO_GENERIC_CLIENT_ID", "");
		String secret = environment.getProperty("SSO_GENERIC_CLIENT_SECRET", "");
		String authorization = environment.getProperty("SSO_GENERIC_AUTHORIZATION_URI", "");
		String token = environment.getProperty("SSO_GENERIC_TOKEN_URI", "");
		String userInfo = environment.getProperty("SSO_GENERIC_USERINFO_URI", "");
		String jwks = environment.getProperty("SSO_GENERIC_JWKSET_URI", "");
		if (id.isBlank() || authorization.isBlank() || token.isBlank() || userInfo.isBlank()
				|| jwks.isBlank()) {
			return;
		}
		registrations.add(baseRegistration("generic", "SSO", id, secret, authorization, token,
				userInfo, jwks, "openid", "profile", "email"));
	}

	private ClientRegistration baseRegistration(String registrationId, String clientName,
			String clientId, String clientSecret, String authorizationUri, String tokenUri,
			String userInfoUri, String jwkSetUri, String... scopes) {
		return ClientRegistration.withRegistrationId(registrationId)
				.clientId(clientId)
				.clientSecret(clientSecret)
				.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
				.clientSettings(ClientRegistration.ClientSettings.builder()
						.requireProofKey(true).build())
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
				.redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
				.scope(scopes)
				.authorizationUri(authorizationUri)
				.tokenUri(tokenUri)
				.userInfoUri(userInfoUri)
				.userNameAttributeName("sub")
				.jwkSetUri(jwkSetUri)
				.clientName(clientName)
				.build();
	}
}
