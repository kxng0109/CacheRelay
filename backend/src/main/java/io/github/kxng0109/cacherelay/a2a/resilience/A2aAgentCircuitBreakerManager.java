package io.github.kxng0109.cacherelay.a2a.resilience;

import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.github.kxng0109.cacherelay.a2a.config.A2aGatewayProperties;
import io.github.kxng0109.cacherelay.proxy.failover.ProviderCircuitBreaker;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Manages per-agent in-memory atomic CAS circuit breakers for upstream A2A agents.
 *
 * <p>Mirrors the MCP server breaker manager: each agent gets an independent breaker with
 * the A2A-configured failure threshold and cooldown. State is intentionally local
 * (per-instance) for this slice; upstream reachability is agent-specific and probes are
 * cheap.</p>
 */
@Component
public class A2aAgentCircuitBreakerManager {

	private final A2aGatewayProperties properties;
	private final Clock clock;
	private final ConcurrentMap<String, ProviderCircuitBreaker> breakers = new ConcurrentHashMap<>();

	/**
	 * @param properties A2A gateway configuration
	 */
	@Autowired
	public A2aAgentCircuitBreakerManager(A2aGatewayProperties properties) {
		this(properties, Clock.systemUTC());
	}

	/**
	 * @param properties A2A gateway configuration
	 * @param clock      time source for cooldown accounting
	 */
	public A2aAgentCircuitBreakerManager(A2aGatewayProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Resolves or initializes the circuit breaker for a named agent.
	 *
	 * @param agentName agent identifier
	 * @return the breaker bound to this agent
	 */
	public ProviderCircuitBreaker getBreaker(String agentName) {
		return breakers.computeIfAbsent(
				agentName,
				name -> new ProviderCircuitBreaker(
						name,
						clock,
						properties.getCircuitBreakerFailureThreshold(),
						properties.getCircuitBreakerCooldown()
				)
		);
	}

	/**
	 * Checks whether an attempt to reach the named agent is permitted.
	 *
	 * @param agentName agent identifier
	 * @return true when the call may proceed
	 */
	public boolean tryAcquire(String agentName) {
		return getBreaker(agentName).tryAcquire();
	}

	/**
	 * Records a successful agent call.
	 *
	 * @param agentName agent identifier
	 */
	public void recordSuccess(String agentName) {
		getBreaker(agentName).recordSuccess();
	}

	/**
	 * Records a failed agent call (may open the circuit at the threshold).
	 *
	 * @param agentName agent identifier
	 */
	public void recordFailure(String agentName) {
		getBreaker(agentName).recordFailure();
	}

	/**
	 * Force-resets the named agent's breaker to CLOSED.
	 *
	 * @param agentName agent identifier
	 */
	public void reset(String agentName) {
		getBreaker(agentName).reset();
	}

	/**
	 * @return names of agents with a materialized breaker
	 */
	public Set<String> agentNames() {
		return Set.copyOf(breakers.keySet());
	}
}
