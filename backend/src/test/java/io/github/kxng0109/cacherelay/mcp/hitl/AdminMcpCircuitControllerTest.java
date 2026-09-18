package io.github.kxng0109.cacherelay.mcp.hitl;

import io.github.kxng0109.cacherelay.mcp.config.McpGatewayProperties;
import io.github.kxng0109.cacherelay.mcp.resilience.McpServerCircuitBreakerManager;
import io.github.kxng0109.cacherelay.mcp.router.McpCatalogCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for MCP server circuit administration: listing, lookup, and reset.
 */
@DisplayName("AdminMcpCircuitController")
class AdminMcpCircuitControllerTest {

	private final McpCatalogCache catalogCache = mock(McpCatalogCache.class);
	private final McpGatewayProperties properties = mock(McpGatewayProperties.class);
	private final McpServerCircuitBreakerManager manager =
			new McpServerCircuitBreakerManager(properties, catalogCache, Clock.systemUTC());
	private final AdminMcpCircuitController controller = new AdminMcpCircuitController(manager);

	@Test
	@DisplayName("empty manager lists nothing and misses lookups")
	void emptyListsNothing() {
		when(properties.getCircuitBreakerFailureThreshold()).thenReturn(3);
		when(properties.getCircuitBreakerCooldown()).thenReturn(Duration.ofSeconds(30));

		var list = controller.listCircuits();

		assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(list.getBody()).isEmpty();
		assertThat(controller.getCircuit("ghost").getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(controller.resetCircuit("ghost").getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("tripped servers report failures and countdowns; reset closes")
	void trippedReportsAndResets() {
		when(properties.getCircuitBreakerFailureThreshold()).thenReturn(1);
		when(properties.getCircuitBreakerCooldown()).thenReturn(Duration.ofSeconds(30));
		manager.recordFailure("postgres");

		var found = controller.getCircuit("postgres");

		assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(found.getBody().state()).isEqualTo("OPEN");
		assertThat(found.getBody().failures()).isEqualTo(1);
		assertThat(found.getBody().cooldownMsRemaining()).isPositive();
		assertThat(found.getBody().halfOpenProbe()).isFalse();

		var reset = controller.resetCircuit("postgres");

		assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(reset.getBody().state()).isEqualTo("CLOSED");
		assertThat(reset.getBody().cooldownMsRemaining()).isZero();
		assertThat(controller.listCircuits().getBody()).hasSize(1);
	}
}
