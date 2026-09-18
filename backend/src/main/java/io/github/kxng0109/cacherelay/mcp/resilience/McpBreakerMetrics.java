package io.github.kxng0109.cacherelay.mcp.resilience;

import io.github.kxng0109.cacherelay.proxy.failover.CircuitBreaker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * Exposes upstream MCP server circuit breaker states as Micrometer gauges.
 *
 * <p>One {@code cacherelay.mcp.circuit.breaker.state} gauge per materialized server (state
 * encoded 0=CLOSED, 1=OPEN, 2=HALF_OPEN) plus one {@code cacherelay.mcp.circuit.breaker.failures}
 * gauge for the consecutive failure count. Scraped by Prometheus for the MCP health view.</p>
 */
@Component
public class McpBreakerMetrics implements MeterBinder {

	private final McpServerCircuitBreakerManager manager;

	/**
	 * Creates the metrics binder for the given manager.
	 *
	 * @param manager the MCP breaker manager whose per server states are exposed
	 */
	public McpBreakerMetrics(McpServerCircuitBreakerManager manager) {
		this.manager = manager;
	}

	/**
	 * Registers state and failure gauges per materialized server.
	 *
	 * @param registry the registry the gauges are registered with
	 */
	@Override
	public void bindTo(MeterRegistry registry) {
		for (String name : manager.serverNames()) {
			Gauge.builder("cacherelay.mcp.circuit.breaker.state", manager,
							m -> stateCode(m.getBreaker(name).getState()))
					.tag("server", name)
					.description("MCP server circuit state encoded as 0=CLOSED, 1=OPEN, 2=HALF_OPEN")
					.baseUnit("state")
					.register(registry);
			Gauge.builder("cacherelay.mcp.circuit.breaker.failures", manager,
							m -> m.getBreaker(name).getFailureCount())
					.tag("server", name)
					.description("MCP server consecutive failures recorded while CLOSED")
					.baseUnit("failures")
					.register(registry);
		}
	}

	private static int stateCode(CircuitBreaker.State state) {
		return switch (state) {
			case CLOSED -> 0;
			case OPEN -> 1;
			case HALF_OPEN -> 2;
		};
	}
}
