package io.github.kxng0109.cacherelay.a2a.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the A2A (Agent-to-Agent) proxy gateway.
 *
 * <p>Slice 1 exposes a governed JSON-RPC relay in front of operator-registered upstream
 * A2A agents: virtual-key authentication, per-key agent RBAC, per-agent circuit breaking,
 * and bounded request/response bodies. Agent credentials are injected exclusively from the
 * runtime environment; no secret value is ever hardcoded or logged.</p>
 *
 * @since 1.9.0
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "gateway.a2a")
public class A2aGatewayProperties {

	/**
	 * Master toggle for the A2A proxy subsystem.
	 */
	private boolean enabled = true;

	/**
	 * Externally reachable gateway base URL, used to rewrite agent cards so clients
	 * address the gateway instead of the upstream agent.
	 */
	private String publicBaseUrl = "http://localhost:8080";

	/**
	 * Gateway version advertised on the discovery agent card.
	 */
	private String gatewayVersion = "1.0.0";

	/**
	 * A2A protocol version this proxy speaks (slice 1 targets the 0.3 wire format).
	 */
	private String defaultProtocolVersion = "0.3";

	/**
	 * Maximum accepted inbound JSON-RPC body size in bytes.
	 */
	@Min(1)
	@Max(16_777_216)
	private int maxRequestBytes = 1_048_576;

	/**
	 * Maximum accepted upstream response body size in bytes.
	 */
	@Min(1)
	@Max(16_777_216)
	private int maxResultBytes = 1_048_576;

	/**
	 * Consecutive failures before an agent's circuit breaker opens.
	 */
	@Min(1)
	@Max(100)
	private int circuitBreakerFailureThreshold = 3;

	/**
	 * How long an open agent circuit stays open before a probe is admitted.
	 */
	private Duration circuitBreakerCooldown = Duration.ofSeconds(30);

	/**
	 * Upstream connect timeout.
	 */
	private Duration clientConnectTimeout = Duration.ofSeconds(5);

	/**
	 * Upstream response timeout (covers the whole non-streaming exchange).
	 */
	private Duration clientRequestTimeout = Duration.ofSeconds(60);

	/**
	 * Registered upstream A2A agents, keyed by the name used in the proxy path.
	 */
	private Map<String, A2aAgentConfig> agents = new LinkedHashMap<>();

	/**
	 * Replaces the registered agents with a defensive copy so configuration binding
	 * never shares a mutable map with callers.
	 *
	 * @param agents agents map to install, possibly {@code null}
	 */
	public void setAgents(Map<String, A2aAgentConfig> agents) {
		this.agents = agents == null ? new LinkedHashMap<>() : new LinkedHashMap<>(agents);
	}
}
