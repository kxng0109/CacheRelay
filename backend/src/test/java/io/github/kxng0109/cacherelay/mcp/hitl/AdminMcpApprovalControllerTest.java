package io.github.kxng0109.cacherelay.mcp.hitl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Admin MCP Approval Controller Unit Tests")
class AdminMcpApprovalControllerTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	private AdminMcpApprovalController controller;

	@BeforeEach
	void setUp() {
		lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		controller = new AdminMcpApprovalController(redisTemplate, objectMapper);
	}

	@Test
	@DisplayName("getPendingApproval retrieves metadata or returns 404 when absent")
	void getPendingApprovalScenarios() {
		// Absent
		when(valueOperations.get("mcp:hitl:pending:tok-absent")).thenReturn(null);
		ResponseEntity<String> resp1 = controller.getPendingApproval("tok-absent");
		assertThat(resp1.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		// Present
		String mockJson = "{\"tokenId\":\"tok-1\",\"toolName\":\"postgres__execute_sql\"}";
		when(valueOperations.get("mcp:hitl:pending:tok-1")).thenReturn(mockJson);
		ResponseEntity<String> resp2 = controller.getPendingApproval("tok-1");
		assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(resp2.getBody()).isEqualTo(mockJson);
	}

	@Test
	@DisplayName("approveToolCall sets APPROVED in Redis and returns 200")
	void approveToolCallSuccess() {
		when(valueOperations.get("mcp:hitl:pending:tok-1")).thenReturn("{\"tokenId\":\"tok-1\"}");

		ResponseEntity<String> response = controller.approveToolCall("tok-1", null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("APPROVED");
		verify(valueOperations).set("mcp:hitl:approved:tok-1", "APPROVED", 300, TimeUnit.SECONDS);
	}

	@Test
	@DisplayName("approveToolCall returns 404 when pending token not found")
	void approveToolCallNotFound() {
		when(valueOperations.get("mcp:hitl:pending:tok-missing")).thenReturn(null);
		ResponseEntity<String> response = controller.approveToolCall("tok-missing", null);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("rejectToolCall purges keys and returns 200")
	void rejectToolCallPurgesKeys() {
		ResponseEntity<String> response = controller.rejectToolCall("tok-1", null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("REJECTED");
		verify(redisTemplate).delete("mcp:hitl:pending:tok-1");
		verify(redisTemplate).delete("mcp:hitl:approved:tok-1");
	}

	@Test
	@DisplayName("listPendingApprovals returns newest-first summaries without args")
	@SuppressWarnings("unchecked")
	void listPendingApprovalsSummaries() {
		Cursor<String> cursor = mock(Cursor.class);
		when(cursor.hasNext()).thenReturn(true, true, true, true, false);
		when(cursor.next()).thenReturn(
				"mcp:hitl:pending:new",
				"mcp:hitl:pending:old",
				"mcp:hitl:pending:corrupt",
				"mcp:hitl:pending:incomplete");
		when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
		when(valueOperations.get("mcp:hitl:pending:new")).thenReturn(pendingJson("new",
				"2026-09-18T10:00:00Z", "2026-09-18T11:00:00Z"));
		when(valueOperations.get("mcp:hitl:pending:old")).thenReturn(pendingJson("old",
				"2026-09-18T09:00:00Z", "2026-09-18T10:00:00Z"));
		when(valueOperations.get("mcp:hitl:pending:corrupt")).thenReturn("not-json{{{");
		when(valueOperations.get("mcp:hitl:pending:incomplete"))
				.thenReturn("{\"tokenId\":\"incomplete\"}");

		ResponseEntity<List<PendingApprovalSummary>> response = controller.listPendingApprovals();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).hasSize(2);
		assertThat(response.getBody().get(0).tokenId()).isEqualTo("new");
		assertThat(response.getBody().get(0).toolName()).isEqualTo("postgres__run_query");
		assertThat(response.getBody().get(0).serverName()).isEqualTo("postgres");
		assertThat(response.getBody().get(1).tokenId()).isEqualTo("old");
		verify(cursor).close();
	}

	@Test
	@DisplayName("listPendingApprovals on empty keyspace returns empty list")
	@SuppressWarnings("unchecked")
	void listPendingApprovalsEmpty() {
		Cursor<String> cursor = mock(Cursor.class);
		when(cursor.hasNext()).thenReturn(false);
		when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

		ResponseEntity<List<PendingApprovalSummary>> response = controller.listPendingApprovals();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEmpty();
		verify(cursor).close();
	}

	@Test
	@DisplayName("approve and reject record reasons without touching the APPROVED flag")
	void decisionsRecordReasons() {
		when(valueOperations.get("mcp:hitl:pending:tok-9")).thenReturn("{\"tokenId\":\"tok-9\"}");

		ResponseEntity<String> approved =
				controller.approveToolCall("tok-9", new DecisionRequest("routine", "on-call"));

		assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(valueOperations).set(
				eq("mcp:hitl:approved:tok-9"), eq("APPROVED"), eq(300L), eq(TimeUnit.SECONDS));
		verify(valueOperations).set(
				eq("mcp:hitl:decision:tok-9"), contains("routine"), eq(86_400L),
				eq(TimeUnit.SECONDS));

		ResponseEntity<String> rejected =
				controller.rejectToolCall("tok-9", new DecisionRequest("risky", null));

		assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(valueOperations).set(
				eq("mcp:hitl:decision:tok-9"), contains("risky"), eq(86_400L),
				eq(TimeUnit.SECONDS));
	}

	private static String pendingJson(String tokenId, String createdAt, String expiresAt) {
		return "{\"tokenId\":\"" + tokenId + "\",\"ownerId\":\"tenant-1\",\"keyName\":\"ops\","
				+ "\"toolName\":\"postgres__run_query\",\"serverName\":\"postgres\","
				+ "\"args\":{\"sql\":\"SELECT 1\"},"
				+ "\"createdAt\":\"" + createdAt + "\",\"expiresAt\":\"" + expiresAt + "\"}";
	}
}
