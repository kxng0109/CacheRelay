package io.github.kxng0109.cacherelay.mcp.contracts;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Standard JSON-RPC 2.0 error object with Model Context Protocol (MCP) partitioned error codes.
 *
 * @param code    numeric error code
 * @param message human-readable error summary
 * @param data    optional structured error payload
 */
public record McpJsonRpcError(
		int code,
		String message,
		@Nullable JsonNode data
) {
	public static final int PARSE_ERROR = -32700;
	public static final int INVALID_REQUEST = -32600;
	public static final int METHOD_NOT_FOUND = -32601;
	public static final int INVALID_PARAMS = -32602;
	public static final int INTERNAL_ERROR = -32603;

	// MCP-spec-defined codes in the -32020..-32099 sub-range (MCP 2026-07-28 schema.ts, basic spec).
	// ONLY these three codes may be emitted from this sub-range. Implementations MUST NOT emit any
	// other code from -32020..-32099. Local implementation errors (RBAC denial, circuit-breaker state)
	// are surfaced as INTERNAL_ERROR (-32603), never as reserved-range codes.
	public static final int HEADER_MISMATCH = -32020;
	public static final int MISSING_REQUIRED_CAPABILITY = -32021;
	public static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

	/**
	 * A2A protocol version unsupported by the selected interface (A2A spec section 3.6.2:
	 * agents return {@code VersionNotSupportedError}, JSON-RPC {@code -32009} / HTTP 400).
	 */
	public static final int VERSION_NOT_SUPPORTED = -32009;

	public static McpJsonRpcError parseError(String detail) {
		return new McpJsonRpcError(PARSE_ERROR, "Parse error: " + detail, null);
	}

	public static McpJsonRpcError invalidRequest(String detail) {
		return new McpJsonRpcError(INVALID_REQUEST, "Invalid Request: " + detail, null);
	}

	public static McpJsonRpcError methodNotFound(String method) {
		return new McpJsonRpcError(METHOD_NOT_FOUND, "Method not found: " + method, null);
	}

	public static McpJsonRpcError invalidParams(String detail) {
		return new McpJsonRpcError(INVALID_PARAMS, "Invalid params: " + detail, null);
	}

	public static McpJsonRpcError internalError(String detail) {
		return new McpJsonRpcError(INTERNAL_ERROR, "Internal error: " + detail, null);
	}

	/**
	 * Tool-execution failure with a correlation id (MCP-B19): the client-facing message is generic and
	 * carries only the id in {@code data}, so upstream exception text (paths, credentials, socket
	 * detail) never reaches the client; the id ties the client report to the server log line.
	 *
	 * @param detail        generic failure summary, never exception text
	 * @param correlationId server-log correlation id
	 * @param mapper        mapper for the {@code data} node
	 * @return the client-safe error
	 */
	public static McpJsonRpcError internalError(String detail, String correlationId, ObjectMapper mapper) {
		ObjectNode dataNode = mapper.createObjectNode();
		dataNode.put("correlationId", correlationId);
		return new McpJsonRpcError(INTERNAL_ERROR, "Internal error: " + detail, dataNode);
	}

	/**
	 * RBAC policy denial. Surfaced as {@code INTERNAL_ERROR} (-32603) because per-request tool authorization is a local
	 * policy decision, not a protocol error; the MCP spec forbids emitting undefined codes from the -32020..-32099
	 * sub-range.
	 */
	public static McpJsonRpcError accessDenied(String detail) {
		return internalError("Access denied: " + detail);
	}

	/**
	 * Upstream circuit-breaker open. Surfaced as {@code INTERNAL_ERROR} (-32603) because breaker state is a local
	 * implementation error, not a protocol error; the MCP spec forbids emitting undefined codes from the -32020..-32099
	 * sub-range.
	 */
	public static McpJsonRpcError circuitBreakerTripped(String serverName) {
		return internalError("Upstream MCP server '" + serverName
				                     + "' is temporarily unavailable (circuit breaker open)");
	}

	/**
	 * Per-key request rate exceeded. Surfaced as {@code INTERNAL_ERROR} (-32603) because
	 * throttling is a local implementation decision, not a protocol error; the MCP spec
	 * forbids emitting undefined codes from the -32020..-32099 sub-range. Callers should
	 * also honor the accompanying HTTP {@code 429} status and {@code Retry-After} header.
	 *
	 * @param retryAfterSeconds seconds to wait before retrying
	 */
	public static McpJsonRpcError rateLimited(long retryAfterSeconds) {
		return internalError("Rate limit exceeded: too many tool calls; retry after "
				+ Math.max(1, retryAfterSeconds) + "s");
	}

	/**
	 * Tool output blocked by the egress injection policy. Surfaced as {@code INTERNAL_ERROR}
	 * (-32603): blocking is a local implementation decision, and the MCP spec forbids emitting
	 * undefined codes from the -32020..-32099 sub-range. The offending output is never returned
	 * and no {@code data} payload is attached (data-free failure).
	 *
	 * <p>Code decision (FS-05): JSON-RPC reserves -32000..-32099 for implementation-defined
	 * server errors, so a dedicated code would be legal — but the gateway standardizes every
	 * local policy decision (RBAC denial, breaker state, throttling, egress block) on
	 * {@code INTERNAL_ERROR} so clients never branch on codes. This is stable and documented.</p>
	 *
	 * @param toolName namespaced tool whose output was blocked
	 */
	public static McpJsonRpcError policyBlocked(String toolName) {
		return internalError("Tool output blocked by egress policy: indirect prompt injection "
				+ "markers detected in '" + toolName + "'");
	}

	/**
	 * Replay of an already-consumed HITL approval. Surfaced as {@code INTERNAL_ERROR}
	 * (-32603): the single-use claim already executed this exact call, and replaying it must
	 * never re-enter the suspension cycle (a second administrator approval would otherwise
	 * execute the tool twice). Clients re-initiate the tool call if they still need it.
	 */
	public static McpJsonRpcError resumptionConsumed() {
		return internalError("Resumption already consumed: this approval executed its call; "
				+ "re-initiate the tool call to request a new approval");
	}

	/**
	 * Resumption of an explicitly rejected HITL approval. Surfaced as {@code INTERNAL_ERROR}
	 * (-32603): the rejection is terminal for the token's TTL, so retrying it must never
	 * re-enter the suspension cycle (a later approval could otherwise execute a call an
	 * administrator already refused). Clients re-initiate the tool call if they still need it.
	 */
	public static McpJsonRpcError resumptionRejected() {
		return internalError("Resumption rejected: an administrator refused this call; "
				+ "re-initiate the tool call to request a new approval");
	}

	public static McpJsonRpcError headerMismatch(String detail) {
		return new McpJsonRpcError(HEADER_MISMATCH, "Header mismatch: " + detail, null);
	}

	/**
	 * Whether an upstream-supplied error code may be forwarded verbatim (MCP-B27, verified against
	 * JSON-RPC 2.0 section 5.1 and the MCP 2026-07-28 error-code partition): the standard JSON-RPC
	 * codes, the three spec-defined MCP codes, and the legacy {@code -32000..-32019} band (which MCP
	 * explicitly grandfathers as implementation-defined — forwarding an upstream code from it is not
	 * allocating a new one). Undefined codes from the spec-reserved {@code -32020..-32099} sub-range
	 * and anything outside the known set are remapped to {@code INTERNAL_ERROR} so a misbehaving
	 * upstream cannot put clients into undefined-code branches.
	 *
	 * @param code upstream error code
	 * @return true when the code is safe to forward
	 */
	public static boolean isForwardableUpstreamCode(int code) {
		if (code >= -32019 && code <= -32000) {
			return true;
		}
		return switch (code) {
			case PARSE_ERROR, INVALID_REQUEST, METHOD_NOT_FOUND, INVALID_PARAMS, INTERNAL_ERROR,
			     HEADER_MISMATCH, MISSING_REQUIRED_CAPABILITY, UNSUPPORTED_PROTOCOL_VERSION -> true;
			default -> false;
		};
	}

	public static McpJsonRpcError unsupportedVersion(String requestedVersion, ObjectMapper mapper) {
		ObjectNode dataNode = mapper.createObjectNode();
		dataNode.put("requested", requestedVersion);
		ArrayNode supportedNode = dataNode.putArray("supported");
		for (String v : McpProtocolVersion.SUPPORTED_VERSIONS_ORDERED) {
			supportedNode.add(v);
		}
		return new McpJsonRpcError(UNSUPPORTED_PROTOCOL_VERSION, "Unsupported protocol version", dataNode);
	}

	/**
	 * A2A version not supported by the selected interface (A2A spec section 3.6.2).
	 *
	 * @param detail human-readable version mismatch summary
	 * @return the {@code -32009} client error
	 */
	public static McpJsonRpcError versionNotSupported(String detail) {
		return new McpJsonRpcError(VERSION_NOT_SUPPORTED, "Version not supported: " + detail, null);
	}
}
