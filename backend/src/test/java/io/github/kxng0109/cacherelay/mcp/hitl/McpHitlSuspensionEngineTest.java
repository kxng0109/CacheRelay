package io.github.kxng0109.cacherelay.mcp.hitl;

import io.github.kxng0109.cacherelay.config.SensitiveString;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.mcp.config.McpGatewayProperties;
import io.github.kxng0109.cacherelay.mcp.contracts.McpJsonRpcRequest;
import io.github.kxng0109.cacherelay.mcp.contracts.McpJsonRpcResponse;
import io.github.kxng0109.cacherelay.mcp.contracts.McpServerConfig;
import io.github.kxng0109.cacherelay.mcp.contracts.McpTransportType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MCP Human-in-the-Loop (HITL) Suspension Engine Unit Tests")
class McpHitlSuspensionEngineTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private McpGatewayProperties properties;
	private McpAeadResumptionTokenService tokenService;

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	private McpHitlSuspensionEngine suspensionEngine;
	private McpServerConfig hitlServer;
	private VirtualApiKey apiKey;

	@BeforeEach
	void setUp() {
		properties = new McpGatewayProperties();
		properties.setHitlSecret(new SensitiveString("super-secret-hitl-key-32-bytes!!"));
		tokenService = new McpAeadResumptionTokenService(properties, objectMapper);

		lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);

		suspensionEngine = new McpHitlSuspensionEngine(properties, tokenService, redisTemplate, objectMapper);

		hitlServer = new McpServerConfig(
				"postgres",
				McpTransportType.STREAMABLE_HTTP,
				URI.create("http://localhost:8081"),
				null,
				null,
				null,
				Set.of(),
				Set.of(),
				Set.of("execute_sql", "*:delete_*"),
				100,
				true
		);

		apiKey = new VirtualApiKey(
				SHA256Hash.fromRawKey("gw-key-1"),
				"gw-",
				"tenant-corp",
				"prod-key",
				100,
				1000,
				Set.of(),
				Set.of(),
				Set.of(),
				Set.of(),
				true,
				Instant.now()
		);
	}

	@Test
	@DisplayName("isHitlRequired identifies tools requiring approval")
	void isHitlRequiredScenarios() {
		assertThat(suspensionEngine.isHitlRequired(hitlServer, "execute_sql", "postgres__execute_sql")).isTrue();
		assertThat(suspensionEngine.isHitlRequired(hitlServer, "delete_row", "postgres:delete_row")).isTrue();
		assertThat(suspensionEngine.isHitlRequired(hitlServer, "read_data", "postgres__read_data")).isFalse();
	}

	@Test
	@DisplayName("Suspends privileged tool execution when no resumption token is present")
	void suspendsExecutionWithoutToken() {
		ObjectNode params = objectMapper.createObjectNode();
		params.put("name", "postgres__execute_sql");
		params.putObject("arguments").put("sql", "DROP TABLE users");

		McpJsonRpcRequest request = new McpJsonRpcRequest(
				"2.0",
				objectMapper.getNodeFactory().numberNode(1),
				"tools/call",
				params
		);

		Optional<McpJsonRpcResponse> suspendedOpt = suspensionEngine.evaluateOrSuspend(
				request,
				hitlServer,
				"execute_sql",
				"postgres__execute_sql",
				apiKey
		);

		assertThat(suspendedOpt).isPresent();
		McpJsonRpcResponse response = suspendedOpt.get();
		assertThat(response.isSuccess()).isTrue();

		ObjectNode resultNode = (ObjectNode) response.result();
		assertThat(resultNode.get("resultType").asString()).isEqualTo("input_required");
		assertThat(resultNode.get("requestState").asString()).startsWith("v2.aead.");
		assertThat(resultNode.get("inputRequests").has("human_approval")).isTrue();

		verify(valueOperations).set(startsWith("mcp:hitl:pending:"), anyString(), anyLong(), any());
	}

	@Test
	@DisplayName("Permits execution and enforces single-use replay deletion when token is approved")
	void permitsExecutionWithApprovedToken() {
		String argsJson = "{\"sql\":\"DROP TABLE users\"}";
		String argsSha = McpAeadResumptionTokenService.computeArgsSha256(argsJson);
		Instant now = Instant.now();

		McpResumptionClaims claims = new McpResumptionClaims(
				"tok-approved-1",
				"tenant-corp",
				"postgres__execute_sql",
				argsSha,
				now,
				now.plusSeconds(300)
		);
		String token = tokenService.mintToken(claims);

		// Client sends request with resumption token in params
		ObjectNode params = objectMapper.createObjectNode();
		params.put("name", "postgres__execute_sql");
		params.put("requestState", token);
		params.putObject("arguments").put("sql", "DROP TABLE users");

		McpJsonRpcRequest request = new McpJsonRpcRequest(
				"2.0",
				objectMapper.getNodeFactory().numberNode(2),
				"tools/call",
				params
		);

		when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(1L);

		Optional<McpJsonRpcResponse> cleared = suspensionEngine.evaluateOrSuspend(
				request,
				hitlServer,
				"execute_sql",
				"postgres__execute_sql",
				apiKey
		);

		assertThat(cleared).isEmpty(); // Cleared to execute!
		verify(redisTemplate).execute(any(), eq(List.of(
				"mcp:hitl:approved:tok-approved-1",
				"mcp:hitl:pending:tok-approved-1",
				"mcp:hitl:consumed:tok-approved-1"
		)), eq("APPROVED"), eq("300"));
	}

	@Test
	@DisplayName("Unapproved resumption re-suspends under the same call id without littering")
	void extractsTokenFromMetaAndResuspendsIfNotApproved() {
		String argsJson = "{\"sql\":\"DROP TABLE users\"}";
		String argsSha = McpAeadResumptionTokenService.computeArgsSha256(argsJson);
		Instant now = Instant.now();

		McpResumptionClaims claims = new McpResumptionClaims(
				"tok-pending-1",
				"tenant-corp",
				"postgres__execute_sql",
				argsSha,
				now,
				now.plusSeconds(300)
		);
		String token = tokenService.mintToken(claims);

		// Token in _meta instead of top-level params
		ObjectNode params = objectMapper.createObjectNode();
		params.put("name", "postgres__execute_sql");
		params.putObject("_meta").put("requestState", token);
		params.putObject("arguments").put("sql", "DROP TABLE users");

		McpJsonRpcRequest request = new McpJsonRpcRequest(
				"2.0",
				objectMapper.getNodeFactory().numberNode(3),
				"tools/call",
				params
		);

		when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(0L); // Not approved

		Optional<McpJsonRpcResponse> suspendedOpt = suspensionEngine.evaluateOrSuspend(
				request,
				hitlServer,
				"execute_sql",
				"postgres__execute_sql",
				apiKey
		);

		assertThat(suspendedOpt).isPresent();
		McpJsonRpcResponse response = suspendedOpt.get();
		assertThat(response.isSuccess()).isTrue();
		ObjectNode resultNode = (ObjectNode) response.result();
		assertThat(resultNode.get("resultType").asString()).isEqualTo("input_required");

		// The re-suspension keeps the SAME call id so approvals stay bound to the id the
		// client holds and no stale pending entry is orphaned in Redis.
		String returnedToken = resultNode.get("requestState").asString();
		Optional<McpResumptionClaims> reClaims = tokenService.verifyAndExtract(
				returnedToken, argsSha, "tenant-corp");
		assertThat(reClaims).isPresent();
		assertThat(reClaims.get().tokenId()).isEqualTo("tok-pending-1");

		// Exactly one pending write, under the reused id, preserving the original wait time.
		ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
		verify(valueOperations, times(1)).set(
				keyCaptor.capture(), valueCaptor.capture(), anyLong(), any());
		assertThat(keyCaptor.getValue()).isEqualTo("mcp:hitl:pending:tok-pending-1");
		ObjectNode pendingMeta = (ObjectNode) objectMapper.readTree(valueCaptor.getValue());
		assertThat(pendingMeta.get("createdAt").asString()).isEqualTo(now.toString());
	}

	@Test
	@DisplayName("Replay of a consumed approval is denied and never re-suspends")
	void consumedReplayIsDenied() {
		String argsJson = "{\"sql\":\"DROP TABLE users\"}";
		String argsSha = McpAeadResumptionTokenService.computeArgsSha256(argsJson);
		Instant now = Instant.now();

		McpResumptionClaims claims = new McpResumptionClaims(
				"tok-consumed-1",
				"tenant-corp",
				"postgres__execute_sql",
				argsSha,
				now,
				now.plusSeconds(300)
		);
		String token = tokenService.mintToken(claims);

		ObjectNode params = objectMapper.createObjectNode();
		params.put("name", "postgres__execute_sql");
		params.put("requestState", token);
		params.putObject("arguments").put("sql", "DROP TABLE users");

		McpJsonRpcRequest request = new McpJsonRpcRequest(
				"2.0",
				objectMapper.getNodeFactory().numberNode(4),
				"tools/call",
				params
		);

		when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(-1L); // Consumed

		Optional<McpJsonRpcResponse> outcome = suspensionEngine.evaluateOrSuspend(
				request,
				hitlServer,
				"execute_sql",
				"postgres__execute_sql",
				apiKey
		);

		assertThat(outcome).isPresent();
		assertThat(outcome.get().isSuccess()).isFalse();
		assertThat(outcome.get().error()).isNotNull();
		assertThat(outcome.get().error().code()).isEqualTo(-32603);
		verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any());
	}
}
