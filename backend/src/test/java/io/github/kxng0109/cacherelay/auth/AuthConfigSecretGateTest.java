package io.github.kxng0109.cacherelay.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AuthConfig JWT secret startup gate")
class AuthConfigSecretGateTest {

	@Test
	@DisplayName("missing or short secret fails outside dev/test")
	void failsOutsideRelaxedProfiles() {
		assertThatThrownBy(() -> AuthConfig.resolveSecret(null, List.of()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("GATEWAY_AUTH_JWT_SECRET");
		assertThatThrownBy(() -> AuthConfig.resolveSecret("  ", List.of("prod")))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> AuthConfig.resolveSecret("short", List.of("prod", "metrics")))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("blank secret mints ephemeral bytes under dev/test")
	void ephemeralUnderRelaxedProfiles() {
		assertThat(AuthConfig.resolveSecret(null, List.of("dev"))).hasSize(32);
		assertThat(AuthConfig.resolveSecret("", List.of("test"))).hasSize(32);
		assertThat(AuthConfig.resolveSecret("short", List.of("dev", "prod"))).hasSize(32);
	}

	@Test
	@DisplayName("stable secret passes through in any profile")
	void stableSecretPasses() {
		assertThat(AuthConfig.resolveSecret("x".repeat(32), List.of("prod")))
				.isEqualTo("x".repeat(32).getBytes(StandardCharsets.UTF_8));
	}

	@Test
	@DisplayName("AuthConfig bean fails fast with a blank secret outside dev/test")
	void beanFailsFast() {
		Environment prodEnv = mock(Environment.class);
		when(prodEnv.getActiveProfiles()).thenReturn(new String[]{"prod"});
		assertThatThrownBy(() -> new AuthConfig(AuthProperties.defaults(), prodEnv))
				.isInstanceOf(IllegalStateException.class);

		Environment devEnv = mock(Environment.class);
		when(devEnv.getActiveProfiles()).thenReturn(new String[]{"dev"});
		assertThat(new AuthConfig(AuthProperties.defaults(), devEnv)).isNotNull();
	}

	@Test
	@DisplayName("AuthConfig bean boots without warning on a stable secret in prod")
	void beanAcceptsStableSecret() {
		AuthProperties configured = new AuthProperties(
				null, null, null, null, null, null, null, null, null, null,
				"test-only-jwt-secret-32-bytes-min!!",
				180, new HashMap<>(), 5, null, null, null);
		Environment prodEnv = mock(Environment.class);
		when(prodEnv.getActiveProfiles()).thenReturn(new String[]{"prod"});

		assertThat(new AuthConfig(configured, prodEnv)).isNotNull();
	}
}
