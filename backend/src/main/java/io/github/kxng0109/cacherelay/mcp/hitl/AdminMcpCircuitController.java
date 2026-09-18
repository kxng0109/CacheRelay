package io.github.kxng0109.cacherelay.mcp.hitl;

import java.util.List;

import io.github.kxng0109.cacherelay.admin.dto.CircuitStateResponse;
import io.github.kxng0109.cacherelay.config.OpenApiConfig;
import io.github.kxng0109.cacherelay.mcp.resilience.McpServerCircuitBreakerManager;
import io.github.kxng0109.cacherelay.proxy.failover.ProviderCircuitBreaker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for inspecting and resetting upstream MCP server circuit breakers under
 * {@code /v1/admin/mcp/circuits}. Mirrors the provider circuit surface with per-server
 * failure counts and cooldown remainders.
 */
@RestController
@RequestMapping("/v1/admin/mcp/circuits")
@RequiredArgsConstructor
@Tag(name = "Admin - MCP Circuit Breakers", description = "Inspecting real-time MCP server circuit breaker states and force-resetting degraded servers")
public class AdminMcpCircuitController {

	private final McpServerCircuitBreakerManager circuitBreakers;

	/**
	 * Lists the current circuit breaker states of all materialized upstream MCP servers.
	 *
	 * @return HTTP 200 OK with list of server circuit states
	 */
	@Operation(
			summary = "List MCP server circuit breaker states",
			description = "Inspects real-time circuit breaker states with failure counts and cooldown remainders across upstream MCP servers.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(
					responseCode = "200",
					description = "List of circuit breaker states retrieved",
					content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = CircuitStateResponse.class)))
			),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect")
	})
	@GetMapping
	public ResponseEntity<List<CircuitStateResponse>> listCircuits() {
		List<CircuitStateResponse> response = circuitBreakers.serverNames().stream()
				.sorted()
				.map(name -> snapshot(name, circuitBreakers.getBreaker(name)))
				.toList();
		return ResponseEntity.ok(response);
	}

	/**
	 * Retrieves the circuit breaker state for a specific upstream MCP server.
	 *
	 * @param server server name
	 * @return HTTP 200 OK with server circuit state, or HTTP 404 Not Found
	 */
	@Operation(
			summary = "Get circuit breaker state for MCP server",
			description = "Retrieves the real-time circuit breaker state for a specific upstream MCP server.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Circuit state retrieved", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CircuitStateResponse.class))),
			@ApiResponse(responseCode = "404", description = "Server not found"),
			@ApiResponse(responseCode = "401", description = "Unauthorized")
	})
	@GetMapping("/{server}")
	public ResponseEntity<CircuitStateResponse> getCircuit(
			@Parameter(description = "Upstream MCP server identifier", example = "postgres")
			@PathVariable("server") String server
	) {
		if (!circuitBreakers.serverNames().contains(server)) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok(snapshot(server, circuitBreakers.getBreaker(server)));
	}

	/**
	 * Force-resets an upstream MCP server's circuit breaker to CLOSED.
	 *
	 * @param server server name
	 * @return HTTP 200 OK with updated server circuit state, or HTTP 404 Not Found
	 */
	@Operation(
			summary = "Force-reset MCP server circuit breaker",
			description = "Force-transitions the specified server's circuit breaker to CLOSED state and resets the failure counter.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Circuit breaker reset to CLOSED", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CircuitStateResponse.class))),
			@ApiResponse(responseCode = "404", description = "Server not found"),
			@ApiResponse(responseCode = "401", description = "Unauthorized")
	})
	@PostMapping("/{server}/reset")
	public ResponseEntity<CircuitStateResponse> resetCircuit(
			@Parameter(description = "Upstream MCP server identifier", example = "postgres")
			@PathVariable("server") String server
	) {
		if (!circuitBreakers.serverNames().contains(server)) {
			return ResponseEntity.notFound().build();
		}
		circuitBreakers.reset(server);
		return ResponseEntity.ok(snapshot(server, circuitBreakers.getBreaker(server)));
	}

	private static CircuitStateResponse snapshot(String server, ProviderCircuitBreaker breaker) {
		return new CircuitStateResponse(
				server,
				breaker.getState().name(),
				breaker.getFailureCount(),
				breaker.cooldownRemainingMillis(),
				breaker.halfOpenProbeInFlight());
	}
}
