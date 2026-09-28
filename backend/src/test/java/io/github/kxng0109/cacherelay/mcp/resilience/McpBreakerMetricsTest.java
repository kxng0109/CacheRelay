package io.github.kxng0109.cacherelay.mcp.resilience;

import io.github.kxng0109.cacherelay.mcp.config.McpGatewayProperties;
import io.github.kxng0109.cacherelay.mcp.contracts.McpServerConfig;
import io.github.kxng0109.cacherelay.mcp.router.McpCatalogCache;
import io.github.kxng0109.cacherelay.proxy.failover.ProviderCircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for MCP breaker gauges: state codes and failure counts per server.
 */
@DisplayName("McpBreakerMetrics")
class McpBreakerMetricsTest {

	@Test
	@DisplayName("MCP-B22: gauges pre-materialize for every configured server")
	void bindsGaugesForConfiguredServers() {
		McpServerCircuitBreakerManager manager = manager("ok", "down");
		SimpleMeterRegistry registry = new SimpleMeterRegistry();

		new McpBreakerMetrics(manager).bindTo(registry);

		assertThat(registry.get("cacherelay.mcp.circuit.breaker.state").tag("server", "ok")
				.gauge().value()).isZero();
		assertThat(registry.get("cacherelay.mcp.circuit.breaker.state").tag("server", "down")
				.gauge().value()).isZero();
		assertThat(registry.get("cacherelay.mcp.circuit.breaker.failures").tag("server", "ok")
				.gauge().value()).isZero();
		assertThat(registry.get("cacherelay.mcp.circuit.breaker.failures").tag("server", "down")
				.gauge().value()).isZero();
	}

	@Test
	@DisplayName("tripped servers gauge OPEN with their failure count")
	void trippedServerGauges() {
		McpServerCircuitBreakerManager manager = manager("ok", "down");
		for (int i = 0; i < 3; i++) {
			manager.getBreaker("down").recordFailure();
		}
		SimpleMeterRegistry registry = new SimpleMeterRegistry();

		new McpBreakerMetrics(manager).bindTo(registry);

		assertThat(registry.get("cacherelay.mcp.circuit.breaker.state").tag("server", "down")
				.gauge().value()).isEqualTo(1.0);
		assertThat(registry.get("cacherelay.mcp.circuit.breaker.failures").tag("server", "down")
				.gauge().value()).isEqualTo(3.0);
	}

	@Test
	@DisplayName("MCP-B22: gauges track live breaker transitions after bind, not snapshots")
	void gaugesTrackLiveTransitions() {
		McpServerCircuitBreakerManager manager = manager("ok", "down");
		SimpleMeterRegistry registry = new SimpleMeterRegistry();

		new McpBreakerMetrics(manager).bindTo(registry);
		assertThat(registry.get("cacherelay.mcp.circuit.breaker.state").tag("server", "ok")
				.gauge().value()).isZero();

		for (int i = 0; i < 3; i++) {
			manager.getBreaker("ok").recordFailure();
		}

		assertThat(registry.get("cacherelay.mcp.circuit.breaker.state").tag("server", "ok")
				.gauge().value()).isEqualTo(1.0);
		assertThat(registry.get("cacherelay.mcp.circuit.breaker.failures").tag("server", "ok")
				.gauge().value()).isEqualTo(3.0);
	}

	private static McpServerCircuitBreakerManager manager(String... names) {
		McpGatewayProperties properties = new McpGatewayProperties();
		Map<String, McpServerConfig> servers = new LinkedHashMap<>();
		for (String name : names) {
			servers.put(name, new McpServerConfig(
					name, null, URI.create("https://example.invalid/" + name), null,
					Duration.ofSeconds(5), Duration.ofSeconds(30),
					Set.of(), Set.of(), Set.of(), 0, true));
		}
		properties.setServers(servers);
		return new McpServerCircuitBreakerManager(properties, mock(McpCatalogCache.class));
	}

	@Test
	@DisplayName("half-open servers gauge at 2 with the probe flag visible")
	void halfOpenGauges() {
		MutableClock clock = new MutableClock();
		McpGatewayProperties properties = new McpGatewayProperties();
		properties.setCircuitBreakerCooldown(Duration.ZERO);
		properties.setServers(Map.of("flapping", new McpServerConfig(
				"flapping", null, URI.create("https://example.invalid/flapping"), null,
				Duration.ofSeconds(5), Duration.ofSeconds(30),
				Set.of(), Set.of(), Set.of(), 0, true)));
		McpServerCircuitBreakerManager manager =
				new McpServerCircuitBreakerManager(properties, mock(McpCatalogCache.class), clock);
		ProviderCircuitBreaker flapping = manager.getBreaker("flapping");
		for (int i = 0; i < 3; i++) {
			flapping.recordFailure();
		}
		clock.advance();
		assertThat(flapping.tryAcquire()).isTrue();
		SimpleMeterRegistry registry = new SimpleMeterRegistry();

		new McpBreakerMetrics(manager).bindTo(registry);

		assertThat(registry.get("cacherelay.mcp.circuit.breaker.state").tag("server", "flapping")
				.gauge().value()).isEqualTo(2.0);
	}

	private static final class MutableClock extends Clock {

		private Instant now = Instant.now();

		private void advance() {
			now = now.plusMillis(50);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
