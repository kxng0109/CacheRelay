package io.github.kxng0109.cacherelay.a2a.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Map;

import io.github.kxng0109.cacherelay.a2a.registry.A2aAgentRegistry;
import io.github.kxng0109.cacherelay.config.SensitiveString;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the A2A agent configuration defaults and the registry's
 * resolution semantics.
 */
@DisplayName("A2A agent configuration and registry")
class A2aAgentConfigTest {

	private static final URI BASE = URI.create("https://agents.internal/a2a");

	@Test
	@DisplayName("unset optional fields read as documented defaults")
	void defaults() {
		A2aAgentConfig config = new A2aAgentConfig("research", BASE, null, null, null, null);

		assertThat(config.protocolVersionOrDefault()).isEqualTo("0.3");
		assertThat(config.cardPathOrDefault()).isEqualTo("/.well-known/agent-card.json");
		assertThat(config.isEnabled()).isTrue();
	}

	@Test
	@DisplayName("explicit values override the defaults")
	void explicitValues() {
		A2aAgentConfig config = new A2aAgentConfig("research", BASE,
				new SensitiveString("secret"), "1.0", "/card.json", false);

		assertThat(config.protocolVersionOrDefault()).isEqualTo("1.0");
		assertThat(config.cardPathOrDefault()).isEqualTo("/card.json");
		assertThat(config.isEnabled()).isFalse();
	}

	@Test
	@DisplayName("blank optional strings fall back to the defaults")
	void blankValues() {
		A2aAgentConfig config = new A2aAgentConfig("research", BASE, null, "  ", "  ", true);

		assertThat(config.protocolVersionOrDefault()).isEqualTo("0.3");
		assertThat(config.cardPathOrDefault()).isEqualTo("/.well-known/agent-card.json");
		assertThat(config.isEnabled()).isTrue();
	}

	@Test
	@DisplayName("registry resolves valid agents and filters everything else")
	void registryResolution() {
		A2aGatewayProperties properties = new A2aGatewayProperties();
		properties.setAgents(Map.of(
				"valid", new A2aAgentConfig("valid", BASE, null, null, null, null),
				"disabled", new A2aAgentConfig("disabled", BASE, null, null, null, false),
				"broken", new A2aAgentConfig("broken", null, null, null, null, null)));
		A2aAgentRegistry registry = new A2aAgentRegistry(properties);

		assertThat(registry.resolve("valid")).isPresent();
		assertThat(registry.resolve("disabled")).isEmpty();
		assertThat(registry.resolve("broken")).isEmpty();
		assertThat(registry.resolve("ghost")).isEmpty();
		assertThat(registry.resolve(null)).isEmpty();
		assertThat(registry.resolve("  ")).isEmpty();
		assertThat(registry.enabledAgents()).containsOnlyKeys("valid");
	}

	@Test
	@DisplayName("a null agents map is tolerated")
	void nullAgentsMap() {
		A2aGatewayProperties properties = new A2aGatewayProperties();
		properties.setAgents(null);
		A2aAgentRegistry registry = new A2aAgentRegistry(properties);

		assertThat(properties.getAgents()).isEmpty();
		assertThat(registry.enabledAgents()).isEmpty();
	}
}
