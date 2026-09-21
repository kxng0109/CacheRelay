package io.github.kxng0109.cacherelay.a2a.registry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import io.github.kxng0109.cacherelay.a2a.config.A2aAgentConfig;
import io.github.kxng0109.cacherelay.a2a.config.A2aGatewayProperties;

import org.springframework.stereotype.Component;

/**
 * Resolves operator-registered upstream A2A agents by name.
 *
 * <p>Lookup is a plain in-memory map read: unknown, disabled, or misconfigured
 * (missing base URL) agents resolve as absent, which callers surface as a generic
 * policy denial so the registry can never be enumerated through the API.</p>
 */
@Component
public class A2aAgentRegistry {

	private final A2aGatewayProperties properties;

	/**
	 * @param properties A2A gateway configuration
	 */
	public A2aAgentRegistry(A2aGatewayProperties properties) {
		this.properties = properties;
	}

	/**
	 * Resolves a routable agent by name.
	 *
	 * @param agentName agent identifier from the proxy path
	 * @return the agent configuration when known, enabled, and complete
	 */
	public Optional<A2aAgentConfig> resolve(String agentName) {
		if (agentName == null || agentName.isBlank()) {
			return Optional.empty();
		}
		A2aAgentConfig agent = properties.getAgents().get(agentName);
		if (agent == null || !agent.isEnabled() || agent.baseUrl() == null) {
			return Optional.empty();
		}
		return Optional.of(agent);
	}

	/**
	 * @return the routable agents keyed by name, in configuration order
	 */
	public Map<String, A2aAgentConfig> enabledAgents() {
		Map<String, A2aAgentConfig> enabled = new LinkedHashMap<>();
		properties.getAgents().forEach((name, agent) -> {
			if (agent != null && agent.isEnabled() && agent.baseUrl() != null) {
				enabled.put(name, agent);
			}
		});
		return Map.copyOf(enabled);
	}
}
