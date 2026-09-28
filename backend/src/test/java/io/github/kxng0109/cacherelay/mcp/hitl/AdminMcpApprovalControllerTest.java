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
		when(valueOperations.get("mcp:hitl:pending:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")).thenReturn(null);
		ResponseEntity<String> resp1 = controller.getPendingApproval("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
		assertThat(resp1.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		// Present
		String mockJson = "{\"tokenId\":\"9f8e7d6c5b4a32109f8e7d6c5b4a3210\",\"toolName\":\"postgres__execute_sql\"}";
		when(valueOperations.get("mcp:hitl:pending:9f8e7d6c5b4a32109f8e7d6c5b4a3210")).thenReturn(mockJson);
		ResponseEntity<String> resp2 = controller.getPendingApproval("9f8e7d6c5b4a32109f8e7d6c5b4a3210");
		assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(resp2.getBody()).isEqualTo(mockJson);
	}

	@Test
	@DisplayName("approveToolCall sets APPROVED in Redis and returns 200")
	void approveToolCallSuccess() {
		when(valueOperations.get("mcp:hitl:pending:9f8e7d6c5b4a32109f8e7d6c5b4a3210")).thenReturn("{\"tokenId\":\"9f8e7d6c5b4a32109f8e7d6c5b4a3210\"}");

		ResponseEntity<String> response = controller.approveToolCall("9f8e7d6c5b4a32109f8e7d6c5b4a3210", null, new MockHttpServletRequest());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("APPROVED");
		verify(valueOperations).set("mcp:hitl:approved:9f8e7d6c5b4a32109f8e7d6c5b4a3210", "APPROVED", 300, TimeUnit.SECONDS);
	}

	@Test
	@DisplayName("ADM-B10: malformed token ids are rejected 400 without touching Redis")
	void malformedTokenIdRejected() {
		MockHttpServletRequest request = new MockHttpServletRequest();

		for (String evil : new String[]{"../x", "tok-1", "", " ", "9F8E7D6C5B4A32109F8E7D6C5B4A3210",
				"9f8e7d6c5b4a32109f8e7d6c5b4a321", "9f8e7d6c5b4a32109f8e7d6c5b4a3210x",
				"g".repeat(32), "x".repeat(10_000)}) {
			assertThat(controller.approveToolCall(evil, null, request).getStatusCode())
					.isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(controller.rejectToolCall(evil, null, request).getStatusCode())
					.isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(controller.getPendingApproval(evil).getStatusCode())
					.isEqualTo(HttpStatus.BAD_REQUEST);
		}
		verifyNoInteractions(redisTemplate);
		verifyNoInteractions(valueOperations);
	}

	@Test
	@DisplayName("ADM-B10: null token ids are rejected 400 without touching Redis")
	@SuppressWarnings("DataFlowIssue")
	void nullTokenIdRejected() {
		MockHttpServletRequest request = new MockHttpServletRequest();

		assertThat(controller.approveToolCall(null, null, request).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(controller.rejectToolCall(null, null, request).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(controller.getPendingApproval(null).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		verifyNoInteractions(redisTemplate);
		verifyNoInteractions(valueOperations);
	}

	@Test
	@DisplayName("approveToolCall returns 404 when pending token not found")
	void approveToolCallNotFound() {
		when(valueOperations.get("mcp:hitl:pending:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).thenReturn(null);
		ResponseEntity<String> response = controller.approveToolCall("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", null, new MockHttpServletRequest());
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("rejectToolCall purges keys and returns 200")
	void rejectToolCallPurgesKeys() {
		ResponseEntity<String> response = controller.rejectToolCall("9f8e7d6c5b4a32109f8e7d6c5b4a3210", null, new MockHttpServletRequest());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("REJECTED");
		verify(redisTemplate).delete("mcp:hitl:pending:9f8e7d6c5b4a32109f8e7d6c5b4a3210");
		verify(redisTemplate).delete("mcp:hitl:approved:9f8e7d6c5b4a32109f8e7d6c5b4a3210");
		verify(valueOperations).set("mcp:hitl:rejected:9f8e7d6c5b4a32109f8e7d6c5b4a3210", "REJECTED", 86_400L, TimeUnit.SECONDS);
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
		when(valueOperations.get("mcp:hitl:pending:cbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcb")).thenReturn("   ");

		assertThat(controller.getPendingApproval("cbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcb").getStatusCode())
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
		when(valueOperations.get("mcp:hitl:pending:d9d9d9d9d9d9d9d9d9d9d9d9d9d9d9d9")).thenReturn("{\"tokenId\":\"d9d9d9d9d9d9d9d9d9d9d9d9d9d9d9d9\"}");

		ResponseEntity<String> approved =
				controller.approveToolCall("d9d9d9d9d9d9d9d9d9d9d9d9d9d9d9d9", new DecisionRequest("routine", "on-call"),
						new MockHttpServletRequest());

		assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(valueOperations).set(
				eq("mcp:hitl:approved:d9d9d9d9d9d9d9d9d9d9d9d9d9d9d9d9"), eq("APPROVED"), eq(300L), eq(TimeUnit.SECONDS));
		verify(valueOperations).set(
				eq("mcp:hitl:decision:d9d9d9d9d9d9d9d9d9d9d9d9d9d9d9d9"), contains("routine"), eq(86_400L),
				eq(TimeUnit.SECONDS));

		ResponseEntity<String> rejected =
				controller.rejectToolCall("d9d9d9d9d9d9d9d9d9d9d9d9d9d9d9d9", new DecisionRequest("risky", null), new MockHttpServletRequest());

		assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.OK);
		verify(valueOperations).set(
				eq("mcp:hitl:decision:d9d9d9d9d9d9d9d9d9d9d9d9d9d9d9d9"), contains("risky"), eq(86_400L),
				eq(TimeUnit.SECONDS));
	}

	@Test
	@DisplayName("anonymous decisions and blank pending values resolve safely")
	void anonymousAndBlankDecisions() {
		when(valueOperations.get("mcp:hitl:pending:cbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcb")).thenReturn("   ");

		assertThat(controller.approveToolCall("cbcbcbcbcbcbcbcbcbcbcbcbcbcbcbcb", null, new MockHttpServletRequest()).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);

		when(valueOperations.get("mcp:hitl:pending:e8e8e8e8e8e8e8e8e8e8e8e8e8e8e8e8")).thenReturn("{\"tokenId\":\"e8e8e8e8e8e8e8e8e8e8e8e8e8e8e8e8\"}");

		ResponseEntity<String> approved =
				controller.approveToolCall("e8e8e8e8e8e8e8e8e8e8e8e8e8e8e8e8", new DecisionRequest(null, "commander"),
						new MockHttpServletRequest());

		assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
		ArgumentCaptor<String> decisionBody = ArgumentCaptor.forClass(String.class);
		verify(valueOperations).set(
				eq("mcp:hitl:decision:e8e8e8e8e8e8e8e8e8e8e8e8e8e8e8e8"), decisionBody.capture(), eq(86_400L),
				eq(TimeUnit.SECONDS));
		assertThat(decisionBody.getValue())
				.as("self-asserted name ignored without an authenticated identity")
				.contains("master-key")
				.doesNotContain("commander");
	}

	@Test
	@DisplayName("forged decidedBy is ignored in favor of the authenticated admin id")
	void forgedDecidedByIgnored() {
		when(valueOperations.get("mcp:hitl:pending:f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7")).thenReturn("{\"tokenId\":\"f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7\"}");
		MockHttpServletRequest request = new MockHttpServletRequest();
		UUID adminId = UUID.randomUUID();
		request.setAttribute(AdminAuthFilter.ATTRIBUTE_ADMIN_ID, adminId);

		ResponseEntity<String> approved = controller.approveToolCall(
				"f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7", new DecisionRequest("routine", "mallory"), request);

		assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
		ArgumentCaptor<String> decisionBody = ArgumentCaptor.forClass(String.class);
		verify(valueOperations).set(
				eq("mcp:hitl:decision:f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7"), decisionBody.capture(), eq(86_400L),
				eq(TimeUnit.SECONDS));
		assertThat(decisionBody.getValue())
				.as("audit records the authenticated actor")
				.contains(adminId.toString())
				.doesNotContain("mallory");
	}

	@Test
	@DisplayName("sealed pending args are decrypted for review, undecryptable ones marked")
	void sealedArgsDecryptedForReview() {
		String sealedMeta = "{\"tokenId\":\"ab12ab12ab12ab12ab12ab12ab12ab12\",\"args\":\"v1.aead.args.xyz\"}";
		when(valueOperations.get("mcp:hitl:pending:ab12ab12ab12ab12ab12ab12ab12ab12")).thenReturn(sealedMeta);
		when(tokenService.openString("v1.aead.args.xyz"))
				.thenReturn(Optional.of("{\"sql\":\"SELECT 1\"}"));

		ResponseEntity<String> response = controller.getPendingApproval("ab12ab12ab12ab12ab12ab12ab12ab12");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("SELECT 1");
		assertThat(response.getBody()).doesNotContain("v1.aead.args.xyz");
	}

	@Test
	@DisplayName("undecryptable sealed args render as an explicit marker")
	void undecryptableArgsMarked() {
		String sealedMeta = "{\"tokenId\":\"ab12ab12ab12ab12ab12ab12ab12ab12\",\"args\":\"v1.aead.args.xyz\"}";
		when(valueOperations.get("mcp:hitl:pending:ab12ab12ab12ab12ab12ab12ab12ab12")).thenReturn(sealedMeta);
		when(tokenService.openString("v1.aead.args.xyz")).thenReturn(Optional.empty());

		ResponseEntity<String> response = controller.getPendingApproval("ab12ab12ab12ab12ab12ab12ab12ab12");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).contains("***undecryptable***");
	}

	@Test
	@DisplayName("legacy plaintext pending args pass through review")
	void legacyPlaintextArgsPassThrough() {
		String plainMeta = "{\"tokenId\":\"9f8e7d6c5b4a32109f8e7d6c5b4a3210\",\"args\":\"{\\\"sql\\\":\\\"SELECT 1\\\"}\"}";
		when(valueOperations.get("mcp:hitl:pending:9f8e7d6c5b4a32109f8e7d6c5b4a3210")).thenReturn(plainMeta);

		ResponseEntity<String> response = controller.getPendingApproval("9f8e7d6c5b4a32109f8e7d6c5b4a3210");

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
		when(valueOperations.get("mcp:hitl:pending:1b2b1b2b1b2b1b2b1b2b1b2b1b2b1b2b")).thenReturn("{\"tokenId\":\"1b2b1b2b1b2b1b2b1b2b1b2b1b2b1b2b\"}");

		ResponseEntity<String> response = controller.approveToolCall("1b2b1b2b1b2b1b2b1b2b1b2b1b2b1b2b", null, null);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		ArgumentCaptor<String> decisionBody = ArgumentCaptor.forClass(String.class);
		verify(valueOperations).set(
				eq("mcp:hitl:decision:1b2b1b2b1b2b1b2b1b2b1b2b1b2b1b2b"), decisionBody.capture(), eq(86_400L),
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
