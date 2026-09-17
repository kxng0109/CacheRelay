package io.github.kxng0109.cacherelay.mcp.resilience;

import io.github.kxng0109.cacherelay.config.SensitiveString;
import io.github.kxng0109.cacherelay.mcp.config.McpGatewayProperties;
import io.github.kxng0109.cacherelay.mcp.router.McpCatalogCache;
import io.github.kxng0109.cacherelay.proxy.failover.CircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("McpServerCircuitBreakerManager force-reset")
class McpServerCircuitBreakerManagerResetTest {

	@Mock
	McpCatalogCache catalogCache;

	private McpServerCircuitBreakerManager manager() {
		McpGatewayProperties props = new McpGatewayProperties();
		props.setHitlSecret(new SensitiveString("test-only-hitl-secret-32-bytes-minimum!!"));
		return new McpServerCircuitBreakerManager(
				props, catalogCache, Clock.fixed(Instant.now(), ZoneId.of("UTC")));
	}

	@Test
	@DisplayName("reset force-closes an OPEN breaker to CLOSED and invalidates catalog")
	void resetForceClosesOpenBreaker() {
		McpServerCircuitBreakerManager manager = manager();
		for (int i = 0; i < 5; i++) {
			manager.recordFailure("srv");
		}
		manager.reset("srv");
		assertThat(manager.getBreaker("srv").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
		verify(catalogCache, atLeastOnce()).invalidate();
	}
}
