package io.github.kxng0109.cacherelay.auth;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for auth property defaults and null-tolerant binding.
 */
@DisplayName("AuthProperties")
class AuthPropertiesTest {

	@Test
	@DisplayName("defaults apply and explicit values survive")
	void defaultsAndExplicit() {
		AuthProperties defaults = AuthProperties.defaults();

		assertThat(defaults.accessTtl()).isEqualTo(Duration.ofMinutes(10));
		assertThat(defaults.adminAccessTtl()).isEqualTo(Duration.ofMinutes(5));
		assertThat(defaults.auditRetentionDays()).isEqualTo(180);
		assertThat(defaults.auditRetentionDaysByJurisdiction()).isEmpty();

		AuthProperties sparse = new AuthProperties(null, null, null, null, null, null, null,
				null, null, null, null, 90, null, 3, null, null, null);

		assertThat(sparse.accessTtl()).isEqualTo(Duration.ofMinutes(10));
		assertThat(sparse.auditRetentionDays()).isEqualTo(90);
		assertThat(sparse.auditRetentionDaysByJurisdiction()).isEmpty();
		assertThat(sparse.loginMaxAttempts()).isEqualTo(3);

		AuthProperties overrides = new AuthProperties(Duration.ofMinutes(7), null, null, null,
				null, null, null, "refresh", "Strict", "issuer", "secret", 200,
				Map.of("IN", 365), 5, null, null, null);

		assertThat(overrides.accessTtl()).isEqualTo(Duration.ofMinutes(7));
		assertThat(overrides.auditRetentionDaysByJurisdiction()).containsEntry("IN", 365);
	}

	@Test
	@DisplayName("invite base URL defaults blank and explicit values survive")
	void inviteBaseUrlDefaultsBlankAndExplicitSurvives() {
		assertThat(AuthProperties.defaults().inviteBaseUrl()).isEmpty();

		AuthProperties sparse = new AuthProperties(null, null, null, null, null, null, null,
				null, null, null, null, 90, null, 3, null, null, null);

		assertThat(sparse.inviteBaseUrl()).isEmpty();

		AuthProperties explicit = new AuthProperties(null, null, null, null, null, null, null,
				null, null, null, null, 90, null, 3, null, null, "https://app.example.com/");

		assertThat(explicit.inviteBaseUrl()).isEqualTo("https://app.example.com/");
	}
}
