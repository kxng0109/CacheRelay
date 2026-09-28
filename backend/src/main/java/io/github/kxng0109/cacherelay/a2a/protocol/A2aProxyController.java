package io.github.kxng0109.cacherelay.a2a.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
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
import io.github.kxng0109.cacherelay.a2a.security.A2aTaskOwnershipIndex;
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

	/**
	 * Methods gated by the key's RPM budget (A2A-B04): messaging dispatches agent work, and
	 * {@code tasks/get} / {@code tasks/cancel} each dispatch an upstream call under the shared
	 * agent credential — unthrottled task polling would be free upstream work. Reads stay in the
	 * same RPM set (no separate cheap bucket exists); denial burns the caller's own quota.
	 */
	private static final Set<String> THROTTLED_METHODS =
			Set.of("message/send", "message/stream", "tasks/get", "tasks/cancel");

	private static final String HEADER_A2A_VERSION = "A2A-Version";

	private static final int STREAM_COPY_BUFFER_BYTES = 8_192;

	/**
	 * Structured SSE media-type check (A2A-B08): the spec mandates exactly
	 * {@code text/event-stream}, so detection is case-insensitive equality on the
	 * type/subtype with any {@code ;}-parameters ignored — never a substring test,
	 * which false-positives on lookalikes like
	 * {@code application/x-text-event-stream-evil}.
	 *
	 * @param contentType raw upstream {@code Content-Type}, possibly {@code null}
	 * @return true only for the SSE media type
	 */
	static boolean isSseContentType(@Nullable String contentType) {
		if (contentType == null) {
			return false;
		}
		int params = contentType.indexOf(';');
		String mediaType = (params < 0 ? contentType : contentType.substring(0, params)).trim();
		return "text/event-stream".equalsIgnoreCase(mediaType);
	}

	private final A2aGatewayProperties properties;

	private final A2aAgentRegistry agentRegistry;

	private final A2aRbacPolicyEngine rbacPolicyEngine;

	private final A2aAgentCircuitBreakerManager circuitBreakerManager;

	private final KeyManagementService keyManagementService;

	private final RateLimitEngine rateLimitEngine;

	private final ObjectMapper objectMapper;

	private final HttpClient httpClient;

	/**
	 * Task-ownership bindings created through this gateway instance (A2A-B02). Built from
	 * configuration here (not injected) so the controller stays directly constructible;
	 * the TTL and size knobs live on {@link A2aGatewayProperties}.
	 */
	private final A2aTaskOwnershipIndex taskOwnership;

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
		this.taskOwnership = new A2aTaskOwnershipIndex(
				properties.getTaskIndexMaxSize(), properties.getTaskIndexTtl());
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
		if (!keyManagementService.isUsable(apiKey)) {
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

		// Flood gate for messaging and task methods: messaging dispatches upstream
		// work, and tasks/get + tasks/cancel each dispatch an upstream call under the
		// shared agent credential, so all four burn the key's RPM budget (A2A-B04).
		// Denied calls burn the caller's own quota, which is self-defeating for attackers.
		if (THROTTLED_METHODS.contains(method)) {
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

		// A2A-B02: tasks/get and tasks/cancel run under the agent's shared credential,
		// so task ids are bearer-equivalent. Only the tenant that created the task
		// through this gateway may read or cancel it; unknown and foreign ids answer
		// the same 404 without ever reaching upstream (no enumeration oracle).
		if ("tasks/get".equals(method) || "tasks/cancel".equals(method)) {
			String taskId = tree.path("params").path("id").asString("");
			if (taskId.isBlank()) {
				return jsonRpc(HttpStatus.BAD_REQUEST,
						McpJsonRpcResponse.failure(id,
								McpJsonRpcError.invalidParams("Task id is required")));
			}
			String owner = taskOwnership.ownerOf(agentName, taskId);
			if (owner == null || !owner.equals(apiKey.ownerId())) {
				return jsonRpc(HttpStatus.NOT_FOUND,
						McpJsonRpcResponse.failure(id,
								McpJsonRpcError.internalError("Task not available")));
			}
		}

		ResponseEntity<StreamingResponseBody> versionVerdict =
				rejectUnsupportedVersion(agent, protocolVersion, id);
		if (versionVerdict != null) {
			return versionVerdict;
		}

		if (!circuitBreakerManager.tryAcquire(agentName)) {
			return jsonRpc(HttpStatus.OK,
					McpJsonRpcResponse.failure(id,
							McpJsonRpcError.internalError(
									"Agent '" + agentName + "' is temporarily unavailable (circuit breaker open)")));
		}

		if ("message/stream".equals(method)) {
			return streamRelay(agentName, agent, rawBody, protocolVersion, id, apiKey.ownerId());
		}
		return jsonRelay(agentName, agent, rawBody, protocolVersion, id, apiKey.ownerId());
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
			JsonNode id,
			@Nullable String ownerId
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
				noteTaskOwnership(agentName, ownerId, upstream.body());
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
	 * <p>Two bounds apply: the request timeout covers the headers phase (JDK semantics bound
	 * time-to-first-byte, not the stream), and an explicit stream deadline caps total lifetime
	 * (the servlet container cannot be relied on to do it). A pre-stream failure answers a normal
	 * JSON-RPC error; a mid-stream upstream failure closes the SSE body (clients detect truncation
	 * by the missing {@code final:true} event). A client disconnect closes the upstream stream and
	 * is never counted as an upstream failure.</p>
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
			JsonNode id,
			@Nullable String ownerId
	) {
		HttpRequest request = baseRequest(agent, rawBody, protocolVersion)
				.header("Accept", "text/event-stream")
				.timeout(properties.getClientRequestTimeout())
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
				|| !isSseContentType(contentType)) {
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

		PeekedStream peek = peekFirstFrame(agentName, ownerId, upstream.body());
		StreamingResponseBody stream = out -> copyStream(
				agentName, peek.stream(), out,
				System.nanoTime() + properties.getStreamMaxDuration().toNanos());
		return ResponseEntity.ok()
				.contentType(MediaType.TEXT_EVENT_STREAM)
				.header("Cache-Control", "no-cache")
				.header("X-Accel-Buffering", "no")
				.body(stream);
	}

	/**
	 * Peek window for the streaming first frame (A2A-B02): bounded so a hostile agent
	 * cannot force buffering of the whole stream before relay begins.
	 */
	private static final int STREAM_PEEK_BYTES = 65_536;

	/**
	 * Records task ownership from a buffered non-streaming upstream body (A2A-B02).
	 * Failures parse to nothing and never disturb the relay path.
	 *
	 * @param agentName upstream agent name
	 * @param ownerId   creating tenant, possibly {@code null}
	 * @param body      upstream response body, possibly {@code null}
	 */
	private void noteTaskOwnership(String agentName, @Nullable String ownerId, @Nullable String body) {
		if (body == null || body.isBlank()) {
			return;
		}
		try {
			JsonNode taskId = objectMapper.readTree(body).path("result").path("id");
			if (taskId.isString()) {
				taskOwnership.record(agentName, taskId.asString(), ownerId);
			}
		} catch (Exception ignored) {
			// Unparseable bodies carry no indexable task; the relay is unaffected.
		}
	}

	/**
	 * Peeks the first SSE data frame of a stream to index its task id (A2A-B02), returning
	 * a stream that replays the peeked bytes first so relay stays byte-transparent.
	 *
	 * @param agentName upstream agent name
	 * @param ownerId   creating tenant, possibly {@code null}
	 * @param raw       upstream byte stream (ownership transfers to the returned stream)
	 * @return peeked head plus the rejoined stream
	 */
	private PeekedStream peekFirstFrame(String agentName, @Nullable String ownerId, @Nullable InputStream raw) {
		if (raw == null) {
			// Preserve the legacy null-body behavior exactly (fail fast on first
			// read downstream); there is nothing to peek and nothing to index.
			return new PeekedStream(null);
		}
		// Read only up to the first frame boundary: blocking for a fixed byte count
		// would stall relay start on slow streams.
		ByteArrayOutputStream head = new ByteArrayOutputStream();
		try {
			int previous = -1;
			int current;
			while (head.size() < STREAM_PEEK_BYTES
					&& (current = raw.read()) != -1) {
				head.write(current);
				if (previous == '\n' && current == '\n') {
					break;
				}
				previous = current;
			}
		} catch (IOException readFailed) {
			// Peek failed: relay the untouched remainder without indexing.
			return new PeekedStream(raw);
		}
		byte[] headBytes = head.toByteArray();
		if (headBytes.length == 0) {
			// Empty stream: nothing to replay and nothing to index — hand the
			// original stream back so read/close delegation stays byte-identical.
			return new PeekedStream(raw);
		}
		String taskId = firstFrameTaskId(new String(headBytes, StandardCharsets.UTF_8));
		if (taskId != null) {
			taskOwnership.record(agentName, taskId, ownerId);
		}
		InputStream replay = new SequenceInputStream(new ByteArrayInputStream(headBytes), raw);
		return new PeekedStream(replay);
	}

	/**
	 * Extracts the task id from the first SSE data frame in a peek window.
	 *
	 * @param window peeked stream head as text
	 * @return the {@code result.id} string of the first data frame, or {@code null}
	 */
	private @Nullable String firstFrameTaskId(String window) {
		for (String line : window.split("\n")) {
			String trimmed = line.trim();
			if (!trimmed.startsWith("data:")) {
				continue;
			}
			try {
				JsonNode taskId = objectMapper.readTree(trimmed.substring(5).trim())
						.path("result").path("id");
				if (taskId.isString() && !taskId.asString().isBlank()) {
					return taskId.asString();
				}
				return null;
			} catch (Exception unparsable) {
				return null;
			}
		}
		return null;
	}

	private record PeekedStream(InputStream stream) {
	}

	/**
	 * Copies the upstream SSE body to the client with per-chunk flush, a byte cap, and a stream
	 * deadline.
	 *
	 * <p>Read failures are upstream faults (breaker failure); write failures mean the
	 * client is gone (no breaker penalty). A stalled stream past the deadline is an upstream
	 * fault too (the agent stopped producing). The upstream stream is always closed.</p>
	 */
	private void copyStream(String agentName, InputStream upstream, OutputStream out, long deadlineNanos) {
		byte[] buffer = new byte[STREAM_COPY_BUFFER_BYTES];
		long total = 0;
		try (upstream) {
			while (true) {
				if (System.nanoTime() > deadlineNanos) {
					circuitBreakerManager.recordFailure(agentName);
					log.warn("A2A stream from agent '{}' exceeded the stream deadline; closing stream",
							agentName);
					return;
				}
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
					// A2A-B01: the byte cap is a local policy stop, not an upstream
					// fault — the agent delivered bytes correctly. Close the stream
					// without recording a breaker failure so a long stream cannot
					// trip the agent breaker fleet-wide.
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
	 * Validates the caller {@code A2A-Version} against the agent's pinned version (A2A-B08,
	 * spec section 3.6: versions negotiate on {@code Major.Minor}, patch ignored; empty means
	 * 0.3 and is always accepted). A present version that is unparsable answers {@code -32602};
	 * one whose {@code Major.Minor} differs from the pin answers {@code -32009} — the agent
	 * would otherwise process the call under the wrong semantics. Absent versions fall back
	 * to the agent pin downstream and are never rejected here.
	 *
	 * @param agent           resolved agent configuration carrying the pin
	 * @param protocolVersion caller version, possibly {@code null}
	 * @param id              request id for error shaping
	 * @return a 400 response on violation, or {@code null} to proceed
	 */
	private @Nullable ResponseEntity<StreamingResponseBody> rejectUnsupportedVersion(
			A2aAgentConfig agent,
			@Nullable String protocolVersion,
			JsonNode id
	) {
		if (protocolVersion == null || protocolVersion.isBlank()) {
			return null;
		}
		int[] requested = majorMinor(protocolVersion.trim());
		if (requested == null) {
			return jsonRpc(HttpStatus.BAD_REQUEST,
					McpJsonRpcResponse.failure(id,
							McpJsonRpcError.invalidParams(
									"Invalid A2A-Version '" + protocolVersion.trim() + "'")));
		}
		int[] pinned = majorMinor(agent.protocolVersionOrDefault());
		if (pinned != null
				&& (requested[0] != pinned[0] || requested[1] != pinned[1])) {
			return jsonRpc(HttpStatus.BAD_REQUEST,
					McpJsonRpcResponse.failure(id,
							McpJsonRpcError.versionNotSupported(
									"agent '" + agent.name() + "' speaks "
											+ agent.protocolVersionOrDefault()
											+ ", caller asked for " + protocolVersion.trim())));
		}
		return null;
	}

	/**
	 * Parses a {@code Major.Minor[.patch]} version, ignoring the patch.
	 *
	 * @param version raw version string
	 * @return major/minor pair, or {@code null} when unparsable
	 */
	private static int @Nullable [] majorMinor(String version) {
		String[] parts = version.split("\\.", -1);
		if (parts.length < 2) {
			return null;
		}
		try {
			return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
		} catch (NumberFormatException notNumeric) {
			return null;
		}
	}

	/**
	 * Whether a resolved card target stays on the agent origin (A2A-B06).
	 *
	 * @param base     registered agent base URL
	 * @param resolved resolution of the configured card path against the base
	 * @return true when scheme and authority match (case-insensitive host)
	 */
	private static boolean isSameOrigin(URI base, URI resolved) {
		if (resolved.getScheme() == null || resolved.getAuthority() == null) {
			return false;
		}
		return resolved.getScheme().equalsIgnoreCase(base.getScheme())
				&& resolved.getAuthority().equalsIgnoreCase(base.getAuthority());
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
		if (!keyManagementService.isUsable(apiKey)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		// A2A-B07: the card fetch dispatches upstream work under the agent credential,
		// so it burns the key's RPM budget and honors breaker admission like every
		// other upstream dispatch. Denied calls burn the caller's own quota.
		SHA256Hash keyHash = keyHashOf(httpRequest);
		if (keyHash != null) {
			RateLimitDecision decision;
			try {
				decision = rateLimitEngine.checkRequestRate(keyHash, apiKey);
			} catch (RateLimitUnavailableException e) {
				log.warn("A2A rate limiter unavailable; failing closed");
				return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
			}
			if (decision instanceof RateLimitDecision.Rejected rejected) {
				return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
						.header("Retry-After",
								String.valueOf(Math.max(1, rejected.retryAfterSeconds())))
						.build();
			}
		}
		Optional<A2aAgentConfig> resolved = agentRegistry.resolve(agentName);
		if (resolved.isEmpty() || !rbacPolicyEngine.isAgentAllowed(agentName, apiKey)) {
			return ResponseEntity.notFound().build();
		}
		if (!circuitBreakerManager.tryAcquire(agentName)) {
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
		}
		A2aAgentConfig agent = resolved.get();

		// A2A-B06: the card path is operator configuration, but an absolute URI (or a
		// scheme-relative //host reference) would override the agent origin and carry
		// the agent bearer to a foreign host. Require a relative reference and verify
		// the resolved target stays same-origin before attaching any credential.
		URI cardUri;
		try {
			URI cardRef = new URI(agent.cardPathOrDefault());
			cardUri = agent.baseUrl().resolve(cardRef);
		} catch (Exception malformed) {
			return ResponseEntity.badRequest().build();
		}
		if (!isSameOrigin(agent.baseUrl(), cardUri)) {
			log.warn("A2A card path for agent '{}' escapes the agent origin; refusing to fetch",
					agentName);
			return ResponseEntity.badRequest().build();
		}
		try {
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
				circuitBreakerManager.recordFailure(agentName);
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
				// A2A-B03 (verified): v1.0 cards carry no top-level url — endpoints live
				// in supportedInterfaces[*].url. Rewrite those to the gateway; leave
				// provider/documentation/icon and OAuth/OIDC URLs untouched (third parties,
				// not the proxied endpoint — rewriting them would break auth).
				JsonNode supportedInterfaces = objectCard.get("supportedInterfaces");
				if (supportedInterfaces != null && supportedInterfaces.isArray()) {
					for (JsonNode entry : supportedInterfaces) {
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
