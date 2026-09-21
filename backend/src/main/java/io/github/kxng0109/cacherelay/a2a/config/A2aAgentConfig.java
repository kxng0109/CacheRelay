package io.github.kxng0109.cacherelay.a2a.config;

import java.net.URI;

import io.github.kxng0109.cacherelay.config.SensitiveString;

import org.jspecify.annotations.Nullable;

/**
 * Configuration of one registered upstream A2A agent, bound from
 * {@code gateway.a2a.agents.<name>}.
 *
 * @param name            agent identifier used in the proxy path
 * @param baseUrl         JSON-RPC endpoint of the upstream agent
 * @param apiKey          upstream credential (Bearer), masked and optional
 * @param protocolVersion pinned A2A protocol version for this agent (null = gateway default)
 * @param cardPath        agent card path relative to the agent origin
 *                        (null = {@code /.well-known/agent-card.json})
 * @param enabled         whether the agent is routable (null = true)
 */
public record A2aAgentConfig(
		String name,
		URI baseUrl,
		@Nullable SensitiveString apiKey,
		@Nullable String protocolVersion,
		@Nullable String cardPath,
		@Nullable Boolean enabled
) {

	/**
	 * @return the pinned protocol version, or the 0.3 default when unset
	 */
	public String protocolVersionOrDefault() {
		return protocolVersion == null || protocolVersion.isBlank() ? "0.3" : protocolVersion;
	}

	/**
	 * @return the configured card path, or the A2A well-known path when unset
	 */
	public String cardPathOrDefault() {
		return cardPath == null || cardPath.isBlank() ? "/.well-known/agent-card.json" : cardPath;
	}

	/**
	 * @return whether this agent is routable; unset reads as enabled
	 */
	public boolean isEnabled() {
		return enabled == null || enabled;
	}
}
