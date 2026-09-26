package io.github.kxng0109.cacherelay.mcp.hitl;

import io.github.kxng0109.cacherelay.admin.AdminAuthFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
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

	@Mock
	private McpAeadResumptionTokenService tokenService;

	private AdminMcpApprovalController controller;

	@BeforeEach
	void setUp() {
		lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		controller = new AdminMcpApprovalController(redisTemplate, objectMapper, tokenService);
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

		ResponseEntity<String> response = controller.approveToolCall("tok-1", null, new MockHttpServletRequest());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("APPROVED");
		verify(valueOperations).set("mcp:hitl:approved:tok-1", "APPROVED", 300, TimeUnit.SECONDS);
	}

	@Test
	@DisplayName("approveToolCall returns 404 when pending token not found")
	void approveToolCallNotFound() {
		when(valueOperations.get("mcp:hitl:pending:tok-missing")).thenReturn(null);
		ResponseEntity<String> response = controller.approveToolCall("tok-missing", null, new MockHttpServletRequest());
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("rejectToolCall purges keys and returns 200")
	void rejectToolCallPurgesKeys() {
		ResponseEntity<String> response = controller.rejectToolCall("tok-1", null, new MockHttpServletRequest());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("REJECTED");
		verify(redisTemplate).delete("mcp:hitl:pending:tok-1");
		verify(redisTemplate).delete("mcp:hitl:approved:tok-1");
		verify(valueOperations).set("mcp:hitl:rejected:tok-1", "REJECTED", 86_400L, TimeUnit.SECONDS);
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

		ResponseEntity<AdminMcpApprovalController.PendingApprovalsResponse> response =
				controller.listPendingApprovals();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().approvals()).hasSize(2);
		assertThat(response.getBody().approvals().get(0).tokenId()).isEqualTo("new");
		assertThat(response.getBody().approvals().get(0).toolName()).isEqualTo("postgres__run_query");
		assertThat(response.getBody().approvals().get(0).serverName()).isEqualTo("postgres");
		assertThat(response.getBody().approvals().get(1).tokenId()).isEqualTo("old");
		verify(cursor).close();
	}

	@Test
	@DisplayName("FS-B12: listing caps at 500 summaries and skips blank entries")
	void listPendingApprovalsCapAndSkips() {
		Cursor<String> cursor = mock(Cursor.class);
		when(cursor.hasNext()).thenReturn(true);
		AtomicInteger counter = new AtomicInteger();
		when(cursor.next()).thenAnswer(invocation ->
				"mcp:hitl:pending:bulk-" + counter.getAndIncrement());
		when(valueOperations.get(anyString())).thenAnswer(invocation ->
				pendingJson("bulk", "2026-09-18T10:00:00Z", "2026-09-18T11:00:00Z"));
		when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

		ResponseEntity<AdminMcpApprovalController.PendingApprovalsResponse> response =
				controller.listPendingApprovals();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().approvals()).hasSize(500);
		verify(cursor).close();
	}

	@Test
	@DisplayName("FS-B12: blank, null, and field-less entries never surface")
	void listPendingApprovalsSkipsBadEntries() {
		Cursor<String> cursor = mock(Cursor.class);
		when(cursor.hasNext()).thenReturn(true, true, true, true, false);
		when(cursor.next()).thenReturn(
				"mcp:hitl:pending:blank",
				"mcp:hitl:pending:nil",
				"mcp:hitl:pending:notool",
				"mcp:hitl:pending:notoken");
		when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
		when(valueOperations.get("mcp:hitl:pending:blank")).thenReturn("   ");
		when(valueOperations.get("mcp:hitl:pending:nil")).thenReturn(null);
		when(valueOperations.get("mcp:hitl:pending:notool"))
				.thenReturn("{\"tokenId\":\"notool\"}");
		when(valueOperations.get("mcp:hitl:pending:notoken"))
				.thenReturn("{\"toolName\":\"postgres__run_query\"}");

		ResponseEntity<AdminMcpApprovalController.PendingApprovalsResponse> response =
				controller.listPendingApprovals();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().approvals()).isEmpty();
		verify(cursor).close();
	}

	@Test
	@DisplayName("FS-B12: null approval lists normalize to empty")
	void pendingApprovalsResponseNullList() {
		AdminMcpApprovalController.PendingApprovalsResponse response =
				new AdminMcpApprovalController.PendingApprovalsResponse(null);

		assertThat(response.approvals()).isEmpty();
	}

	@Test
	@DisplayName("FS-B12: blank pending values answer 404")
	void getPendingApprovalBlankIs404() {
		when(valueOperations.get("mcp:hitl:pending:tok-blank")).thenReturn("   ");

		assertThat(controller.getPendingApproval("tok-blank").getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("listPendingApprovals on empty keyspace returns empty list")
	@SuppressWarnings("unchecked")
	void listPendingApprovalsEmpty() {
		Cursor<String> cursor = mock(Cursor.class);
		when(cursor.hasNext()).thenReturn(false);
		when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

		ResponseEntity<AdminMcpApprovalController.PendingApprovalsResponse> response =
				controller.listPendingApprovals();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().approvals()).isEmpty();
		verify(cursor).close();
	}

	@Test
	@DisplayName("approve and reject record reasons without touching the APPROVED flag")
	void decisionsRecordReasons() {
		when(valueOperations.get("mcp:hitl:pending:tok-9")).thenReturn("{\"tokenId\":\"tok-9\"}");

		ResponseEntity<String> approved =
				controller.approveToolCall("tok-9", new DecisionRequest("routine", "on-call"),
						new MockHttpServletRequest());

		assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(valueOperations).set(
				eq("mcp:hitl:approved:tok-9"), eq("APPROVED"), eq(300L), eq(TimeUnit.SECONDS));
		verify(valueOperations).set(
				eq("mcp:hitl:decision:tok-9"), contains("routine"), eq(86_400L),
				eq(TimeUnit.SECONDS));

		ResponseEntity<String> rejected =
				controller.rejectToolCall("tok-9", new DecisionRequest("risky", null), new MockHttpServletRequest());

		assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(valueOperations).set(
				eq("mcp:hitl:decision:tok-9"), contains("risky"), eq(86_400L),
				eq(TimeUnit.SECONDS));
	}

	@Test
	@DisplayName("anonymous decisions and blank pending values resolve safely")
	void anonymousAndBlankDecisions() {
		when(valueOperations.get("mcp:hitl:pending:tok-blank")).thenReturn("   ");

		assertThat(controller.approveToolCall("tok-blank", null, new MockHttpServletRequest()).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);

		when(valueOperations.get("mcp:hitl:pending:tok-8")).thenReturn("{\"tokenId\":\"tok-8\"}");

		ResponseEntity<String> approved =
				controller.approveToolCall("tok-8", new DecisionRequest(null, "commander"),
						new MockHttpServletRequest());

		assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
		ArgumentCaptor<String> decisionBody = ArgumentCaptor.forClass(String.class);
		verify(valueOperations).set(
				eq("mcp:hitl:decision:tok-8"), decisionBody.capture(), eq(86_400L),
				eq(TimeUnit.SECONDS));
		assertThat(decisionBody.getValue())
				.as("self-asserted name ignored without an authenticated identity")
				.contains("master-key")
				.doesNotContain("commander");
	}

	@Test
	@DisplayName("forged decidedBy is ignored in favor of the authenticated admin id")
	void forgedDecidedByIgnored() {
		when(valueOperations.get("mcp:hitl:pending:tok-7")).thenReturn("{\"tokenId\":\"tok-7\"}");
		MockHttpServletRequest request = new MockHttpServletRequest();
		UUID adminId = UUID.randomUUID();
		request.setAttribute(AdminAuthFilter.ATTRIBUTE_ADMIN_ID, adminId);

		ResponseEntity<String> approved = controller.approveToolCall(
				"tok-7", new DecisionRequest("routine", "mallory"), request);

		assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
		ArgumentCaptor<String> decisionBody = ArgumentCaptor.forClass(String.class);
		verify(valueOperations).set(
				eq("mcp:hitl:decision:tok-7"), decisionBody.capture(), eq(86_400L),
				eq(TimeUnit.SECONDS));
		assertThat(decisionBody.getValue())
				.as("audit records the authenticated actor")
				.contains(adminId.toString())
				.doesNotContain("mallory");
	}

	@Test
	@DisplayName("sealed pending args are decrypted for review, undecryptable ones marked")
	void sealedArgsDecryptedForReview() {
		String sealedMeta = "{\"tokenId\":\"tok-sealed\",\"args\":\"v1.aead.args.xyz\"}";
		when(valueOperations.get("mcp:hitl:pending:tok-sealed")).thenReturn(sealedMeta);
		when(tokenService.openString("v1.aead.args.xyz"))
				.thenReturn(Optional.of("{\"sql\":\"SELECT 1\"}"));

		ResponseEntity<String> response = controller.getPendingApproval("tok-sealed");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("SELECT 1");
		assertThat(response.getBody()).doesNotContain("v1.aead.args.xyz");
	}

	@Test
	@DisplayName("undecryptable sealed args render as an explicit marker")
	void undecryptableArgsMarked() {
		String sealedMeta = "{\"tokenId\":\"tok-sealed\",\"args\":\"v1.aead.args.xyz\"}";
		when(valueOperations.get("mcp:hitl:pending:tok-sealed")).thenReturn(sealedMeta);
		when(tokenService.openString("v1.aead.args.xyz")).thenReturn(Optional.empty());

		ResponseEntity<String> response = controller.getPendingApproval("tok-sealed");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("***undecryptable***");
	}

	@Test
	@DisplayName("legacy plaintext pending args pass through review")
	void legacyPlaintextArgsPassThrough() {
		String plainMeta = "{\"tokenId\":\"tok-1\",\"args\":\"{\\\"sql\\\":\\\"SELECT 1\\\"}\"}";
		when(valueOperations.get("mcp:hitl:pending:tok-1")).thenReturn(plainMeta);

		ResponseEntity<String> response = controller.getPendingApproval("tok-1");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isEqualTo(plainMeta);
	}

	@Test
	@DisplayName("FS-B12: non-object, argless, and non-string pending metadata pass through")
	void malformedPendingMetadataPassThrough() {
		assertThat(controller.decryptArgsForReview("42")).isEqualTo("42");
		assertThat(controller.decryptArgsForReview("{\"tokenId\":\"tok-x\"}"))
				.isEqualTo("{\"tokenId\":\"tok-x\"}");
		assertThat(controller.decryptArgsForReview("{\"tokenId\":\"tok-x\",\"args\":42}"))
				.isEqualTo("{\"tokenId\":\"tok-x\",\"args\":42}");
	}

	@Test
	@DisplayName("FS-B12: decisions without an HTTP request attribute to the master key")
	void nullRequestAttributesToMasterKey() {
		when(valueOperations.get("mcp:hitl:pending:tok-nr")).thenReturn("{\"tokenId\":\"tok-nr\"}");

		ResponseEntity<String> response = controller.approveToolCall("tok-nr", null, null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		ArgumentCaptor<String> decisionBody = ArgumentCaptor.forClass(String.class);
		verify(valueOperations).set(
				eq("mcp:hitl:decision:tok-nr"), decisionBody.capture(), eq(86_400L),
				eq(TimeUnit.SECONDS));
		assertThat(decisionBody.getValue()).contains("master-key");
	}

	private static String pendingJson(String tokenId, String createdAt, String expiresAt) {
		return "{\"tokenId\":\"" + tokenId + "\",\"ownerId\":\"tenant-1\",\"keyName\":\"ops\","
				+ "\"toolName\":\"postgres__run_query\",\"serverName\":\"postgres\","
				+ "\"args\":{\"sql\":\"SELECT 1\"},"
				+ "\"createdAt\":\"" + createdAt + "\",\"expiresAt\":\"" + expiresAt + "\"}";
	}
}
