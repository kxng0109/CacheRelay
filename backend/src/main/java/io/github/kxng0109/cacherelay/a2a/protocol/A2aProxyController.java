package io.github.kxng0109.cacherelay.a2a.protocol;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.Set;

import io.github.kxng0109.cacherelay.a2a.config.A2aAgentConfig;
import io.github.kxng0109.cacherelay.a2a.config.A2aGatewayProperties;
import io.github.kxng0109.cacherelay.a2a.registry.A2aAgentRegistry;
import io.github.kxng0109.cacherelay.a2a.resilience.A2aAgentCircuitBreakerManager;
import io.github.kxng0109.cacherelay.a2a.security.A2aRbacPolicyEngine;
import io.github.kxng0109.cacherelay.config.OpenApiConfig;
import io.github.kxng0109.cacherelay.config.SensitiveString;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.mcp.contracts.McpJsonRpcError;
import io.github.kxng0109.cacherelay.mcp.contracts.McpJsonRpcResponse;
import io.github.kxng0109.cacherelay.mcp.protocol.BoundedResultBodyHandler;
import io.github.kxng0109.cacherelay.security.ratelimit.KeyManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Governed JSON-RPC proxy for upstream A2A (Agent-to-Agent) agents under {@code /v1/a2a}.
 *
 * <p>Slice 1 relays the non-streaming interoperable core ({@code message/send},
 * {@code tasks/get}, {@code tasks/cancel}) verbatim, after virtual-key authentication,
 * per-key agent RBAC, and a per-agent circuit-breaker admission check. Bodies are bounded
 * on both directions; upstream credentials are attached server-side and never logged.
 * Local policy decisions use the same JSON-RPC error convention as the MCP gateway
 * (standardized on {@code -32603} so clients never branch on codes).</p>
 *
 * <p>Explicit non-goals for this slice: {@code message/stream} SSE relay, push-notification
 * configuration methods, task resubscription, gRPC and HTTP+JSON transports, and any agent
 * runtime or task state store — unknown methods answer {@code -32601}.</p>
 */
@Slf4j
@RestController
@RequestMapping("/v1/a2a")
@Tag(name = "A2A - Agent Proxy", description = "Governed JSON-RPC proxy for registered upstream A2A agents")
public class A2aProxyController {

	private static final Set<String> SUPPORTED_METHODS =
			Set.of("message/send", "tasks/get", "tasks/cancel");

	private static final String HEADER_A2A_VERSION = "A2A-Version";

	private final A2aGatewayProperties properties;

	private final A2aAgentRegistry agentRegistry;

	private final A2aRbacPolicyEngine rbacPolicyEngine;

	private final A2aAgentCircuitBreakerManager circuitBreakerManager;

	private final KeyManagementService keyManagementService;

	private final ObjectMapper objectMapper;

	private final HttpClient httpClient;

	/**
	 * @param properties            A2A gateway configuration
	 * @param agentRegistry         upstream agent registry
	 * @param rbacPolicyEngine      per-key agent authorization
	 * @param circuitBreakerManager per-agent breaker admission
	 * @param keyManagementService  virtual-key resolution
	 * @param objectMapper          JSON codec
	 * @param httpClient            dedicated A2A client (redirects disabled)
	 */
	public A2aProxyController(
			A2aGatewayProperties properties,
			A2aAgentRegistry agentRegistry,
			A2aRbacPolicyEngine rbacPolicyEngine,
			A2aAgentCircuitBreakerManager circuitBreakerManager,
			KeyManagementService keyManagementService,
			ObjectMapper objectMapper,
			@Qualifier("a2aHttpClient") HttpClient httpClient
	) {
		this.properties = properties;
		this.agentRegistry = agentRegistry;
		this.rbacPolicyEngine = rbacPolicyEngine;
		this.circuitBreakerManager = circuitBreakerManager;
		this.keyManagementService = keyManagementService;
		this.objectMapper = objectMapper;
		this.httpClient = httpClient;
	}

	/**
	 * Relays one JSON-RPC request to a registered upstream agent.
	 *
	 * @param agentName       registered agent name
	 * @param rawBody         verbatim JSON-RPC request
	 * @param protocolVersion optional caller {@code A2A-Version}
	 * @param httpRequest     servlet request for credential resolution
	 * @return JSON-RPC response (relayed upstream result or a local policy error)
	 */
	@Operation(
			summary = "Relay an A2A JSON-RPC request",
			description = "Relays `message/send`, `tasks/get`, or `tasks/cancel` to the named upstream agent after virtual-key authentication, per-key agent RBAC, and circuit-breaker admission. Results are relayed verbatim.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_BEARER_AUTH)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "JSON-RPC response (upstream result or policy error)",
					content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "202", description = "Accepted (notification)"),
			@ApiResponse(responseCode = "400", description = "Malformed JSON or unsupported request shape"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: virtual key missing or invalid"),
			@ApiResponse(responseCode = "503", description = "A2A proxy disabled")
	})
	@PostMapping(value = "/{agent}", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> relay(
			@Parameter(description = "Registered upstream agent name", example = "research-agent")
			@PathVariable("agent") String agentName,
			@RequestBody String rawBody,
			@RequestHeader(value = HEADER_A2A_VERSION, required = false) String protocolVersion,
			HttpServletRequest httpRequest
	) {
		if (!properties.isEnabled()) {
			return jsonRpc(HttpStatus.SERVICE_UNAVAILABLE,
					McpJsonRpcResponse.failure(null, McpJsonRpcError.internalError("A2A gateway is disabled")));
		}

		VirtualApiKey apiKey = resolveApiKey(httpRequest);
		if (apiKey == null || !apiKey.enabled()) {
			return jsonRpc(HttpStatus.UNAUTHORIZED,
					McpJsonRpcResponse.failure(null,
							McpJsonRpcError.internalError("Unauthorized: Invalid or disabled API key")));
		}

		long declaredLength = httpRequest.getContentLengthLong();
		if (declaredLength > properties.getMaxRequestBytes()
				|| rawBody.length() > properties.getMaxRequestBytes()) {
			return jsonRpc(HttpStatus.BAD_REQUEST,
					McpJsonRpcResponse.failure(null,
							McpJsonRpcError.invalidRequest("Request body exceeds the A2A size limit")));
		}

		JsonNode tree;
		try {
			tree = objectMapper.readTree(rawBody);
		} catch (Exception e) {
			return jsonRpc(HttpStatus.BAD_REQUEST,
					McpJsonRpcResponse.failure(null, McpJsonRpcError.parseError("Malformed JSON body")));
		}
		if (tree == null || !tree.isObject()) {
			return jsonRpc(HttpStatus.BAD_REQUEST,
					McpJsonRpcResponse.failure(null,
							McpJsonRpcError.invalidRequest("Request must be a single JSON-RPC object")));
		}

		String method = tree.path("method").asString("");
		JsonNode id = tree.has("id") ? tree.get("id") : null;

		if (method.isBlank()) {
			return jsonRpc(HttpStatus.BAD_REQUEST,
					McpJsonRpcResponse.failure(id, McpJsonRpcError.invalidRequest("Missing JSON-RPC method")));
		}
		if (!SUPPORTED_METHODS.contains(method)) {
			return jsonRpc(HttpStatus.OK,
					McpJsonRpcResponse.failure(id, McpJsonRpcError.methodNotFound(method)));
		}
		if (id == null || id.isNull()) {
			return ResponseEntity.status(HttpStatus.ACCEPTED).build();
		}

		Optional<A2aAgentConfig> resolved = agentRegistry.resolve(agentName);
		if (resolved.isEmpty() || !rbacPolicyEngine.isAgentAllowed(agentName, apiKey)) {
			log.warn("A2A request denied for agent '{}' by tenant '{}' (unknown, disabled, or RBAC)",
					agentName, apiKey.ownerId());
			return jsonRpc(HttpStatus.OK,
					McpJsonRpcResponse.failure(id,
							McpJsonRpcError.internalError("Access denied: agent not available")));
		}
		A2aAgentConfig agent = resolved.get();

		if (!circuitBreakerManager.tryAcquire(agentName)) {
			return jsonRpc(HttpStatus.OK,
					McpJsonRpcResponse.failure(id,
							McpJsonRpcError.internalError(
									"Agent '" + agentName + "' is temporarily unavailable (circuit breaker open)")));
		}

		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(agent.baseUrl())
				.timeout(properties.getClientRequestTimeout())
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.header(HEADER_A2A_VERSION, protocolVersion != null && !protocolVersion.isBlank()
						? protocolVersion
						: agent.protocolVersionOrDefault())
				.POST(HttpRequest.BodyPublishers.ofString(rawBody));

		SensitiveString upstreamKey = agent.apiKey();
		if (upstreamKey != null && upstreamKey.value() != null && !upstreamKey.value().isBlank()) {
			builder.header("Authorization", "Bearer " + upstreamKey.value());
		}

		try {
			HttpResponse<String> upstream = httpClient.send(
					builder.build(),
					new BoundedResultBodyHandler(properties.getMaxResultBytes())
			);

			if (upstream.statusCode() >= 200 && upstream.statusCode() < 300) {
				circuitBreakerManager.recordSuccess(agentName);
				return relayJsonObject(id, upstream.body());
			}

			circuitBreakerManager.recordFailure(agentName);
			log.warn("A2A agent '{}' returned HTTP {}", agentName, upstream.statusCode());
			return upstreamError(id, upstream.statusCode(), upstream.body());
		} catch (Exception e) {
			circuitBreakerManager.recordFailure(agentName);
			log.error("A2A relay to agent '{}' failed: {}", agentName, e.getClass().getSimpleName());
			return jsonRpc(HttpStatus.OK,
					McpJsonRpcResponse.failure(id,
							McpJsonRpcError.internalError("Agent '" + agentName + "' is unavailable")));
		}
	}

	/**
	 * Returns the named agent's card with its {@code url} rewritten to the gateway.
	 *
	 * @param agentName   registered agent name
	 * @param httpRequest servlet request for credential resolution
	 * @return HTTP 200 with the rewritten card, 401 without a valid key,
	 *         404 for unknown/denied agents (never enumerates), 502 when the
	 *         upstream card is unreachable
	 */
	@Operation(
			summary = "Fetch a rewritten agent card",
			description = "Fetches the upstream agent card and rewrites its URL (and any additional interface URLs) to the gateway proxy address, so clients address the gateway. Unknown and denied agents are indistinguishable (404)."
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Agent card with gateway URLs",
					content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "401", description = "Unauthorized: virtual key missing or invalid"),
			@ApiResponse(responseCode = "404", description = "Unknown, disabled, or denied agent"),
			@ApiResponse(responseCode = "502", description = "Upstream agent card unreachable")
	})
	@GetMapping(value = "/{agent}/card", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> agentCard(
			@Parameter(description = "Registered upstream agent name", example = "research-agent")
			@PathVariable("agent") String agentName,
			HttpServletRequest httpRequest
	) {
		if (!properties.isEnabled()) {
			return ResponseEntity.notFound().build();
		}
		VirtualApiKey apiKey = resolveApiKey(httpRequest);
		if (apiKey == null || !apiKey.enabled()) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		Optional<A2aAgentConfig> resolved = agentRegistry.resolve(agentName);
		if (resolved.isEmpty() || !rbacPolicyEngine.isAgentAllowed(agentName, apiKey)) {
			return ResponseEntity.notFound().build();
		}
		A2aAgentConfig agent = resolved.get();

		try {
			URI cardUri = agent.baseUrl().resolve(agent.cardPathOrDefault());
			HttpRequest.Builder builder = HttpRequest.newBuilder(cardUri)
					.timeout(properties.getClientRequestTimeout())
					.header("Accept", "application/json")
					.GET();
			SensitiveString upstreamKey = agent.apiKey();
			if (upstreamKey != null && upstreamKey.value() != null && !upstreamKey.value().isBlank()) {
				builder.header("Authorization", "Bearer " + upstreamKey.value());
			}

			HttpResponse<String> upstream = httpClient.send(
					builder.build(),
					new BoundedResultBodyHandler(properties.getMaxResultBytes())
			);
			if (upstream.statusCode() < 200 || upstream.statusCode() >= 300) {
				return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
			}

			JsonNode card = objectMapper.readTree(upstream.body());
			if (card instanceof ObjectNode objectCard) {
				String proxyUrl = proxyUrl(agentName);
				objectCard.put("url", proxyUrl);
				JsonNode additionalInterfaces = objectCard.get("additionalInterfaces");
				if (additionalInterfaces != null && additionalInterfaces.isArray()) {
					for (JsonNode entry : additionalInterfaces) {
						if (entry instanceof ObjectNode objectEntry) {
							objectEntry.put("url", proxyUrl);
						}
					}
				}
				return ResponseEntity.ok()
						.contentType(MediaType.APPLICATION_JSON)
						.body(objectCard.toString());
			}
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
		} catch (Exception e) {
			log.warn("A2A card fetch for agent '{}' failed: {}", agentName, e.getClass().getSimpleName());
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
		}
	}

	private ResponseEntity<String> relayJsonObject(JsonNode id, String body) {
		try {
			objectMapper.readTree(body);
		} catch (Exception e) {
			return jsonRpc(HttpStatus.OK,
					McpJsonRpcResponse.failure(id,
							McpJsonRpcError.internalError("Agent returned a non-JSON response")));
		}
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
	}

	private ResponseEntity<String> upstreamError(JsonNode id, int statusCode, @Nullable String body) {
		if (body != null && !body.isBlank()) {
			try {
				JsonNode parsed = objectMapper.readTree(body);
				if (parsed != null && parsed.has("error")) {
					// Relay the upstream JSON-RPC error verbatim so the client sees the
					// agent's own code and message.
					return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
				}
			} catch (Exception ignored) {
				// Fall through to the generic transport error.
			}
		}
		return jsonRpc(HttpStatus.OK,
				McpJsonRpcResponse.failure(id,
						McpJsonRpcError.internalError("Agent returned HTTP " + statusCode)));
	}

	private ResponseEntity<String> jsonRpc(HttpStatus status, McpJsonRpcResponse response) {
		return ResponseEntity.status(status)
				.contentType(MediaType.APPLICATION_JSON)
				.body(response.toJsonNode(objectMapper).toString());
	}

	private String proxyUrl(String agentName) {
		return trimTrailingSlash(properties.getPublicBaseUrl()) + "/v1/a2a/" + agentName;
	}

	private static String trimTrailingSlash(String base) {
		if (base == null) {
			return "";
		}
		return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
	}

	private @Nullable VirtualApiKey resolveApiKey(HttpServletRequest request) {
		Object attr = request.getAttribute("virtualApiKey");
		if (attr instanceof VirtualApiKey key) {
			return key;
		}
		String authHeader = request.getHeader("Authorization");
		if (authHeader != null && authHeader.startsWith("Bearer ")) {
			String token = authHeader.substring(7).trim();
			if (!token.isBlank()) {
				SHA256Hash hash = SHA256Hash.fromRawKey(token);
				return keyManagementService.findByHash(hash).orElse(null);
			}
		}
		return null;
	}
}
