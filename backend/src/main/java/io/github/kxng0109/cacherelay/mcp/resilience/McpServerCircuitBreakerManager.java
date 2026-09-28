package io.github.kxng0109.cacherelay.mcp.resilience;

import io.github.kxng0109.cacherelay.mcp.config.McpGatewayProperties;
import io.github.kxng0109.cacherelay.mcp.router.McpCatalogCache;
import io.github.kxng0109.cacherelay.proxy.failover.ProviderCircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Manages per-server in-memory atomic CAS circuit breakers for upstream MCP servers. Auto-prunes degraded servers from
 * the federated catalog upon state transitions.
 */
@Slf4j
@Component
public class McpServerCircuitBreakerManager {

	private final McpGatewayProperties properties;
	private final McpCatalogCache catalogCache;
	private final Clock clock;
	private final ConcurrentMap<String, ProviderCircuitBreaker> breakers = new ConcurrentHashMap<>();

	@Autowired
	public McpServerCircuitBreakerManager(McpGatewayProperties properties, McpCatalogCache catalogCache) {
		this(properties, catalogCache, Clock.systemUTC());
	}

	public McpServerCircuitBreakerManager(McpGatewayProperties properties, McpCatalogCache catalogCache, Clock clock) {
		this.properties = properties;
		this.catalogCache = catalogCache;
		this.clock = clock;
	}

	/**
	 * Resolves or initializes the circuit breaker for a named upstream MCP server.
	 */
	public ProviderCircuitBreaker getBreaker(String serverName) {
		return breakers.computeIfAbsent(
				serverName,
				name -> new ProviderCircuitBreaker(
						name,
						clock,
						properties.getCircuitBreakerFailureThreshold(),
						properties.getCircuitBreakerCooldown()
				)
		);
	}

	/**
	 * Checks if an attempt is permitted to reach the named MCP server.
	 */
	public boolean tryAcquire(String serverName) {
		return getBreaker(serverName).tryAcquire();
	}

	/**
	 * Non-mutating availability read for display paths (catalog listing): prunes only hard-OPEN
	 * servers and never consumes a HALF_OPEN probe slot. A post-cooldown OPEN still reads OPEN
	 * until a real call probes it.
	 *
	 * @param serverName upstream MCP server name
	 * @return {@code false} only when the breaker is OPEN
	 */
	public boolean isAvailable(String serverName) {
		return getBreaker(serverName).getState() != ProviderCircuitBreaker.State.OPEN;
	}

	/**
	 * Releases a probe slot held across a path that produces no verdict (e.g. a call parked for
	 * human approval): the next probe may proceed instead of waiting out the lease. No state
	 * change when no probe is held.
	 *
	 * @param serverName upstream MCP server name
	 */
	public void abandonProbe(String serverName) {
		ProviderCircuitBreaker breaker = breakers.get(serverName);
		if (breaker != null) {
			breaker.abandonProbe();
		}
	}

	/**
	 * Records a successful execution against the named MCP server.
	 */
	public void recordSuccess(String serverName) {
		getBreaker(serverName).recordSuccess();
	}

	/**
	 * Records a failure; if the circuit trips to OPEN, invalidates the catalog cache to trigger auto-pruning.
	 */
	public void recordFailure(String serverName) {
		ProviderCircuitBreaker breaker = getBreaker(serverName);
		breaker.recordFailure();
		if (breaker.getState() != ProviderCircuitBreaker.State.CLOSED) {
			log.warn(
					"MCP server '{}' circuit breaker is no longer CLOSED. Triggering catalog cache invalidation.",
					serverName
			);
			catalogCache.invalidate();
		}
	}

	/**
	 * Force-resets the circuit breaker for the named server to CLOSED, unconditionally from any state.
	 * Delegates to the breaker's force-close reset(), NOT recordSuccess() (which is intentionally a
	 * no-op while OPEN; only reset() guarantees OPEN to CLOSED, which is what the operator expects).
	 */
	public void reset(String serverName) {
		getBreaker(serverName).reset();
		catalogCache.invalidate();
	}

	/**
	 * @return the names of servers with a materialized breaker (lazily created on first use)
	 */
	public Set<String> serverNames() {
		return Set.copyOf(breakers.keySet());
	}

	/**
	 * Names every configured upstream server, whether its breaker has been
	 * touched or not. Metrics bind against this set so dashboards cover the
	 * whole fleet from boot instead of only servers that already tripped.
	 *
	 * @return configured server names, never {@code null}
	 */
	public Set<String> configuredServerNames() {
		return Set.copyOf(properties.getServers().keySet());
	}
}
