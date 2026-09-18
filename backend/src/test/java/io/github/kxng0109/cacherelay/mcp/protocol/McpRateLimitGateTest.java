package io.github.kxng0109.cacherelay.mcp.protocol;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import io.github.kxng0109.cacherelay.contracts.RateLimitDecision;
import io.github.kxng0109.cacherelay.contracts.RejectionReason;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.mcp.config.McpGatewayProperties;
import io.github.kxng0109.cacherelay.mcp.hitl.McpHitlSuspensionEngine;
import io.github.kxng0109.cacherelay.mcp.resilience.McpServerCircuitBreakerManager;
import io.github.kxng0109.cacherelay.mcp.router.McpCatalogAggregator;
import io.github.kxng0109.cacherelay.mcp.router.McpRouter;
import io.github.kxng0109.cacherelay.mcp.security.McpGuardrailScanner;
import io.github.kxng0109.cacherelay.mcp.security.McpJsonSchemaValidator;
import io.github.kxng0109.cacherelay.mcp.security.McpToolRbacPolicyEngine;
import io.github.kxng0109.cacherelay.security.ratelimit.KeyManagementService;
import io.github.kxng0109.cacherelay.security.ratelimit.RateLimitEngine;
import io.github.kxng0109.cacherelay.security.ratelimit.RateLimitUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the tools/call flood gate: rejections answer 429, outages fail closed, and
 * non-call methods plus hash-less requests pass through untouched.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MCP tools/call rate gate")
class McpRateLimitGateTest {

	private static final String META =
			"\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2024-11-05\","
					+ "\"io.modelcontextprotocol/clientCapabilities\":{\"tools\":{}}}";
	private static final String CALL_BODY =
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
					+ "\"params\":{\"name\":\"postgres__run_query\",\"arguments\":{}," + META + "}}";
	private static final String PING_BODY =
			"{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\",\"params\":{" + META + "}}";

	@Mock
	private McpGatewayProperties properties;
	@Mock
	private McpCatalogAggregator catalogAggregator;
	@Mock
	private McpRouter router;
	@Mock
	private McpToolRbacPolicyEngine rbacPolicyEngine;
	@Mock
	private McpJsonSchemaValidator jsonSchemaValidator;
	@Mock
	private McpGuardrailScanner guardrailScanner;
	@Mock
	private McpHitlSuspensionEngine hitlSuspensionEngine;
	@Mock
	private McpServerCircuitBreakerManager circuitBreakerManager;
	@Mock
	private KeyManagementService keyManagementService;
	@Mock
	private RateLimitEngine rateLimitEngine;
	@Mock
	private HttpClient httpClient;

	private McpStreamableHttpController controller;
	private VirtualApiKey apiKey;

	@BeforeEach
	void setUp() {
		controller = new McpStreamableHttpController(
				properties,
				catalogAggregator,
				router,
				rbacPolicyEngine,
				jsonSchemaValidator,
				guardrailScanner,
				hitlSuspensionEngine,
				circuitBreakerManager,
				keyManagementService,
				rateLimitEngine,
				httpClient,
				new ObjectMapper()
		);
		apiKey = new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-test-key-1234567890abcdef"),
				"gw-",
				"tenant-1",
				"test-key",
				60,
				100000,
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				true,
				Instant.now()
		);
		when(properties.isEnabled()).thenReturn(true);
	}

	private void keyResolves() {
		when(keyManagementService.findByHash(any())).thenReturn(Optional.of(apiKey));
	}

	@Test
	@DisplayName("rejected tool calls answer 429 with Retry-After")
	void rejectedIs429() {
		keyResolves();
		when(rateLimitEngine.checkRequestRate(any(), any())).thenReturn(
				new RateLimitDecision.Rejected(RejectionReason.RPM_EXCEEDED, 45));

		ResponseEntity<String> response = controller.handleStreamableHttp(CALL_BODY,
				"2024-11-05", null, bearerRequest());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("45");
		assertThat(response.getBody()).contains("Rate limit exceeded");
	}

	@Test
	@DisplayName("limiter outages fail closed with 503")
	void outageIs503() {
		keyResolves();
		when(rateLimitEngine.checkRequestRate(any(), any())).thenThrow(
				new RateLimitUnavailableException("down"));

		ResponseEntity<String> response = controller.handleStreamableHttp(CALL_BODY,
				"2024-11-05", null, bearerRequest());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
	}

	@Test
	@DisplayName("allowed calls proceed past the gate")
	void allowedProceeds() {
		keyResolves();
		when(rateLimitEngine.checkRequestRate(any(), any())).thenReturn(
				mock(RateLimitDecision.Allowed.class));
		when(router.resolveToolRoute(any())).thenReturn(Optional.empty());

		ResponseEntity<String> response = controller.handleStreamableHttp(CALL_BODY,
				"2024-11-05", null, bearerRequest());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("Method not found");
	}

	@Test
	@DisplayName("non-call methods skip the gate entirely")
	void nonCallSkipsGate() {
		keyResolves();

		ResponseEntity<String> response = controller.handleStreamableHttp(PING_BODY,
				"2024-11-05", null, bearerRequest());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	@DisplayName("attribute-attributed keys without a secret proceed")
	void hashlessProceeds() {
		when(router.resolveToolRoute(any())).thenReturn(Optional.empty());
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/mcp");
		request.setAttribute("virtualApiKey", apiKey);

		ResponseEntity<String> response = controller.handleStreamableHttp(CALL_BODY,
				"2024-11-05", null, request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("Method not found");
	}

	private MockHttpServletRequest bearerRequest() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/mcp");
		request.addHeader("Authorization", "Bearer gw-test-key-1234567890abcdef");
		return request;
	}
}
