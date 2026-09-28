package io.github.kxng0109.cacherelay.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.kxng0109.cacherelay.admin.AlertWebhookProperties;
import io.github.kxng0109.cacherelay.contracts.BootstrapKey;
import io.github.kxng0109.cacherelay.contracts.GatewayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Set;

/**
 * FS-B17 startup fail-fasts: bootstrap keys hard-required outside dev/test,
 * dev/test refuses non-loopback binds.
 */
@DisplayName("StartupGuard")
class StartupGuardTest {

	private static GatewayProperties propertiesWithKeys() {
		GatewayProperties properties = mock(GatewayProperties.class);
		when(properties.getBootstrapKeys()).thenReturn(List.of(
				new BootstrapKey("owner", "name", "gw-" + "a".repeat(32), 1, 1, Set.of(), Set.of())));
		return properties;
	}

	private static GatewayProperties propertiesWithoutKeys() {
		GatewayProperties properties = mock(GatewayProperties.class);
		when(properties.getBootstrapKeys()).thenReturn(List.of());
		return properties;
	}

	private static AlertWebhookProperties alertsWithSecret(String secret) {
		AlertWebhookProperties properties = new AlertWebhookProperties();
		properties.setWebhookSecret(secret);
		return properties;
	}

	private static final String VALID_ALERTS_SECRET = "test-only-alerts-webhook-secret-32b!";

	@Test
	@DisplayName("non-dev profile with no bootstrap keys fails fast")
	void nonDevWithoutKeysFailsFast() {
		StartupGuard guard = new StartupGuard(new MockEnvironment(), propertiesWithoutKeys(),
				alertsWithSecret(VALID_ALERTS_SECRET));

		assertThatThrownBy(guard::guard).isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("non-dev profile with keys starts cleanly")
	void nonDevWithKeysStarts() {
		StartupGuard guard = new StartupGuard(new MockEnvironment(), propertiesWithKeys(),
				alertsWithSecret(VALID_ALERTS_SECRET));

		assertThatNoException().isThrownBy(guard::guard);
	}

	@Test
	@DisplayName("dev profile with keys starts cleanly on loopback")
	void devWithKeysStarts() {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("dev");
		StartupGuard guard = new StartupGuard(environment, propertiesWithKeys(),
				alertsWithSecret(""));

		assertThatNoException().isThrownBy(guard::guard);
	}

	@Test
	@DisplayName("dev profile refuses non-loopback binds")
	void devRefusesNonLoopbackBind() {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("dev");
		environment.setProperty("GATEWAY_BIND_HOST", "0.0.0.0");
		StartupGuard guard = new StartupGuard(environment, propertiesWithKeys(),
				alertsWithSecret(""));

		assertThatThrownBy(guard::guard).isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("test profile with keys starts cleanly on loopback")
	void testProfileWithKeysStarts() {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles("test");
		StartupGuard guard = new StartupGuard(environment, propertiesWithKeys(),
				alertsWithSecret(""));

		assertThatNoException().isThrownBy(guard::guard);
	}

	@Test
	@DisplayName("dev profile accepts IPv6 and hostname loopback binds")
	void devAcceptsIpv6AndLocalhostBind() {
		for (String host : new String[]{"::1", "localhost"}) {
			MockEnvironment environment = new MockEnvironment();
			environment.setActiveProfiles("dev");
			environment.setProperty("GATEWAY_BIND_HOST", host);
			StartupGuard guard = new StartupGuard(environment, propertiesWithKeys(),
					alertsWithSecret(""));

			assertThatNoException().isThrownBy(guard::guard);
		}
	}

	@Test
	@DisplayName("non-dev profile with an empty alerts secret fails fast")
	void nonDevEmptyAlertsSecretFailsFast() {
		StartupGuard guard = new StartupGuard(new MockEnvironment(), propertiesWithKeys(),
				alertsWithSecret(""));

		assertThatThrownBy(guard::guard).isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("non-dev profile with a short alerts secret fails fast")
	void nonDevShortAlertsSecretFailsFast() {
		StartupGuard guard = new StartupGuard(new MockEnvironment(), propertiesWithKeys(),
				alertsWithSecret("too-short"));

		assertThatThrownBy(guard::guard).isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("non-dev profile with a null alerts secret fails fast")
	void nonDevNullAlertsSecretFailsFast() {
		StartupGuard guard = new StartupGuard(new MockEnvironment(), propertiesWithKeys(),
				alertsWithSecret(null));

		assertThatThrownBy(guard::guard).isInstanceOf(IllegalStateException.class);
	}
}
