package io.github.kxng0109.cacherelay.a2a.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

import io.github.kxng0109.cacherelay.a2a.config.A2aAgentConfig;
import io.github.kxng0109.cacherelay.a2a.config.A2aGatewayProperties;
import io.github.kxng0109.cacherelay.a2a.registry.A2aAgentRegistry;
import io.github.kxng0109.cacherelay.a2a.resilience.A2aAgentCircuitBreakerManager;
import io.github.kxng0109.cacherelay.a2a.security.A2aRbacPolicyEngine;
import io.github.kxng0109.cacherelay.config.OpenApiConfig;
import io.github.kxng0109.cacherelay.config.SensitiveString;
import io.github.kxng0109.cacherelay.contracts.RateLimitDecision;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.mcp.contracts.McpJsonRpcError;
import io.github.kxng0109.cacherelay.mcp.contracts.McpJsonRpcResponse;
import io.github.kxng0109.cacherelay.mcp.protocol.BoundedResultBodyHandler;
import io.github.kxng0109.cacherelay.security.ratelimit.KeyManagementService;
import io.github.kxng0109.cacherelay.security.ratelimit.RateLimitEngine;
import io.github.kxng0109.cacherelay.security.ratelimit.RateLimitUnavailableException;
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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Governed JSON-RPC proxy for upstream A2A (Agent-to-Agent) agents under {@code /v1/a2a}.
 *
 * <p>Relays the interoperable core — {@code message/send}, {@code message/stream},
 * {@code tasks/get}, {@code tasks/cancel} — to operator-registered agents after
 * virtual-key authentication, per-key agent RBAC, a per-agent circuit-breaker admission
 * check, and the key's RPM budget on the cost-bearing messaging methods. Non-streaming
 * results are relayed verbatim; {@code message/stream} is relayed byte-for-byte as
 * {@code text/event-stream} (each upstream SSE {@code data:} frame is a complete JSON-RPC
 * response per A2A v0.3, so transparency preserves protocol semantics). Upstream
 * credentials are attached server-side and never logged; bodies are bounded in both
 * directions.</p>
 *
 * <p>Local policy decisions use the same JSON-RPC error convention as the MCP gateway
 * (standardized on {@code -32603} so clients never branch on codes). Mid-stream upstream
 * failures close the SSE body so clients observe truncation (no {@code final:true} event);
 * a client disconnect never counts as an upstream failure. Explicit non-goals:
 * push-notification configuration methods, task resubscription, gRPC and HTTP+JSON
 * transports, and any agent runtime or task state store — unknown methods answer
 * {@code -32601}.</p>
 */
@Slf4j
@RestController
@RequestMapping("/v1/a2a")
@Tag(name = "A2A - Agent Proxy", description = "Governed JSON-RPC proxy for registered upstream A2A agents")
public class A2aProxyController {

	private static final Set<String> SUPPORTED_METHODS =
			Set.of("message/send", "message/stream", "tasks/get", "tasks/cancel");

	private static final Set<String> COST_BEARING_METHODS =
			Set.of("message/send", "message/stream");

	private static final String HEADER_A2A_VERSION = "A2A-Version";

	private static final int STREAM_COPY_BUFFER_BYTES = 8_192;

	private final A2aGatewayProperties properties;

	private final A2aAgentRegistry agentRegistry;

	private final A2aRbacPolicyEngine rbacPolicyEngine;

	private final A2aAgentCircuitBreakerManager circuitBreakerManager;

	private final KeyManagementService keyManagementService;

	private final RateLimitEngine rateLimitEngine;

	private final ObjectMapper objectMapper;

	private final HttpClient httpClient;

	/**
	 * @param properties            A2A gateway configuration
	 * @param agentRegistry         upstream agent registry
	 * @param rbacPolicyEngine      per-key agent authorization
	 * @param circuitBreakerManager per-agent breaker admission
	 * @param keyManagementService  virtual-key resolution
	 * @param rateLimitEngine       per-key RPM budget enforcement
	 * @param objectMapper          JSON codec
	 * @param httpClient            dedicated A2A client (redirects disabled)
	 */
	public A2aProxyController(
			A2aGatewayProperties properties,
			A2aAgentRegistry agentRegistry,
			A2aRbacPolicyEngine rbacPolicyEngine,
			A2aAgentCircuitBreakerManager circuitBreakerManager,
			KeyManagementService keyManagementService,
			RateLimitEngine rateLimitEngine,
			ObjectMapper objectMapper,
			@Qualifier("a2aHttpClient") HttpClient httpClient
	) {
		this.properties = properties;
		this.agentRegistry = agentRegistry;
		this.rbacPolicyEngine = rbacPolicyEngine;
		this.circuitBreakerManager = circuitBreakerManager;
		this.keyManagementService = keyManagementService;
		this.rateLimitEngine = rateLimitEngine;
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
	 * @return JSON-RPC response body (relayed result or policy error), or an SSE stream
	 *         for {@code message/stream}
	 */
	@Operation(
			summary = "Relay an A2A JSON-RPC request",
			description = "Relays `message/send`, `message/stream`, `tasks/get`, or `tasks/cancel` to the named upstream agent after virtual-key authentication, per-key agent RBAC, RPM budget enforcement on messaging methods, and circuit-breaker admission. Non-streaming results are relayed verbatim; `message/stream` answers `text/event-stream` relayed byte-for-byte.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_BEARER_AUTH)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "JSON-RPC response or SSE stream",
					content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "202", description = "Accepted (notification)"),
			@ApiResponse(responseCode = "400", description = "Malformed JSON or unsupported request shape"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: virtual key missing or invalid"),
			@ApiResponse(responseCode = "429", description = "Virtual key RPM budget exceeded"),
			@ApiResponse(responseCode = "503", description = "A2A proxy disabled or rate limiter unavailable")
	})
	@PostMapping(value = "/{agent}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<StreamingResponseBody> relay(
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

		// Flood gate for the cost-bearing methods only: messaging dispatches upstream
		// work, so the key's RPM applies here; tasks/get and tasks/cancel are local
		// reads and stay unthrottled (MCP tools/call precedent). Denied calls burn the
		// caller's own quota, which is self-defeating for attackers.
		if (COST_BEARING_METHODS.contains(method)) {
			ResponseEntity<StreamingResponseBody> limited = enforceRateLimit(id, apiKey, httpRequest);
			if (limited != null) {
				return limited;
			}
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

		if ("message/stream".equals(method)) {
			return streamRelay(agentName, agent, rawBody, protocolVersion, id);
		}
		return jsonRelay(agentName, agent, rawBody, protocolVersion, id);
	}

	/**
	 * Relays a non-streaming JSON-RPC exchange.
	 *
	 * @param agentName       registered agent name
	 * @param agent           resolved agent configuration
	 * @param rawBody         verbatim JSON-RPC request
	 * @param protocolVersion optional caller {@code A2A-Version}
	 * @param id              request id for error shaping
	 * @return the relational response (upstream result or a policy error)
	 */
	private ResponseEntity<StreamingResponseBody> jsonRelay(
			String agentName,
			A2aAgentConfig agent,
			String rawBody,
			@Nullable String protocolVersion,
			JsonNode id
	) {
		HttpRequest.Builder builder = baseRequest(agent, rawBody, protocolVersion)
				.header("Accept", "application/json")
				.timeout(properties.getClientRequestTimeout());

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
	 * Relays a {@code message/stream} exchange as a byte-transparent SSE pass-through.
	 *
	 * <p>No {@code HttpRequest} timeout is set: JDK behavior for request timeouts on
	 * streaming bodies is implementation-dependent (headers-only vs whole-exchange), so
	 * stream lifetime is bounded by the servlet async timeout and the client connection
	 * instead. A pre-stream failure answers a normal JSON-RPC error; a mid-stream
	 * upstream failure closes the SSE body (clients detect truncation by the missing
	 * {@code final:true} event). A client disconnect closes the upstream stream and is
	 * never counted as an upstream failure.</p>
	 *
	 * @param agentName       registered agent name
	 * @param agent           resolved agent configuration
	 * @param rawBody         verbatim JSON-RPC request
	 * @param protocolVersion optional caller {@code A2A-Version}
	 * @param id              request id for pre-stream error shaping
	 * @return the SSE pass-through response
	 */
	private ResponseEntity<StreamingResponseBody> streamRelay(
			String agentName,
			A2aAgentConfig agent,
			String rawBody,
			@Nullable String protocolVersion,
			JsonNode id
	) {
		HttpRequest request = baseRequest(agent, rawBody, protocolVersion)
				.header("Accept", "text/event-stream")
				.POST(HttpRequest.BodyPublishers.ofString(rawBody))
				.build();

		HttpResponse<InputStream> upstream;
		try {
			upstream = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
		} catch (Exception e) {
			circuitBreakerManager.recordFailure(agentName);
			log.error("A2A stream to agent '{}' failed before headers: {}",
					agentName, e.getClass().getSimpleName());
			return jsonRpc(HttpStatus.OK,
					McpJsonRpcResponse.failure(id,
							McpJsonRpcError.internalError("Agent '" + agentName + "' is unavailable")));
		}

		String contentType = upstream.headers().firstValue("Content-Type").orElse("");
		if (upstream.statusCode() < 200 || upstream.statusCode() >= 300
				|| !contentType.contains("text/event-stream")) {
			// Pre-stream failure (or a non-SSE body): buffer it bounded and answer a
			// normal JSON-RPC response.
			try (InputStream body = upstream.body()) {
				String buffered = readBounded(body, properties.getMaxResultBytes());
				if (upstream.statusCode() >= 200 && upstream.statusCode() < 300) {
					circuitBreakerManager.recordSuccess(agentName);
					return relayJsonObject(id, buffered);
				}
				circuitBreakerManager.recordFailure(agentName);
				log.warn("A2A stream to agent '{}' pre-failed with HTTP {}", agentName, upstream.statusCode());
				return upstreamError(id, upstream.statusCode(), buffered);
			} catch (IOException e) {
				circuitBreakerManager.recordFailure(agentName);
				return jsonRpc(HttpStatus.OK,
						McpJsonRpcResponse.failure(id,
								McpJsonRpcError.internalError("Agent '" + agentName + "' is unavailable")));
			}
		}

		StreamingResponseBody stream = out -> copyStream(agentName, upstream.body(), out);
		return ResponseEntity.ok()
				.contentType(MediaType.TEXT_EVENT_STREAM)
				.header("Cache-Control", "no-cache")
				.header("X-Accel-Buffering", "no")
				.body(stream);
	}

	/**
	 * Copies the upstream SSE body to the client with per-chunk flush and a byte cap.
	 *
	 * <p>Read failures are upstream faults (breaker failure); write failures mean the
	 * client is gone (no breaker penalty). The upstream stream is always closed.</p>
	 */
	private void copyStream(String agentName, InputStream upstream, OutputStream out) {
		byte[] buffer = new byte[STREAM_COPY_BUFFER_BYTES];
		long total = 0;
		try (upstream) {
			while (true) {
				int read;
				try {
					read = upstream.read(buffer);
				} catch (IOException upstreamFailure) {
					circuitBreakerManager.recordFailure(agentName);
					log.warn("A2A stream from agent '{}' failed mid-stream: {}",
							agentName, upstreamFailure.getClass().getSimpleName());
					return;
				}
				if (read == -1) {
					circuitBreakerManager.recordSuccess(agentName);
					return;
				}
				total += read;
				if (total > properties.getMaxResultBytes()) {
					circuitBreakerManager.recordFailure(agentName);
					log.warn("A2A stream from agent '{}' exceeded {} bytes; closing stream",
							agentName, properties.getMaxResultBytes());
					return;
				}
				try {
					out.write(buffer, 0, read);
					out.flush();
				} catch (IOException clientGone) {
					// Downstream disconnect: close upstream (try-with-resources) and
					// leave breaker health untouched.
					return;
				}
			}
		} catch (IOException ignored) {
			// close() failed; nothing further to do.
		}
	}

	private HttpRequest.Builder baseRequest(
			A2aAgentConfig agent,
			String rawBody,
			@Nullable String protocolVersion
	) {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(agent.baseUrl())
				.header("Content-Type", "application/json")
				.header(HEADER_A2A_VERSION, protocolVersion != null && !protocolVersion.isBlank()
						? protocolVersion
						: agent.protocolVersionOrDefault());
		SensitiveString upstreamKey = agent.apiKey();
		if (upstreamKey != null && upstreamKey.value() != null && !upstreamKey.value().isBlank()) {
			builder.header("Authorization", "Bearer " + upstreamKey.value());
		}
		return builder;
	}

	/**
	 * Enforces the key's RPM budget on A2A messaging methods (TPM untouched by contract).
	 *
	 * @param id          request id for error shaping
	 * @param apiKey      authenticated key carrying the limits
	 * @param httpRequest current request (Bearer re-hash, no I/O)
	 * @return a 429/503 response on rejection or outage, or {@code null} to proceed
	 */
	private @Nullable ResponseEntity<StreamingResponseBody> enforceRateLimit(
			JsonNode id,
			VirtualApiKey apiKey,
			HttpServletRequest httpRequest
	) {
		SHA256Hash keyHash = keyHashOf(httpRequest);
		if (keyHash == null) {
			// Attribute-attributed keys carry no presented secret to hash; production
			// always resolves via Bearer, so this only triggers in test scaffolding.
			return null;
		}
		RateLimitDecision decision;
		try {
			decision = rateLimitEngine.checkRequestRate(keyHash, apiKey);
		} catch (RateLimitUnavailableException e) {
			log.warn("A2A rate limiter unavailable; failing closed");
			return jsonRpc(HttpStatus.SERVICE_UNAVAILABLE,
					McpJsonRpcResponse.failure(id,
							McpJsonRpcError.internalError("Rate limiter unavailable")));
		}
		if (decision instanceof RateLimitDecision.Rejected rejected) {
			log.warn("A2A rate limit exceeded for tenant '{}'", apiKey.ownerId());
			String message = "Rate limit exceeded: too many agent calls; retry after "
					+ Math.max(1, rejected.retryAfterSeconds()) + "s";
			return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
					.contentType(MediaType.APPLICATION_JSON)
					.header("Retry-After", String.valueOf(Math.max(1, rejected.retryAfterSeconds())))
					.body(byteBody(McpJsonRpcResponse
							.failure(id, McpJsonRpcError.internalError(message))
							.toJsonNode(objectMapper).toString()));
		}
		return null;
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

	private ResponseEntity<StreamingResponseBody> relayJsonObject(JsonNode id, @Nullable String body) {
		if (body == null) {
			return jsonRpc(HttpStatus.OK,
					McpJsonRpcResponse.failure(id,
							McpJsonRpcError.internalError("Agent returned an empty response")));
		}
		try {
			objectMapper.readTree(body);
		} catch (Exception e) {
			return jsonRpc(HttpStatus.OK,
					McpJsonRpcResponse.failure(id,
							McpJsonRpcError.internalError("Agent returned a non-JSON response")));
		}
		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_JSON)
				.body(byteBody(body));
	}

	private ResponseEntity<StreamingResponseBody> upstreamError(JsonNode id, int statusCode, @Nullable String body) {
		if (body != null && !body.isBlank()) {
			try {
				JsonNode parsed = objectMapper.readTree(body);
				if (parsed != null && parsed.has("error")) {
					// Relay the upstream JSON-RPC error verbatim so the client sees the
					// agent's own code and message.
					return ResponseEntity.ok()
							.contentType(MediaType.APPLICATION_JSON)
							.body(byteBody(body));
				}
			} catch (Exception ignored) {
				// Fall through to the generic transport error.
			}
		}
		return jsonRpc(HttpStatus.OK,
				McpJsonRpcResponse.failure(id,
						McpJsonRpcError.internalError("Agent returned HTTP " + statusCode)));
	}

	/**
	 * Reads at most {@code maxBytes} from a stream for bounded pre-stream error bodies.
	 *
	 * @param in       source stream
	 * @param maxBytes byte cap
	 * @return decoded UTF-8 body, truncated at the cap
	 * @throws IOException when the stream cannot be read
	 */
	private static String readBounded(InputStream in, int maxBytes) throws IOException {
		byte[] buffer = new byte[STREAM_COPY_BUFFER_BYTES];
		ByteArrayOutputStream collected = new ByteArrayOutputStream();
		int total = 0;
		int read;
		while (total < maxBytes && (read = in.read(buffer)) != -1) {
			int writable = Math.min(read, maxBytes - total);
			collected.write(buffer, 0, writable);
			total += writable;
		}
		return collected.toString(StandardCharsets.UTF_8);
	}

	private static StreamingResponseBody byteBody(String body) {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		return out -> out.write(bytes);
	}

	private ResponseEntity<StreamingResponseBody> jsonRpc(HttpStatus status, McpJsonRpcResponse response) {
		return ResponseEntity.status(status)
				.contentType(MediaType.APPLICATION_JSON)
				.body(byteBody(response.toJsonNode(objectMapper).toString()));
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

	private @Nullable SHA256Hash keyHashOf(HttpServletRequest request) {
		String authHeader = request.getHeader("Authorization");
		if (authHeader != null && authHeader.startsWith("Bearer ")) {
			String token = authHeader.substring(7).trim();
			if (!token.isBlank()) {
				return SHA256Hash.fromRawKey(token);
			}
		}
		return null;
	}
}
