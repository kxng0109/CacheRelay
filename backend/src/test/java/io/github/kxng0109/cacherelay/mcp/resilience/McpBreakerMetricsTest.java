package io.github.kxng0109.cacherelay.mcp.resilience;

import io.github.kxng0109.cacherelay.proxy.failover.ProviderCircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
	@DisplayName("binds state and failure gauges per materialized server")
	void bindsGauges() {		McpServerCircuitBreakerManager manager = mock(McpServerCircuitBreakerManager.class);
		ProviderCircuitBreaker healthy =
				new ProviderCircuitBreaker("ok", Clock.systemUTC(), 3, Duration.ofSeconds(30));
		ProviderCircuitBreaker tripped =
				new ProviderCircuitBreaker("down", Clock.systemUTC(), 1, Duration.ofSeconds(30));
		tripped.recordFailure();
		when(manager.serverNames()).thenReturn(Set.of("ok", "down"));
		when(manager.getBreaker("ok")).thenReturn(healthy);
		when(manager.getBreaker("down")).thenReturn(tripped);
		SimpleMeterRegistry registry = new SimpleMeterRegistry();

		new McpBreakerMetrics(manager).bindTo(registry);

		assertThat(registry.get("cacherelay.mcp.circuit.breaker.state").tag("server", "ok")
				.gauge().value()).isZero();
		assertThat(registry.get("cacherelay.mcp.circuit.breaker.state").tag("server", "down")
				.gauge().value()).isEqualTo(1.0);
		assertThat(registry.get("cacherelay.mcp.circuit.breaker.failures").tag("server", "down")
				.gauge().value()).isEqualTo(1.0);
	}

	@Test
	@DisplayName("half-open servers gauge at 2 with the probe flag visible")
	void halfOpenGauges() {
		MutableClock clock = new MutableClock();
		McpServerCircuitBreakerManager manager = mock(McpServerCircuitBreakerManager.class);
		ProviderCircuitBreaker flapping =
				new ProviderCircuitBreaker("flapping", clock, 1, Duration.ZERO);
		flapping.recordFailure();
		clock.advance();
		assertThat(flapping.tryAcquire()).isTrue();
		when(manager.serverNames()).thenReturn(Set.of("flapping"));
		when(manager.getBreaker("flapping")).thenReturn(flapping);
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
