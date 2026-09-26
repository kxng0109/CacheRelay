package io.github.kxng0109.cacherelay.mcp.hitl;

import io.github.kxng0109.cacherelay.admin.AdminAuthFilter;
import io.github.kxng0109.cacherelay.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * REST controller for Human-in-the-Loop (HITL) administrator inspection, approval, and rejection of suspended tool
 * calls.
 */
@Slf4j
@RestController
@RequestMapping("/v1/admin/mcp/approvals")
@RequiredArgsConstructor
@Tag(name = "Admin - MCP Tool Governance", description = "Human-in-the-Loop (HITL) tool execution review, consent, and approval management")
public class AdminMcpApprovalController {

	private static final String REDIS_PENDING_PREFIX = "mcp:hitl:pending:";
	private static final String REDIS_APPROVED_PREFIX = "mcp:hitl:approved:";
	private static final String REDIS_DECISION_PREFIX = "mcp:hitl:decision:";
	private static final String REDIS_REJECTED_PREFIX = "mcp:hitl:rejected:";
	private static final long DECISION_TTL_SECONDS = 86_400L;
	private static final long PENDING_SCAN_COUNT = 100L;
	private static final int PENDING_LIST_CAP = 500;

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;
	private final McpAeadResumptionTokenService tokenService;

	/**
	 * Lists pending tool invocations (newest first, arguments stripped).
	 *
	 * <p>Keys are per-token with no index, so the list scans {@code mcp:hitl:pending:*}
	 * with a bounded cursor (COUNT hint, hard cap). Corrupt or half-written entries are
	 * skipped; expired entries self-exclude via TTL. Spring matches this exact path before
	 * {@code /{tokenId}}, so {@code pending} never resolves as a token id.</p>
	 */
	@Operation(
			summary = "List pending MCP tool invocations",
			description = "Lists suspended tool executions awaiting approval (newest first, without arguments).",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Pending invocation summaries envelope"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key required")
	})
	@GetMapping(value = "/pending", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<PendingApprovalsResponse> listPendingApprovals() {
		List<PendingApprovalSummary> summaries = new ArrayList<>();
		ScanOptions options = ScanOptions.scanOptions()
				.count(PENDING_SCAN_COUNT)
				.match(REDIS_PENDING_PREFIX + "*")
				.build();
		try (Cursor<String> cursor = redisTemplate.scan(options)) {
			while (cursor.hasNext() && summaries.size() < PENDING_LIST_CAP) {
				String key = cursor.next();
				String raw = redisTemplate.opsForValue().get(key);
				PendingApprovalSummary summary = toSummary(raw);
				if (summary != null) {
					summaries.add(summary);
				}
			}
		}
		summaries.sort(Comparator.comparing(PendingApprovalSummary::createdAt).reversed());
		return ResponseEntity.ok(new PendingApprovalsResponse(summaries));
	}

	/**
	 * Envelope for the pending-approval list (the contract promises an object, never a bare array).
	 *
	 * @param approvals pending invocation summaries, newest first
	 */
	public record PendingApprovalsResponse(List<PendingApprovalSummary> approvals) {
		/**
		 * Compact constructor defensively copying the list.
		 */
		public PendingApprovalsResponse {
			approvals = approvals == null ? List.of() : List.copyOf(approvals);
		}
	}

	private @Nullable PendingApprovalSummary toSummary(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			JsonNode node = objectMapper.readTree(raw);
			String tokenId = node.path("tokenId").asString("");
			String toolName = node.path("toolName").asString("");
			if (tokenId.isBlank() || toolName.isBlank()) {
				return null;
			}
			return new PendingApprovalSummary(
					tokenId,
					toolName,
					node.path("serverName").asString(""),
					node.path("ownerId").asString(""),
					node.path("keyName").asString(""),
					node.path("createdAt").asString(""),
					node.path("expiresAt").asString(""));
		} catch (Exception e) {
			log.debug("Skipping unparseable pending HITL entry: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Inspects pending tool invocation metadata for a specific token ID.
	 */
	@Operation(
			summary = "Get pending MCP tool invocation details",
			description = "Retrieves the parameters, tenant, and tool metadata for a suspended tool execution awaiting approval.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Pending invocation details retrieved"),
			@ApiResponse(responseCode = "404", description = "Pending invocation not found or expired"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key required")
	})
	@GetMapping(value = "/{tokenId}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> getPendingApproval(
			@Parameter(description = "Hex token ID of the suspended invocation", example = "9f8e7d6c5b4a3210")
			@PathVariable("tokenId") String tokenId
	) {
		String raw = redisTemplate.opsForValue().get(REDIS_PENDING_PREFIX + tokenId);
		if (raw == null || raw.isBlank()) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok(decryptArgsForReview(raw));
	}

	/**
	 * Decrypts sealed pending arguments for admin review. Legacy plaintext entries (stored
	 * before sealing) pass through; undecryptable sealed values render as an explicit marker
	 * rather than ciphertext or nothing.
	 *
	 * @param raw pending metadata JSON
	 * @return metadata JSON with reviewable arguments
	 */
	String decryptArgsForReview(String raw) {
		JsonNode meta;
		try {
			meta = objectMapper.readTree(raw);
		} catch (Exception e) {
			return raw;
		}
		if (!(meta instanceof ObjectNode object) || !object.has("args") || !object.path("args").isString()) {
			return raw;
		}
		String stored = object.path("args").asString();
		String reviewed = tokenService.openString(stored)
				.orElse(stored.startsWith("v1.aead.args.") ? "***undecryptable***" : stored);
		if (reviewed.equals(stored)) {
			return raw;
		}
		ObjectNode copy = object.deepCopy();
		copy.put("args", reviewed);
		try {
			return objectMapper.writeValueAsString(copy);
		} catch (Exception e) {
			return raw;
		}
	}

	/**
	 * Approves a suspended tool invocation, clearing it for execution on subsequent retry.
	 *
	 * <p>The approved flag stays the literal {@code APPROVED} because resumption compares
	 * against it; the optional reason is recorded separately (decision key plus logs) so the
	 * audit trail never disturbs the handshake.</p>
	 */
	@Operation(
			summary = "Approve suspended MCP tool invocation",
			description = "Marks the suspended tool invocation as APPROVED in Redis. When the client retries with the resumption token, execution proceeds.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Invocation successfully approved"),
			@ApiResponse(responseCode = "404", description = "Pending invocation not found or expired"),
			@ApiResponse(responseCode = "401", description = "Unauthorized")
	})
	@PostMapping(value = "/{tokenId}/approve", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> approveToolCall(
			@Parameter(description = "Hex token ID of the suspended invocation", example = "9f8e7d6c5b4a3210")
			@PathVariable("tokenId") String tokenId,
			@RequestBody(required = false) @Valid DecisionRequest decision,
			HttpServletRequest httpRequest
	) {
		String pendingKey = REDIS_PENDING_PREFIX + tokenId;
		String raw = redisTemplate.opsForValue().get(pendingKey);
		if (raw == null || raw.isBlank()) {
			return ResponseEntity.notFound().build();
		}

		// Mark approved with a 300s window
		redisTemplate.opsForValue().set(REDIS_APPROVED_PREFIX + tokenId, "APPROVED", 300, TimeUnit.SECONDS);
		recordDecision(tokenId, "APPROVED", decision, resolveActor(httpRequest));
		log.info("Administrator approved MCP tool invocation for token ID '{}'", tokenId);

		ObjectNode response = objectMapper.createObjectNode();
		response.put("status", "APPROVED");
		response.put("tokenId", tokenId);
		response.put("message", "Tool invocation approved. Client may resume execution.");

		return ResponseEntity.ok(response.toString());
	}

	/**
	 * Rejects and deletes a suspended tool invocation.
	 */
	@Operation(
			summary = "Reject suspended MCP tool invocation",
			description = "Rejects and removes the suspended tool invocation from Redis.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Invocation rejected and purged"),
			@ApiResponse(responseCode = "404", description = "Pending invocation not found"),
			@ApiResponse(responseCode = "401", description = "Unauthorized")
	})
	@PostMapping(value = "/{tokenId}/reject", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> rejectToolCall(
			@Parameter(description = "Hex token ID of the suspended invocation", example = "9f8e7d6c5b4a3210")
			@PathVariable("tokenId") String tokenId,
			@RequestBody(required = false) @Valid DecisionRequest decision,
			HttpServletRequest httpRequest
	) {
		redisTemplate.delete(REDIS_PENDING_PREFIX + tokenId);
		redisTemplate.delete(REDIS_APPROVED_PREFIX + tokenId);
		// Terminal marker: resuming this token is refused without re-suspension for the
		// decision TTL, so a refusal cannot be washed out by a later approval.
		redisTemplate.opsForValue().set(
				REDIS_REJECTED_PREFIX + tokenId, "REJECTED", DECISION_TTL_SECONDS, TimeUnit.SECONDS);
		recordDecision(tokenId, "REJECTED", decision, resolveActor(httpRequest));
		log.info("Administrator rejected MCP tool invocation for token ID '{}'", tokenId);

		ObjectNode response = objectMapper.createObjectNode();
		response.put("status", "REJECTED");
		response.put("tokenId", tokenId);
		response.put("message", "Tool invocation rejected and purged.");

		return ResponseEntity.ok(response.toString());
	}

	/**
	 * Records a human decision for the audit trail without touching the resumption handshake.
	 * The actor is always the authenticated admin identity, never the client-supplied claim.
	 *
	 * @param tokenId  suspended invocation id
	 * @param status   {@code APPROVED} or {@code REJECTED}
	 * @param decision optional reason context, possibly {@code null}
	 * @param decidedBy authenticated actor id, or {@code master-key} for master-key callers
	 */
	private void recordDecision(String tokenId, String status, DecisionRequest decision, String decidedBy) {
		String reason = decision != null && decision.reason() != null ? decision.reason() : "";
		try {
			ObjectNode entry = objectMapper.createObjectNode();
			entry.put("status", status);
			entry.put("reason", reason);
			entry.put("decidedBy", decidedBy);
			redisTemplate.opsForValue().set(
					REDIS_DECISION_PREFIX + tokenId,
					objectMapper.writeValueAsString(entry),
					DECISION_TTL_SECONDS, TimeUnit.SECONDS);
		} catch (Exception e) {
			log.debug("Skipping HITL decision record for token ID '{}': {}", tokenId, e.getMessage());
		}
		log.info("HITL {} for token ID '{}' by '{}': {}", status, tokenId, decidedBy, reason);
	}

	/**
	 * Resolves the authenticated actor for the audit trail from the admin identity attribute.
	 * Master-key callers carry no account, so they attribute to the shared master key explicitly
	 * rather than to any client-supplied name.
	 *
	 * @param httpRequest current request
	 * @return admin account id string, or {@code master-key} when unattributed
	 */
	private static String resolveActor(HttpServletRequest httpRequest) {
		Object adminId = httpRequest != null ? httpRequest.getAttribute(AdminAuthFilter.ATTRIBUTE_ADMIN_ID) : null;
		return adminId != null ? adminId.toString() : "master-key";
	}
}
