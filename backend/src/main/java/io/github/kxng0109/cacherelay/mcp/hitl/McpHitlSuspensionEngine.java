package io.github.kxng0109.cacherelay.mcp.hitl;

import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.mcp.config.McpGatewayProperties;
import io.github.kxng0109.cacherelay.mcp.contracts.McpJsonRpcError;
import io.github.kxng0109.cacherelay.mcp.contracts.McpJsonRpcRequest;
import io.github.kxng0109.cacherelay.mcp.contracts.McpJsonRpcResponse;
import io.github.kxng0109.cacherelay.mcp.contracts.McpServerConfig;
import io.github.kxng0109.cacherelay.mcp.security.McpToolRbacPolicyEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Multi Round-Trip Request (MRTR, SEP-2322) Human-in-the-Loop (HITL) suspension and resumption engine.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpHitlSuspensionEngine {

	private static final String REDIS_PENDING_PREFIX = "mcp:hitl:pending:";
	private static final String REDIS_APPROVED_PREFIX = "mcp:hitl:approved:";
	private static final String REDIS_CONSUMED_PREFIX = "mcp:hitl:consumed:";

	/**
	 * Atomically claims a single-use HITL approval (SEC-02) and records a consumption
	 * marker in the same script. The consumed marker is checked FIRST and is terminal for
	 * its TTL, so even a re-armed approval key can never execute the same call twice:
	 * <ul>
	 *   <li>{@code -1} — the approval was already consumed: the call executed earlier, so a
	 *       replay is denied (never re-suspended) regardless of the approved/pending keys.</li>
	 *   <li>{@code 1} — the caller observed {@code APPROVED}; the approval and pending keys
	 *       are deleted and the consumed marker is set with the suspension TTL. Exactly one
	 *       concurrent resumption can win.</li>
	 *   <li>{@code 0} — not approved (never approved, rejected, or the approval window
	 *       elapsed); the call is re-suspended under its stable token id.</li>
	 * </ul>
	 */
	private static final DefaultRedisScript<Long> CLAIM_APPROVAL_SCRIPT = new DefaultRedisScript<>(
			"if redis.call('EXISTS', KEYS[3]) == 1 then return -1 "
					+ "elseif redis.call('GET', KEYS[1]) == ARGV[1] then "
					+ "redis.call('DEL', KEYS[1], KEYS[2]) "
					+ "redis.call('SET', KEYS[3], '1', 'EX', ARGV[2]) "
					+ "return 1 "
					+ "else return 0 end",
			Long.class);

	private final McpGatewayProperties properties;
	private final McpAeadResumptionTokenService tokenService;
	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;

	/**
	 * Checks whether this tool requires Human-in-the-Loop approval per server configuration.
	 */
	public boolean isHitlRequired(McpServerConfig serverConfig, String rawToolName, String namespacedToolName) {
		Set<String> hitlTools = serverConfig.hitlRequiredTools();
		if (hitlTools == null || hitlTools.isEmpty()) {
			return false;
		}
		for (String pattern : hitlTools) {
			if (McpToolRbacPolicyEngine.matchesPattern(rawToolName, pattern)
					|| McpToolRbacPolicyEngine.matchesPattern(namespacedToolName, pattern)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Evaluates an incoming tool invocation under HITL policies.
	 *
	 * <p>Approval consumption is atomic (SEC-02): the approval value is claimed with
	 * {@link #CLAIM_APPROVAL_SCRIPT}, so N concurrent resumptions of the same single-use
	 * token yield exactly one execution. A missing or non-approved value denies (the call
	 * re-suspends) and never executes.</p>
	 *
	 * @param request            incoming JSON-RPC request
	 * @param serverConfig       target server configuration
	 * @param rawToolName        un-prefixed tool name
	 * @param namespacedToolName federated tool name
	 * @param apiKey             authenticated virtual key
	 * @return optional suspension response if execution is paused; empty if cleared for execution
	 */
	public Optional<McpJsonRpcResponse> evaluateOrSuspend(
			McpJsonRpcRequest request,
			McpServerConfig serverConfig,
			String rawToolName,
			String namespacedToolName,
			VirtualApiKey apiKey
	) {
		if (!isHitlRequired(serverConfig, rawToolName, namespacedToolName)) {
			return Optional.empty();
		}

		JsonNode params = request.params();
		JsonNode args = params != null ? params.path("arguments") : null;
		String serializedArgs = args != null && !args.isMissingNode() ? args.toString() : "{}";
		String currentArgsSha256 = McpAeadResumptionTokenService.computeArgsSha256(serializedArgs);

		String resumptionToken = extractResumptionToken(request);

		if (resumptionToken != null) {
			Optional<McpResumptionClaims> verifiedClaims = tokenService.verifyAndExtract(
					resumptionToken,
					currentArgsSha256,
					apiKey.ownerId()
			);

			if (verifiedClaims.isPresent()) {
				McpResumptionClaims claims = verifiedClaims.get();
				Long claimed = redisTemplate.execute(
						CLAIM_APPROVAL_SCRIPT,
						List.of(
								REDIS_APPROVED_PREFIX + claims.tokenId(),
								REDIS_PENDING_PREFIX + claims.tokenId(),
								REDIS_CONSUMED_PREFIX + claims.tokenId()
						),
						"APPROVED",
						Long.toString(properties.getHitlSuspensionTtl().toSeconds())
				);

				if (Long.valueOf(1L).equals(claimed)) {
					log.info(
							"HITL approval verified for token '{}' on tool '{}'",
							claims.tokenId(),
							namespacedToolName
					);
					return Optional.empty(); // Cleared to execute!
				}

				if (Long.valueOf(-1L).equals(claimed)) {
					// The approval was already consumed: this exact call executed once.
					// Replaying it must never re-enter the suspension cycle (that would let
					// a second administrator approval execute the tool twice).
					log.warn(
							"HITL replay denied for consumed token '{}' on tool '{}'",
							claims.tokenId(),
							namespacedToolName
					);
					return Optional.of(McpJsonRpcResponse.failure(
							request.id(), McpJsonRpcError.resumptionConsumed()));
				}

				// Authentic but not approved yet (or rejected): re-suspend under the SAME
				// token id so the invocation keeps exactly one pending entry, approvals stay
				// bound to the id the client holds, and retries cannot litter Redis.
				return Optional.of(suspend(
						request,
						serverConfig,
						namespacedToolName,
						apiKey,
						serializedArgs,
						currentArgsSha256,
						claims.tokenId(),
						claims.issuedAt()
				));
			}
		}

		// First suspension of this call (or an unverifiable/expired token): mint a
		// fresh identity.
		return Optional.of(suspend(
				request,
				serverConfig,
				namespacedToolName,
				apiKey,
				serializedArgs,
				currentArgsSha256,
				UUID.randomUUID().toString().replace("-", ""),
				Instant.now()
		));
	}

	/**
	 * Suspends a privileged call under the given token id.
	 *
	 * <p>The token id is stable across retries of the same verified call; the minted AEAD
	 * token always gets a fresh ciphertext and refreshed expiry. The pending metadata keeps
	 * the original {@code createdAt} so the admin queue reflects the true wait time.</p>
	 */
	private McpJsonRpcResponse suspend(
			McpJsonRpcRequest request,
			McpServerConfig serverConfig,
			String namespacedToolName,
			VirtualApiKey apiKey,
			String serializedArgs,
			String currentArgsSha256,
			String tokenId,
			Instant createdAt
	) {
		Instant now = Instant.now();
		Instant expiresAt = now.plus(properties.getHitlSuspensionTtl());

		McpResumptionClaims newClaims = new McpResumptionClaims(
				tokenId,
				apiKey.ownerId(),
				namespacedToolName,
				currentArgsSha256,
				now,
				expiresAt
		);

		String mintedToken = tokenService.mintToken(newClaims);

		// Store pending invocation metadata in Redis for admin review UI
		try {
			ObjectNode pendingMeta = objectMapper.createObjectNode();
			pendingMeta.put("tokenId", tokenId);
			pendingMeta.put("ownerId", apiKey.ownerId());
			pendingMeta.put("keyName", apiKey.name());
			pendingMeta.put("toolName", namespacedToolName);
			pendingMeta.put("serverName", serverConfig.name());
			pendingMeta.put("args", serializedArgs);
			pendingMeta.put("createdAt", createdAt.toString());
			pendingMeta.put("expiresAt", expiresAt.toString());

			long ttlSeconds = properties.getHitlSuspensionTtl().toSeconds();
			redisTemplate.opsForValue().set(
					REDIS_PENDING_PREFIX + tokenId,
					objectMapper.writeValueAsString(pendingMeta),
					ttlSeconds,
					TimeUnit.SECONDS
			);
		} catch (Exception e) {
			log.warn("Failed to persist pending HITL metadata in Redis: {}", e.getMessage());
		}

		// Construct standard MCP InputRequiredResult (MRTR pattern)
		ObjectNode resultNode = objectMapper.createObjectNode();
		resultNode.put("resultType", "input_required");
		resultNode.put("requestState", mintedToken);

		ObjectNode inputRequests = resultNode.putObject("inputRequests");
		ObjectNode hitlRequest = inputRequests.putObject("human_approval");
		hitlRequest.put("method", "elicitation/create");

		ObjectNode hitlParams = hitlRequest.putObject("params");
		hitlParams.put("mode", "url");
		hitlParams.put("url", "/v1/admin/mcp/approvals/" + tokenId);
		hitlParams.put(
				"message",
				"Execution of privileged tool '" + namespacedToolName + "' requires administrator approval."
		);

		return McpJsonRpcResponse.success(request.id(), resultNode);
	}

	private @Nullable String extractResumptionToken(McpJsonRpcRequest request) {
		JsonNode params = request.params();
		if (params == null) {
			return null;
		}
		if (params.has("requestState") && params.path("requestState").isString()) {
			return params.path("requestState").asString();
		}
		JsonNode meta = params.path("_meta");
		if (meta.has("requestState") && meta.path("requestState").isString()) {
			return meta.path("requestState").asString();
		}
		return null;
	}
}
