package io.github.kxng0109.cacherelay.proxy;

import io.github.kxng0109.cacherelay.budget.BudgetDecision;
import io.github.kxng0109.cacherelay.budget.BudgetEnforcer;
import io.github.kxng0109.cacherelay.budget.BudgetSettlement;
import io.github.kxng0109.cacherelay.replay.ReplayService;
import io.github.kxng0109.cacherelay.cache.contracts.CacheEntry;
import io.github.kxng0109.cacherelay.cache.contracts.CacheLookupResult;
import io.github.kxng0109.cacherelay.cache.contracts.CacheStatus;
import io.github.kxng0109.cacherelay.cache.engine.CacheRelayCacheService;
import io.github.kxng0109.cacherelay.cache.engine.streaming.CachedStreamReconstitution;
import io.github.kxng0109.cacherelay.config.OpenApiConfig;
import io.github.kxng0109.cacherelay.contracts.GatewayProperties;
import io.github.kxng0109.cacherelay.contracts.ModelAlias;
import io.github.kxng0109.cacherelay.contracts.ProviderConfig;
import io.github.kxng0109.cacherelay.contracts.ProviderRef;
import io.github.kxng0109.cacherelay.contracts.ProviderType;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.ledger.CostCalculator;import io.github.kxng0109.cacherelay.ledger.DecisionLogWriter;
import io.github.kxng0109.cacherelay.ledger.ModelPriceCatalog;
import io.github.kxng0109.cacherelay.ledger.ModelPricingEntry;
import io.github.kxng0109.cacherelay.ledger.TokenUsageEvent;
import io.github.kxng0109.cacherelay.proxy.failover.FailoverOrchestrator;
import io.github.kxng0109.cacherelay.proxy.failover.ProviderResponse;
import io.github.kxng0109.cacherelay.proxy.failover.UpstreamUnavailableException;
import io.micrometer.tracing.Tracer;
import io.github.kxng0109.cacherelay.proxy.protocol.*;
import io.github.kxng0109.cacherelay.proxy.sse.LineTooLongException;
import io.github.kxng0109.cacherelay.proxy.sse.SseConnectionLimitException;
import io.github.kxng0109.cacherelay.proxy.sse.SseFlushStrategy;
import io.github.kxng0109.cacherelay.proxy.sse.SseLineGuard;
import io.github.kxng0109.cacherelay.proxy.sse.SseLineGuardAutoConfig.SseLineGuardFactory;
import io.github.kxng0109.cacherelay.security.compliance.MerkleAuditLedger;
import io.github.kxng0109.cacherelay.security.compliance.ZeroDataRetentionEnforcer;
import io.github.kxng0109.cacherelay.security.filter.IngressSecurityFilter;
import io.github.kxng0109.cacherelay.security.filter.KeyAuthFilter;
import io.github.kxng0109.cacherelay.security.guardrail.common.GuardrailProperties;
import io.github.kxng0109.cacherelay.security.guardrail.injection.SystemPromptProtectionEngine;
import io.github.kxng0109.cacherelay.security.guardrail.pii.EphemeralPiiVault;
import io.github.kxng0109.cacherelay.security.guardrail.streaming.MidStreamKillSwitch;
import io.github.kxng0109.cacherelay.security.guardrail.streaming.SlidingWindowAhoCorasick;
import io.github.kxng0109.cacherelay.security.guardrail.streaming.StreamingJsonPdaValidator;
import io.github.kxng0109.cacherelay.security.ratelimit.RateLimitUnavailableException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/**
 * REST controller exposing the chat completions proxy endpoint.
 *
 * <p>The client always talks to this one OpenAI shaped endpoint. Behind it the
 * controller resolves the requested model to a {@link ModelAlias}, asks the {@link FailoverOrchestrator} to pick a
 * winning provider, and relays that provider's stream back through its {@link ProtocolAdapter} normalizer, so the
 * client sees OpenAI shaped SSE no matter which dialect the winner spoke. Failover has already happened by the time
 * streaming begins, so the client never sees a switch.</p>
 *
 * <p>When a stream completes with token usage, a single {@link TokenUsageEvent}
 * is published for the asynchronous ledger. Publishing happens after the last byte was written, never inside the
 * streaming loop, and the listener runs on its own executor, so accounting can never slow the response.</p>
 */
@Slf4j
@RestController
@Tag(name = "Proxy - Chat Completions", description = "OpenAI-compatible chat completions proxy with rate-limiting, failover, and multi-tier caching")
public class ProxyController {

	/**
	 * Upper bound for the retained completion text accumulated during an SSE relay for cache and
	 * replay persistence (~4 MiB, roughly a million tokens). The client always receives the full
	 * stream; past this bound persistence is skipped rather than storing a truncated artifact.
	 */
	static final int ACCUMULATED_CONTENT_MAX_BYTES = 4 * 1024 * 1024;

	private final FailoverOrchestrator failoverOrchestrator;
	private final GatewayProperties gatewayProperties;
	private final ObjectMapper objectMapper;
	private final ProtocolAdapterResolver adapterResolver;
	private final CostCalculator costCalculator;
	private final ApplicationEventPublisher eventPublisher;
	private final SseFlushStrategy flushStrategy;
	private final SseLineGuardFactory lineGuardFactory;
	private final @Nullable CacheRelayCacheService cacheService;
	private final @Nullable CachedStreamReconstitution cachedStreamReconstitution;
	private final @Nullable MerkleAuditLedger auditLedger;
	private final @Nullable SystemPromptProtectionEngine systemPromptProtectionEngine;
	private final @Nullable GuardrailProperties guardrailProperties;
	private final @Nullable ZeroDataRetentionEnforcer zdrEnforcer;

	private volatile @Nullable BudgetEnforcer budgetEnforcer;

	private volatile @Nullable BudgetSettlement budgetSettlement;

	private volatile @Nullable ReplayService replayService;

	private volatile @Nullable DecisionLogWriter decisionLogWriter;

	private volatile @Nullable ModelPriceCatalog modelPriceCatalog;

	private volatile @Nullable Tracer tracer;
	/**
	 * Full enterprise constructor injecting all components including cache, security, and compliance subsystems.
	 */
	@Autowired
	public ProxyController(
			FailoverOrchestrator failoverOrchestrator,
			GatewayProperties gatewayProperties,
			ObjectMapper objectMapper,
			ProtocolAdapterResolver adapterResolver,
			CostCalculator costCalculator,
			ApplicationEventPublisher eventPublisher,
			SseFlushStrategy flushStrategy,
			SseLineGuardFactory lineGuardFactory,
			@Nullable CacheRelayCacheService cacheService,
			@Nullable CachedStreamReconstitution cachedStreamReconstitution,
			@Nullable MerkleAuditLedger auditLedger,
			@Nullable SystemPromptProtectionEngine systemPromptProtectionEngine,
			@Nullable GuardrailProperties guardrailProperties,
			@Nullable ZeroDataRetentionEnforcer zdrEnforcer
	) {
		this.failoverOrchestrator = failoverOrchestrator;
		this.gatewayProperties = gatewayProperties;
		this.objectMapper = objectMapper;
		this.adapterResolver = adapterResolver;
		this.costCalculator = costCalculator;
		this.eventPublisher = eventPublisher;
		this.flushStrategy = flushStrategy;
		this.lineGuardFactory = lineGuardFactory;
		this.cacheService = cacheService;
		this.cachedStreamReconstitution = cachedStreamReconstitution;
		this.auditLedger = auditLedger;
		this.systemPromptProtectionEngine = systemPromptProtectionEngine;
		this.guardrailProperties = guardrailProperties;
		this.zdrEnforcer = zdrEnforcer;
	}

	/**
	 * Constructor for tests and contexts with cache subsystem.
	 */
	public ProxyController(
			FailoverOrchestrator failoverOrchestrator,
			GatewayProperties gatewayProperties,
			ObjectMapper objectMapper,
			ProtocolAdapterResolver adapterResolver,
			CostCalculator costCalculator,
			ApplicationEventPublisher eventPublisher,
			SseFlushStrategy flushStrategy,
			SseLineGuardFactory lineGuardFactory,
			@Nullable CacheRelayCacheService cacheService,
			@Nullable CachedStreamReconstitution cachedStreamReconstitution
	) {
		this(
				failoverOrchestrator,
				gatewayProperties,
				objectMapper,
				adapterResolver,
				costCalculator,
				eventPublisher,
				flushStrategy,
				lineGuardFactory,
				cacheService,
				cachedStreamReconstitution,
				null,
				null,
				null,
				null
		);
	}

	/**
	 * Convenience constructor for existing tests without cache or guardrail subsystems.
	 */
	public ProxyController(
			FailoverOrchestrator failoverOrchestrator,
			GatewayProperties gatewayProperties,
			ObjectMapper objectMapper,
			ProtocolAdapterResolver adapterResolver,
			CostCalculator costCalculator,
			ApplicationEventPublisher eventPublisher,
			SseFlushStrategy flushStrategy,
			SseLineGuardFactory lineGuardFactory
	) {
		this(
				failoverOrchestrator,
				gatewayProperties,
				objectMapper,
				adapterResolver,
				costCalculator,
				eventPublisher,
				flushStrategy,
				lineGuardFactory,
				null,
				null,
				null,
				null,
				null,
				null
		);
	}

	/**
	 * Proxies an OpenAI shaped chat completion request to the configured provider chain and streams the normalized SSE
	 * response back.
	 *
	 * @param rawBody the raw request body
	 * @param request the servlet request, used to read the authenticated owner
	 * @return a streaming response, or a JSON error for 400, 404, 502, 503, 504
	 */
	@Operation(
			summary = "Relay OpenAI-compatible chat completion",
			description = """
					Proxies chat completion requests to the configured provider failover chain (OpenAI, Anthropic, Ollama, OpenRouter).
					Automatically resolves L0 in-memory and L1/L2 Redis semantic cache entries before routing to upstream providers.
					Streams normalized Server-Sent Events (SSE) back to the client.
					""",
			security = @SecurityRequirement(name = OpenApiConfig.SCHEME_BEARER_AUTH)
	)
	@ApiResponses(value = {
			@ApiResponse(
					responseCode = "200",
					description = "SSE stream or cached completion relayed successfully",
					headers = {
							@Header(name = "X-Cache", description = "Cache tier status: HIT (L0-Memory), HIT (L1-Exact), HIT (L2-Semantic), or omitted on MISS", schema = @Schema(type = "string", example = "HIT (L2-Semantic)")),
							@Header(name = "X-CacheRelay-Similarity-Score", description = "Cosine similarity score for L2 semantic hits", schema = @Schema(type = "string", example = "0.9650")),
							@Header(name = "Age", description = "Age of the cached response in seconds", schema = @Schema(type = "string", example = "42")),
							@Header(name = "X-RateLimit-Remaining-RPM", description = "Remaining requests allowed in the current minute window", schema = @Schema(type = "integer", example = "118")),
							@Header(name = "X-RateLimit-Remaining-TPM", description = "Remaining token budget in the current minute window", schema = @Schema(type = "integer", example = "485000")),
							@Header(name = "X-CacheRelay-Vendor-Verdict", description = "Flagged vendor verdict (vendor:reason) on AUDIT_ONLY pass-throughs; absent on clean verdicts", schema = @Schema(type = "string", example = "bedrock:contentPolicy:VIOLENCE"))
					},
					content = @Content(
							mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
							examples = @ExampleObject(
									name = "Streaming SSE Chunk Sequence",
									summary = "Standard OpenAI Server-Sent Event stream",
									value = """
											data: {"id":"chatcmpl-a1b2","object":"chat.completion.chunk","created":1772540000,"model":"gpt-56-luna","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}
											
											data: {"id":"chatcmpl-a1b2","object":"chat.completion.chunk","created":1772540000,"model":"gpt-56-luna","choices":[{"index":0,"delta":{"content":"Hello! How can I assist you today?"},"finish_reason":null}]}
											
											data: {"id":"chatcmpl-a1b2","object":"chat.completion.chunk","created":1772540000,"model":"gpt-56-luna","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
											
											data: [DONE]
											"""
							)
					)
			),
			@ApiResponse(
					responseCode = "400",
					description = "Malformed JSON request, missing model parameter, or empty payload",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = "{\"error\":{\"message\":\"model is required\",\"type\":\"invalid_request_error\"}}"))
			),
			@ApiResponse(
					responseCode = "401",
					description = "Missing, invalid, or expired Virtual API Key (Authorization: Bearer gw-...)",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = "{\"error\":{\"message\":\"invalid API key\",\"type\":\"authentication_error\"}}"))
			),
			@ApiResponse(
					responseCode = "403",
					description = "Virtual API Key is disabled or unauthorized for the requested model alias",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = "{\"error\":{\"message\":\"key disabled or model not allowed\",\"type\":\"permission_error\"}}"))
			),
			@ApiResponse(
					responseCode = "404",
					description = "Requested model alias is not registered in gateway routing configuration",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = "{\"error\":{\"message\":\"unknown model: gpt-unknown\",\"type\":\"invalid_request_error\"}}"))
			),
			@ApiResponse(
					responseCode = "413",
					description = "Request body exceeds configured size limit (64 KB cap)",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = "{\"error\":{\"message\":\"request body exceeds limit\",\"type\":\"invalid_request_error\"}}"))
			),
			@ApiResponse(
					responseCode = "429",
					description = "Virtual API Key exceeded RPM or TPM rate limit quota",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = "{\"error\":{\"message\":\"rate limit exceeded\",\"type\":\"rate_limit_error\"}}"))
			),
			@ApiResponse(
					responseCode = "502",
					description = "All upstream model providers in failover chain returned errors",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = "{\"error\":{\"message\":\"all providers failed\",\"type\":\"upstream_error\"}}"))
			),
			@ApiResponse(
					responseCode = "503",
					description = "Upstream service unavailable (all circuits open, nothing configured, or blocked target). Economy routing with no compliant leg adds error.code NO_COMPLIANT_ECONOMY_PROVIDER; every other 503 keeps the codeless shape",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = "{\"error\":{\"message\":\"upstream service unavailable\"}}"))
			),
			@ApiResponse(
					responseCode = "422",
					description = "Opt-in vendor screening flagged the payload in ENFORCE mode; AUDIT_ONLY instead returns 200 with X-CacheRelay-Vendor-Verdict",
					headers = {
							@Header(name = "X-CacheRelay-Vendor-Verdict", description = "Flagged vendor verdict (vendor:reason)", schema = @Schema(type = "string", example = "bedrock:contentPolicy:VIOLENCE"))
					},
					content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, examples = @ExampleObject(value = "{\"type\":\"https://cacherelay.io/errors/vendor-screening-rejection\",\"title\":\"Unprocessable Content - Vendor Screening Rejection\",\"status\":422,\"detail\":\"Third-party screening flagged the payload; request denied.\",\"vendor\":\"bedrock\",\"reason\":\"contentPolicy:VIOLENCE\"}"))
			),
			@ApiResponse(
					responseCode = "504",
					description = "Upstream provider failover chain timed out",
					content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, examples = @ExampleObject(value = "{\"error\":{\"message\":\"gateway timeout\",\"type\":\"timeout_error\"}}"))
			)
	})
	@PostMapping(value = "/v1/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<StreamingResponseBody> proxyChatCompletions(
			@io.swagger.v3.oas.annotations.parameters.RequestBody(
					description = "OpenAI-compatible chat completion payload specifying model, messages, temperature, and stream options",
					required = true,
					content = @Content(
							mediaType = MediaType.APPLICATION_JSON_VALUE,
							schema = @Schema(implementation = OpenAiChatRequest.class),
							examples = {
									@ExampleObject(
											name = "Standard Streaming Request",
											summary = "Standard multi-turn chat request with streaming",
											value = """
													{
													  "model": "gpt-56-luna",
													  "messages": [
													    {"role": "system", "content": "You are a concise, helpful technical assistant."},
													    {"role": "user", "content": "Explain zero-copy vector serialization in two sentences."}
													  ],
													  "temperature": 0.0,
													  "stream": true,
													  "stream_options": {"include_usage": true}
													}
													"""
									),
									@ExampleObject(
											name = "Non-Streaming Request",
											summary = "Direct completion request",
											value = """
													{
													  "model": "claude-sonnet-4-5",
													  "messages": [
													    {"role": "user", "content": "Hello world"}
													  ],
													  "temperature": 0.0,
													  "stream": false
													}
													"""
									)
							}
					)
			)
			@RequestBody String rawBody,
			HttpServletRequest request
	) {
		String trimmed = rawBody == null ? "" : rawBody.trim();
		if (trimmed.isEmpty()) {
			ProxySpanAttributes.markError(request, "empty_body");
			ProxySpanAttributes.tagCurrentSpan(tracer, request);
			return errorResponse(HttpStatus.BAD_REQUEST, "empty request body");
		}

		// PERF-04: parse once — the tree serves model extraction and DTO binding
		// (treeToValue), replacing two full parses with one parse plus one bind.
		JsonNode bodyTree = parseBodyTree(trimmed);
		String model = extractModel(bodyTree);
		if (model == null || model.isBlank()) {
			ProxySpanAttributes.markError(request, "model_required");
			ProxySpanAttributes.tagCurrentSpan(tracer, request);
			return errorResponse(HttpStatus.BAD_REQUEST, "model is required");
		}

		ModelAlias alias = gatewayProperties.getAliases().get(model);
		if (alias == null) {
			ProxySpanAttributes.markRouting(request, null, model);
			ProxySpanAttributes.markError(request, "unknown_model");
			ProxySpanAttributes.tagCurrentSpan(tracer, request);
			return errorResponse(HttpStatus.NOT_FOUND, "unknown model: " + model);
		}
		// Request-level routing facts for span tagging: the requested key is the
		// alias; the served model is the request model until routing overrides it.
		ProxySpanAttributes.markRouting(request, model, model);

		final String idempotencyKey;
		try {
			idempotencyKey = IdempotencyKeys.validateOrNull(request.getHeader(IdempotencyKeys.HEADER));
		} catch (IllegalArgumentException malformed) {
			ProxySpanAttributes.markError(request, "invalid_idempotency_key");
			ProxySpanAttributes.tagCurrentSpan(tracer, request);
			return errorResponse(HttpStatus.BAD_REQUEST, "invalid Idempotency-Key");
		}

		// Cost-router phase 1: routing preferences are validated fail-fast and logged, never enforced.
		final RoutingDecisionContext routingContext;
		try {
			routingContext = RoutingDecisionContext.fromRequest(request);
		} catch (ResponseStatusException badHeaders) {
			String reason = badHeaders.getReason();
			ProxySpanAttributes.markError(request, "invalid_routing_headers");
			ProxySpanAttributes.tagCurrentSpan(tracer, request);
			return errorResponse(HttpStatus.BAD_REQUEST, reason == null ? "invalid routing headers" : reason);
		}

		@Nullable String ownerId = (String) request.getAttribute(KeyAuthFilter.OWNER_ID_ATTRIBUTE);
		@Nullable VirtualApiKey apiKey =
				(VirtualApiKey) request.getAttribute(KeyAuthFilter.VIRTUAL_KEY_ATTRIBUTE);
		if (apiKey != null && !apiKey.allowedProviders().isEmpty()) {
			Optional<ModelAlias> filtered = ProviderAccess.filterAlias(alias, apiKey.allowedProviders());
			if (filtered.isEmpty()) {
				ProxySpanAttributes.markError(request, "provider_not_allowed");
				ProxySpanAttributes.tagCurrentSpan(tracer, request);
				return errorResponse(HttpStatus.FORBIDDEN, "No allowed providers for this key");
			}
			alias = filtered.get();
		}
		OpenAiChatRequest chatRequest = parseChatRequest(bodyTree);
		// PERF-04: the body fingerprint feeds idempotency only — skip the SHA-256
		// when no Idempotency-Key is present (resolveRequestId ignores it then).
		@Nullable String bodyHashHex = idempotencyKey != null
				? IdempotencyKeys.sha256Hex(trimmed.getBytes(StandardCharsets.UTF_8))
				: null;

		// Exact replay for idempotent retries: a stored completion is re-delivered without upstream spend
		// or budget charge. Same key + different body is 422 (key reuse); a concurrent first flight is 409.
		@Nullable ReplayFlight claimedFlight = null;
		ReplayService replay = this.replayService;
		if (idempotencyKey != null && replay != null) {
			String replayKeyHashHex =
					(String) request.getAttribute(KeyAuthFilter.KEY_HASH_ATTRIBUTE);
			ReplayService.Lookup lookup =
					replay.lookup(idempotencyKey, bodyHashHex, ownerId, replayKeyHashHex);
			if (lookup instanceof ReplayService.FingerprintMismatch) {
				ProxySpanAttributes.markError(request, "idempotency_key_reuse");
				ProxySpanAttributes.tagCurrentSpan(tracer, request);
				return errorResponse(HttpStatus.UNPROCESSABLE_ENTITY,
						"idempotency key already used with a different request");
			}
			if (lookup instanceof ReplayService.InFlight) {
				ProxySpanAttributes.markError(request, "identical_request_in_flight");
				ProxySpanAttributes.tagCurrentSpan(tracer, request);
				HttpHeaders conflictHeaders = new HttpHeaders();
				conflictHeaders.setContentType(MediaType.APPLICATION_JSON);
				conflictHeaders.set(HttpHeaders.RETRY_AFTER, "1");
				return ResponseEntity.status(HttpStatus.CONFLICT).headers(conflictHeaders)
						.body(out -> out.write(
								"{\"error\":{\"message\":\"identical request in flight\"}}"
										.getBytes(StandardCharsets.UTF_8)));
			}
			if (lookup instanceof ReplayService.Hit hit) {
				// Idempotent re-delivery: no upstream spend, tagged like a cache hit.
				ProxySpanAttributes.markCacheHit(request, "replay", Double.NaN);
				ProxySpanAttributes.tagCurrentSpan(tracer, request);
				return serveReplay(hit);
			}
			if (replay.beginFill(idempotencyKey, ownerId, replayKeyHashHex)) {
				String keyHashHex =
						(String) request.getAttribute(KeyAuthFilter.KEY_HASH_ATTRIBUTE);
				claimedFlight = new ReplayFlight(idempotencyKey, bodyHashHex,
						BudgetEnforcer.dedupeClaimId(ownerId, keyHashHex, bodyHashHex, idempotencyKey),
						ownerId, replayKeyHashHex);
			} else {
				ProxySpanAttributes.markError(request, "identical_request_in_flight");
				ProxySpanAttributes.tagCurrentSpan(tracer, request);
				HttpHeaders conflictHeaders = new HttpHeaders();
				conflictHeaders.setContentType(MediaType.APPLICATION_JSON);
				conflictHeaders.set(HttpHeaders.RETRY_AFTER, "1");
				return ResponseEntity.status(HttpStatus.CONFLICT).headers(conflictHeaders)
						.body(out -> out.write(
								"{\"error\":{\"message\":\"identical request in flight\"}}"
										.getBytes(StandardCharsets.UTF_8)));
			}
		}
		final @Nullable ReplayFlight replayFlight = claimedFlight;

		if (cacheService != null && cachedStreamReconstitution != null && chatRequest != null) {
			CacheLookupResult cacheResult = cacheService.evaluateCache(chatRequest, request, ownerId, apiKey);
			if (cacheResult.isHit() && cacheResult.entry() != null) {
				CacheEntry entry = cacheResult.entry();
				ProxySpanAttributes.markCacheHit(request,
						cacheResult.status() == CacheStatus.HIT_L0 ? "l0-memory" : (
								cacheResult.status() == CacheStatus.HIT_L1 ? "l1-exact" : "l2-semantic"),
						cacheResult.similarityScore());
				ProxySpanAttributes.tagCurrentSpan(tracer, request);
				boolean clientWantsUsage = chatRequest != null && chatRequest.requestsUsage();
				HttpHeaders headers = new HttpHeaders();
				headers.setContentType(MediaType.TEXT_EVENT_STREAM);
				headers.setCacheControl("no-cache");
				headers.set("X-Accel-Buffering", "no");
				headers.set(
						"X-Cache",
						cacheResult.status() == CacheStatus.HIT_L0 ? "HIT (L0-Memory)" : (
								cacheResult.status() == CacheStatus.HIT_L1 ? "HIT (L1-Exact)" : "HIT (L2-Semantic)")
				);
				headers.set(
						"X-CacheRelay-Similarity-Score",
						String.format(Locale.ROOT, "%.4f", cacheResult.similarityScore())
				);
				if (entry.createdAt() != null) {
					headers.set("Age", String.valueOf(Duration.between(entry.createdAt(), Instant.now()).toSeconds()));
				}
				return ResponseEntity.ok().headers(headers).body(out -> cachedStreamReconstitution.streamCachedResponse(
						entry,
						model,
						clientWantsUsage,
						out
				));
			}
		}

		UUID requestId = IdempotencyKeys.resolveRequestId(
				idempotencyKey,
				ownerId == null ? "" : ownerId,
				request.getRequestURI(),
				bodyHashHex);

		// Spend-budget gate: cache misses only (hits served ~free and bypass spend).
		// Runs after alias/idempotency validation, before any upstream spend.
		// On allow, an admission hold H is charged and recorded for stream-end true-up.
		BudgetAdmission admission = admitWithBudget(
				alias, model, ownerId, trimmed, chatRequest, requestId,
				(String) request.getAttribute(KeyAuthFilter.KEY_HASH_ATTRIBUTE), idempotencyKey, bodyHashHex);
		if (admission.denied() != null) {
			ProxySpanAttributes.markError(request, "budget_denied");
			ProxySpanAttributes.tagCurrentSpan(tracer, request);
			return admission.denied();
		}
		@Nullable SettlementContext settlementContext = admission.context();

		ProviderResponse providerResponse;
		// The fetch ceiling follows client intent: explicit stream:false responses are single JSON
		// documents (loose ceiling); everything else keeps the tight SSE line ceiling. A streaming
		// fetch that turns out to be JSON still relays through relayJson — with the SSE ceiling.
		boolean streaming = chatRequest == null || !Boolean.FALSE.equals(chatRequest.stream());
		try {
			providerResponse = failoverOrchestrator.execute(alias, trimmed, routingContext, streaming).join();
		} catch (CompletionException ex) {
			recordDecision(model, alias, model, routingContext, null);
			Throwable cause = ex.getCause();
			if (cause instanceof UpstreamUnavailableException upstream) {
				ProxySpanAttributes.markError(request, "upstream_unavailable");
				ProxySpanAttributes.tagCurrentSpan(tracer, request);
				throw upstream;
			}
			log.warn("Upstream request failed unexpectedly: {}", cause == null ? "unknown cause" : cause.getMessage());
			ProxySpanAttributes.markError(request, "upstream_failure");
			ProxySpanAttributes.tagCurrentSpan(tracer, request);
			throw new UpstreamUnavailableException(
					"upstream request failed unexpectedly",
					cause, false, false
			);
		}
		recordDecision(model, alias, model, routingContext, providerResponse);

		int status = providerResponse.response().statusCode();
		if (status != HttpStatus.OK.value()) {
			// Upstream failed: no usable output exists, so only the processed input stands.
			ProxySpanAttributes.markProviderError(request, providerResponse.providerName(), status);
			ProxySpanAttributes.tagCurrentSpan(tracer, request);
			settlePromptKnown(settlementContext, false);
			replayReleaseQuietly(replayFlight);
			return ResponseEntity.status(status)
					.contentType(MediaType.APPLICATION_JSON)
					.header("X-CacheRelay-Provider", providerResponse.providerName())
					.header("X-CacheRelay-Tried", triedHeader(providerResponse))
					.body(out -> relayRaw(providerResponse, out));
		}

		// Successful miss: provider winner, tried-leg count, and admission hold
		// (pre-spend estimate; settled cost lands in the ledger at stream end).
		ProxySpanAttributes.markMiss(request, providerResponse.providerName(),
				providerResponse.triedProviders() == null ? -1 : providerResponse.triedProviders().size(),
				settlementContext == null ? -1L : settlementContext.holdMicros());
		ProxySpanAttributes.tagCurrentSpan(tracer, request);

		ProviderConfig config = gatewayProperties.getProviders().get(providerResponse.providerName());
		ProviderType providerType = config == null ? ProviderType.OPENAI : config.type();
		ProtocolAdapter adapter = adapterResolver.resolve(providerType);
		boolean clientWantsUsage = chatRequest != null && chatRequest.requestsUsage();

		HttpHeaders headers = new HttpHeaders();
		headers.setCacheControl("no-cache");
		headers.set("X-Accel-Buffering", "no");

		if (auditLedger != null) {
			MerkleAuditLedger.AuditReceipt receipt = auditLedger.recordTransaction(
					ownerId, String.valueOf(requestId), trimmed.getBytes(StandardCharsets.UTF_8), null
			);
			headers.set("X-CacheRelay-Audit-Receipt", receipt.receiptHeaderValue());
		}
		if (zdrEnforcer != null) {
			zdrEnforcer.applyHeaders(headers);
		}
		headers.set("X-CacheRelay-Provider", providerResponse.providerName());
		headers.set("X-CacheRelay-Tried", triedHeader(providerResponse));
		if (settlementContext != null) {
			headers.set("X-Budget-Held-Micros", Long.toString(settlementContext.holdMicros()));
			if (settlementContext.ownerId() != null) {
				headers.set("X-Budget-Subject", settlementContext.ownerId());
			}
		}

		String upstreamContentType = providerResponse.response().headers() == null ? ""
				: providerResponse.response().headers().firstValue("Content-Type").orElse("");
		// Absent headers (or an unrecognized type) keep the historical SSE path; only a
		// positively non-streaming content type diverts to the JSON completion relay.
		boolean upstreamStreaming = upstreamContentType.isBlank()
				|| upstreamContentType.toLowerCase(Locale.ROOT).contains("text/event-stream")
				|| upstreamContentType.toLowerCase(Locale.ROOT).contains("application/x-ndjson");
		if (!upstreamStreaming) {
			headers.setContentType(MediaType.APPLICATION_JSON);
			return ResponseEntity.ok().headers(headers).body(out -> relayJson(
					providerResponse, requestId, ownerId, providerType,
					providerResponse.providerName(), model, chatRequest, request, out,
					settlementContext, replayFlight
			));
		}

		headers.setContentType(MediaType.TEXT_EVENT_STREAM);
		return ResponseEntity.ok().headers(headers).body(out -> relaySse(
				providerResponse, adapter.newNormalizer(clientWantsUsage, model), out,
				requestId, ownerId, providerType, providerResponse.providerName(), model,
				chatRequest, request, settlementContext, replayFlight
		));
	}

	/**
	 * Re-delivers a stored completion for an idempotent retry without upstream spend or budget charge.
	 * Non-streaming payloads are byte-identical to the original response; streaming completions are
	 * re-framed as a single SSE data event plus {@code [DONE]} (transport framing, identical content and
	 * usage). Every replay carries the {@code Idempotent-Replayed} marker.
	 */
	private ResponseEntity<StreamingResponseBody> serveReplay(ReplayService.Hit hit) {
		HttpHeaders headers = new HttpHeaders();
		headers.set("Idempotent-Replayed", "true");
		headers.setCacheControl("no-cache");
		byte[] payload = hit.body();
		if (hit.sseFramed()) {
			headers.setContentType(MediaType.TEXT_EVENT_STREAM);
			headers.set("X-Accel-Buffering", "no");
			return ResponseEntity.ok().headers(headers).body(out -> {
				out.write("data: ".getBytes(StandardCharsets.UTF_8));
				out.write(payload);
				out.write("\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
			});
		}
		headers.setContentType(MediaType.APPLICATION_JSON);
		return ResponseEntity.ok().headers(headers).body(out -> out.write(payload));
	}

	/**
	 * Stores a completed payload for future idempotent retries. Best-effort by design: every failure is
	 * logged and absorbed (the fill claim is released, so a later retry simply re-proxies).
	 */
	private void replayStoreQuietly(@Nullable ReplayFlight flight, byte[] payload, boolean sseFramed) {
		ReplayService replay = this.replayService;
		if (replay == null || flight == null) {
			return;
		}
		try {
			replay.store(flight.key(), flight.bodyHash(), payload, sseFramed, flight.tenant(), flight.keyHashHex());
		} catch (RuntimeException ex) {
			log.warn("Replay store failed; fill released for a later retry");
			replayReleaseQuietly(flight);
		}
	}

	/**
	 * Releases an owned fill claim without storing (abort, upstream failure, unmeasurable usage), and releases
	 * the matching budget idempotency claim so a retry claims fresh. A stale budget claim is harmless either
	 * way: colliding retries still evaluate caps and charge (fail-closed).
	 */
	private void replayReleaseQuietly(@Nullable ReplayFlight flight) {
		ReplayService replay = this.replayService;
		if (replay == null || flight == null) {
			return;
		}
		try {
			replay.releaseFill(flight.key(), flight.tenant(), flight.keyHashHex());
		} catch (RuntimeException ex) {
			log.warn("Replay fill release failed; TTL bounds the stale claim");
		}
		BudgetEnforcer enforcer = this.budgetEnforcer;
		if (enforcer != null) {
			enforcer.releaseIdempotencyClaim(flight.budgetClaimId());
		}
	}

	private void relaySse(
			ProviderResponse providerResponse,
			SseNormalizer normalizer,
			OutputStream out,
			UUID requestId,
			@Nullable String ownerId,
			ProviderType providerType,
			String providerName,
			String requestedModel,
			@Nullable OpenAiChatRequest chatRequest,
			HttpServletRequest servletRequest,
			@Nullable SettlementContext settlementContext,
			@Nullable ReplayFlight replayFlight
	) throws IOException {
		long startedNanos = System.nanoTime();
		ServletOutputStream servletOut = out instanceof ServletOutputStream candidate ? candidate : null;
		SseFlushStrategy.FlushHandle flushHandle = null;
		if (servletOut != null) {
			try {
				flushHandle = flushStrategy.register(servletOut);
			} catch (SseConnectionLimitException ex) {
				log.warn("SSE stream rejected, connection limit reached: {}", ex.getMessage());
				settlePromptKnown(settlementContext, true);
				replayReleaseQuietly(replayFlight);
				return;
			}
		}

		// Create per-stream line guard
		SseLineGuard lineGuard = lineGuardFactory.newGuard(
				SseLineGuard.ProviderType.from(providerType),
				providerName,
				UUID.randomUUID()
		);

		EphemeralPiiVault vault = (EphemeralPiiVault) servletRequest.getAttribute(IngressSecurityFilter.PII_VAULT_ATTRIBUTE);
		SlidingWindowAhoCorasick deAnonymizer = vault != null ? new SlidingWindowAhoCorasick(vault) : null;
		StreamingJsonPdaValidator jsonPda = (guardrailProperties != null
				&& guardrailProperties.isStreamingValidationEnabled())
				? new StreamingJsonPdaValidator() : null;

		SystemPromptProtectionEngine.StreamingShingleTracker shingleTracker = null;
		if (systemPromptProtectionEngine != null && chatRequest != null && chatRequest.messages() != null) {
			for (OpenAiChatRequest.Message msg : chatRequest.messages()) {
				if ("system".equalsIgnoreCase(msg.role()) && msg.content() != null && msg.content().isString()) {
					var hashes = systemPromptProtectionEngine.computeShingleHashes(msg.content().asString());
					if (!hashes.isEmpty()) {
						shingleTracker = systemPromptProtectionEngine.newTracker(hashes);
						break;
					}
				}
			}
		}

		StringBuilder accumulatedContent = null;
		// The accumulation exists only to persist the completion (cache store + replay); when
		// neither is armed it is pure per-stream heap growth (PRX-B19), so it is skipped entirely.
		// The cap bounds the retained artifact: past it the client still receives the full stream,
		// but nothing is persisted (a truncated artifact must never be cached or replayed).
		boolean accumulateContent = (cacheService != null || replayFlight != null) && chatRequest != null;
		if (accumulateContent) {
			accumulatedContent = new StringBuilder();
		}
		boolean accumulationTruncated = false;
		boolean upstreamFault = false;
		long linesWritten = 0;
		long lastRenewalNanos = startedNanos;
		try {
			try (var lines = providerResponse.response().body()) {
				Iterator<String> iterator = lines.iterator();
				while (true) {
					String line;
					try {
						if (!iterator.hasNext()) {
							break;
						}
						line = iterator.next();
					} catch (RuntimeException iteratorFailed) {
						if (iteratorFailed instanceof LineTooLongException tooLong) {
							// Body handler ceiling: the dedicated handler below
							// owns this fault, not the upstream-fault path.
							throw tooLong;
						}
						// PRX-B05: the upstream died mid-stream. Flag it so the
						// handler below emits a terminal error event instead of
						// misreporting a client disconnect.
						upstreamFault = true;
						throw new IOException("Upstream terminated the stream", iteratorFailed);
					}
					// Guard the raw upstream line before normalization
					List<String> guarded = lineGuard.checkLine(
							line,
							SseLineGuard.ProviderType.from(providerType)
					);

					if (lineGuard.isRejected()) {
						// Write SSE error event and close
						for (String s : guarded) {
							writeSse(out, s);
						}
						out.flush();
						lineGuard.onStreamAbort("line_too_long");
						settlePromptKnown(settlementContext, true);
						replayReleaseQuietly(replayFlight);
						return;
					}
					if (guarded.isEmpty()) {
						continue; // line dropped (REJECT_LINE_CONTINUE)
					}

					List<String> normalized = normalizer.normalizeLine(line);
					for (String toWrite : normalized) {
						String delta = extractDelta(toWrite);
						// Reference comparison is exact here: replaceDeltaContent returns the
						// identical String reference only when it made no change.
						if (delta != null && !delta.isEmpty()) {
							if (shingleTracker != null && shingleTracker.ingestChunk(delta)) {
								MidStreamKillSwitch.terminate(out, lines, "system_prompt_exfiltration");
								lineGuard.onStreamAbort("system_prompt_exfiltration");
								settlePromptKnown(settlementContext, true);
								replayReleaseQuietly(replayFlight);
								return;
							}
							if (jsonPda != null) {
								jsonPda.ingest(delta);
							}
							if (deAnonymizer != null) {
								String deAnonymized = deAnonymizer.processChunk(delta);
								if (!deAnonymized.equals(delta)) {
									String rewritten = replaceDeltaContent(toWrite, deAnonymized);
									if (rewritten != toWrite) {
										toWrite = rewritten;
										delta = deAnonymized;
									}
								}
							}
						}

					// PERF-06: delta was parsed once above for this exact line (or rebased
					// by the de-anonymizer); reusing it avoids a second full JSON parse.
					if (delta != null && accumulateContent && !accumulationTruncated) {
						if (accumulatedContent.length() + delta.length()
								> ACCUMULATED_CONTENT_MAX_BYTES) {
							accumulationTruncated = true;
							accumulatedContent.setLength(0);
							log.debug("Completion exceeded the accumulation cap; skipping cache/replay persist");
						} else {
							accumulatedContent.append(delta);
						}
					}
					byte[] bytes = toWrite.getBytes(StandardCharsets.UTF_8);
						out.write(bytes);
						out.write('\n');
						// FIN-B20: coarse hold renewal at write checkpoints — the
						// cadence gate inside renewHoldIfDue keeps Redis to one
						// round trip per interval per stream, never per chunk.
						if (settlementContext != null && (++linesWritten & 63) == 0) {
							lastRenewalNanos = renewHoldIfDue(settlementContext, lastRenewalNanos);
						}
						if (flushHandle != null && servletOut != null) {
							if (flushStrategy.onWrite(servletOut, bytes.length + 1)) {
								// PRX-B07: backpressure kills reuse the error+abort
								// path (terminal event, best-effort) instead of a
								// silent truncation.
								try {
									writeSseError(out, "FLUSH_BACKPRESSURE",
											"Downstream backpressure exceeded the flush budget", providerName);
								} catch (IOException | RuntimeException ignored) {
								}
								lineGuard.onStreamAbort("flush_backpressure");
								settlePromptKnown(settlementContext, true);
								replayReleaseQuietly(replayFlight);
								return;
							}
						} else {
							out.flush();
						}
					}
					if (normalizer.isDone()) {
						if (deAnonymizer != null) {
							String leftover = deAnonymizer.flush();
							if (!leftover.isEmpty() && accumulateContent && !accumulationTruncated) {
								if (accumulatedContent.length() + leftover.length()
										> ACCUMULATED_CONTENT_MAX_BYTES) {
									accumulationTruncated = true;
									accumulatedContent.setLength(0);
								} else {
									accumulatedContent.append(leftover);
								}
							}
						}
						break;
					}
				}
			}
		} catch (LineTooLongException ex) {
			// Body handler detected oversized line during byte decoding
			writeSseError(out, ex.limitBytes(), ex.actualBytes(), ex.provider());
			lineGuard.onStreamAbort("line_too_long");
			settlePromptKnown(settlementContext, true);
			replayReleaseQuietly(replayFlight);
			return;
		} catch (IOException ex) {
			if (upstreamFault) {
				// PRX-B05: the stream died upstream, not downstream. Tell the
				// client with a terminal error event (best-effort: the socket
				// may be gone too) instead of a silent truncation, then settle
				// the partial usage like every other abort path.
				try {
					writeSseError(out, "UPSTREAM_FAULT",
							"Upstream terminated the stream mid-response", providerName);
				} catch (IOException | RuntimeException ignored) {
				}
				lineGuard.onStreamAbort("upstream_fault");
			}
			// Downstream client disconnected: settle the input-known portion and re-arm the output hold.
			settlePromptKnown(settlementContext, true);
			replayReleaseQuietly(replayFlight);
			return;
		} finally {
			if (vault != null) {
				vault.close();
			}
			if (flushHandle != null) {
				flushStrategy.unregister(flushHandle);
			}
		}

		SseNormalizer.UsageInfo usage = normalizer.usage();
		if (usage != null) {
			long durationMs = (System.nanoTime() - startedNanos) / 1_000_000;
			String model = normalizer.upstreamModel() == null ? requestedModel : normalizer.upstreamModel();

			long cacheRead = 0L;
			long cacheWrite = 0L;
			long reasoning = 0L;
			if (normalizer instanceof AnthropicSseNormalizer anthropicNormalizer) {
				cacheRead = anthropicNormalizer.cacheReadInputTokens();
				cacheWrite = anthropicNormalizer.cacheCreationInputTokens();
				reasoning = anthropicNormalizer.reasoningTokens();
			} else if (normalizer instanceof DeepSeekSseNormalizer deepSeekNormalizer) {
				cacheRead = deepSeekNormalizer.cachedTokens() != null ? deepSeekNormalizer.cachedTokens() : 0L;
				reasoning = deepSeekNormalizer.reasoningTokens() != null ? deepSeekNormalizer.reasoningTokens() : 0L;
			} else if (normalizer instanceof GeminiSseNormalizer geminiNormalizer) {
				reasoning = geminiNormalizer.reasoningTokens();
			}
			long uncachedPrompt = Math.max(0L, usage.promptTokens() - cacheRead);

			long costUsdMicros = (cacheRead > 0 || cacheWrite > 0)
					? costCalculator.calculate(
					providerType, model,
					usage.promptTokens(), usage.completionTokens(),
					uncachedPrompt, cacheRead, cacheWrite
			)
					: costCalculator.calculate(
					providerType, model,
					usage.promptTokens(), usage.completionTokens()
			);
			eventPublisher.publishEvent(new TokenUsageEvent(
					requestId, ownerId, providerName, model,
					usage.promptTokens(), usage.completionTokens(),
					usage.promptTokens() + usage.completionTokens(),
					durationMs, costUsdMicros, Instant.now(),
					uncachedPrompt, cacheRead, cacheWrite, reasoning,
					costUsdMicros, costUsdMicros, null
			));
			// Stream completed with measured usage: true the hold up to actual spend.
			settleQuietly(settlementContext, costUsdMicros, false);

			boolean cacheBlock = accumulateContent && !accumulationTruncated;
			String completionJson = null;
			int pt = 0;
			int ct = 0;
			if (cacheBlock) {
				pt = (int) Math.min(Integer.MAX_VALUE, usage.promptTokens());
				ct = (int) Math.min(Integer.MAX_VALUE, usage.completionTokens());
				completionJson = buildCompletionJson(model, accumulatedContent.toString(), pt, ct);
			}
			if (cacheBlock) {
				if (cacheService != null) {
					@Nullable VirtualApiKey streamApiKey = (VirtualApiKey) servletRequest
							.getAttribute(KeyAuthFilter.VIRTUAL_KEY_ATTRIBUTE);
					cacheService.storeResponse(
							chatRequest, servletRequest, ownerId, streamApiKey, completionJson, pt, ct);
				}
			// Completed SSE stored re-framed (single data event + DONE on serve): identical content
			// and usage, transport framing only.
			replayStoreQuietly(replayFlight,
					completionJson.getBytes(StandardCharsets.UTF_8), true);
			}
		}
	}

	private @Nullable String extractDelta(String line) {
		if (line != null && line.startsWith("data: ") && !line.contains("[DONE]")) {
			String json = line.substring(6).trim();
			try {
				JsonNode node = objectMapper.readTree(json);
				JsonNode choices = node.path("choices");
				if (choices.isArray() && !choices.isEmpty()) {
					JsonNode delta = choices.get(0).path("delta");
					if (delta.has("content") && delta.get("content").isString()) {
						return delta.get("content").asString();
					}
				}
			} catch (Exception ignored) {
			}
		}
		return null;
	}

	String replaceDeltaContent(String line, String newContent) {
		if (line != null && line.startsWith("data: ") && !line.contains("[DONE]")) {
			String json = line.substring(6).trim();
			try {
				JsonNode node = objectMapper.readTree(json);
				JsonNode choices = node.path("choices");
				if (choices.isArray() && !choices.isEmpty()) {
					JsonNode choice0 = choices.get(0);
					if (choice0 instanceof ObjectNode choiceObj) {
						JsonNode delta = choiceObj.path("delta");
						if (delta instanceof ObjectNode deltaObj) {
							deltaObj.put("content", newContent);
							return "data: " + objectMapper.writeValueAsString(node);
						}
					}
				}
			} catch (Exception ignored) {
			}
		}
		return line;
	}

	private String buildCompletionJson(String model, String content, int promptTokens, int completionTokens) {
		return completionBody(objectMapper, model, content, promptTokens, completionTokens);
	}

	/**
	 * Builds the cached/stream-end completion document with the mapper, never by
	 * string concatenation: client-influenced values (notably {@code model}) are
	 * always escaped, so a quoted model string stays well-formed JSON.
	 * Serialization failures reject with 500 rather than serving a degraded or
	 * truncated document.
	 *
	 * @param mapper           Jackson mapper, never {@code null}
	 * @param model            served model id
	 * @param content          completion text
	 * @param promptTokens     prompt tokens (already clamped)
	 * @param completionTokens completion tokens (already clamped)
	 * @return serialized completion JSON
	 * @throws ResponseStatusException 500 when serialization fails
	 */
	static String completionBody(ObjectMapper mapper, String model, String content,
			int promptTokens, int completionTokens) {
		long created = Instant.now().getEpochSecond();
		String id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
		try {
			ObjectNode root = mapper.createObjectNode();
			root.put("id", id);
			root.put("object", "chat.completion");
			root.put("created", created);
			root.put("model", model);
			ArrayNode choices = root.putArray("choices");
			ObjectNode choice = choices.addObject();
			choice.put("index", 0);
			ObjectNode message = choice.putObject("message");
			message.put("role", "assistant");
			message.put("content", content);
			choice.put("finish_reason", "stop");
			ObjectNode usage = root.putObject("usage");
			usage.put("prompt_tokens", promptTokens);
			usage.put("completion_tokens", completionTokens);
			usage.put("total_tokens", promptTokens + completionTokens);
			return mapper.writeValueAsString(root);
		} catch (RuntimeException failed) {
			throw new ResponseStatusException(
					HttpStatus.INTERNAL_SERVER_ERROR, "completion serialization failed", failed);
		}
	}

	private @Nullable JsonNode parseBodyTree(String rawBody) {
		try {
			return objectMapper.readTree(rawBody);
		} catch (JacksonException ex) {
			return null;
		}
	}

	private @Nullable OpenAiChatRequest parseChatRequest(@Nullable JsonNode bodyTree) {
		if (bodyTree == null || !bodyTree.isObject()) {
			return null;
		}
		try {
			return objectMapper.treeToValue(bodyTree, OpenAiChatRequest.class);
		} catch (Exception ex) {
			return null;
		}
	}

	private void relayJson(
			ProviderResponse providerResponse,
			UUID requestId,
			@Nullable String ownerId,
			ProviderType providerType,
			String providerName,
			String requestedModel,
			@Nullable OpenAiChatRequest chatRequest,
			HttpServletRequest servletRequest,
			OutputStream out,
			@Nullable SettlementContext settlementContext,
			@Nullable ReplayFlight replayFlight
	) throws IOException {
		StringBuilder payload = new StringBuilder();
		try (var lines = providerResponse.response().body()) {
			for (String line : (Iterable<String>) lines::iterator) {
				payload.append(line).append('\n');
			}
		} catch (LineTooLongException tooLong) {
			// A streaming fetch that turned out to be JSON exceeded the SSE line ceiling: settle
			// the input-known portion, release the flight, and fail clean (502) instead of leaking
			// the raw guard exception with no usage recorded.
			log.warn("Upstream JSON body exceeded the line ceiling from provider {}: {}",
					providerName, tooLong.getMessage());
			settlePromptKnown(settlementContext, false);
			replayReleaseQuietly(replayFlight);
			throw new UpstreamUnavailableException("upstream response too large", tooLong, false, false);
		}
		String json = payload.toString().trim();
		JsonNode root;
		try {
			root = objectMapper.readTree(json);
		} catch (JacksonException ex) {
			log.warn("Upstream returned non-JSON 200 body from provider {}: {}", providerName, ex.getMessage());
			// Usage unmeasurable: settle the input-known portion without the abort grace (completed response).
			settlePromptKnown(settlementContext, false);
			replayReleaseQuietly(replayFlight);
			relayRawLine(out, json);
			return;
		}
		JsonNode normalized = normalizeCompletion(root, requestedModel, providerName, providerType);
		recordUsageAndCache(
				normalized, root, chatRequest, providerType, providerName, requestedModel,
				ownerId, requestId, servletRequest, settlementContext, replayFlight
		);
		relayRawLine(out, objectMapper.writeValueAsString(normalized));
	}

	private void relayRaw(ProviderResponse providerResponse, OutputStream out) {
		try (var lines = providerResponse.response().body()) {
			for (String line : (Iterable<String>) lines::iterator) {
				out.write(line.getBytes(StandardCharsets.UTF_8));
				out.write('\n');
			}
		}
		catch (LineTooLongException tooLong) {
			// Error bodies relay with the caller's error status already committed; truncate and
			// close rather than leaking the guard exception mid-stream.
			log.debug("Upstream error body exceeded the line ceiling; truncating the relay");
		}
		catch (IOException ex) {
			// The downstream client went away; the upstream stream is closed by
			// the try with resources, so nothing leaks and nothing is recorded.
			log.debug("Client disconnected while relaying the upstream error body");
		}
	}

	private void relayRawLine(OutputStream out, String body) throws IOException {
		out.write(body.getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	private JsonNode normalizeCompletion(JsonNode root, String requestedModel, String providerName,
			ProviderType providerType) {
		ObjectNode normalized = objectMapper.createObjectNode();
		String id = root.path("id").isString() ? root.path("id").asString() : "chatcmpl-" + UUID.randomUUID();
		long created = root.path("created").isNumber() ? root.path("created").asLong() : Instant.now().getEpochSecond();
		normalized.put("id", id);
		normalized.put("object", "chat.completion");
		normalized.put("created", created);
		normalized.put("model", requestedModel);
		ArrayNode choices = normalized.putArray("choices");
		JsonNode upstreamChoices = root.path("choices");
		if (upstreamChoices.isArray() && upstreamChoices.size() > 0) {
			int index = 0;
			for (JsonNode choice : upstreamChoices) {
				String content = choice.path("message").path("content").isString()
						? choice.path("message").path("content").asString()
						: choice.path("text").isString() ? choice.path("text").asString() : "";
				String finish = choice.path("finish_reason").isString()
						? choice.path("finish_reason").asString() : "stop";
				ObjectNode out = choices.addObject();
				out.put("index", choice.path("index").isNumber() ? choice.path("index").asInt() : index);
				ObjectNode msg = out.putObject("message");
				msg.put("role", "assistant");
				msg.put("content", content);
				out.put("finish_reason", finish);
				index++;
			}
		} else {
			ObjectNode out = choices.addObject();
			out.put("index", 0);
			ObjectNode msg = out.putObject("message");
			msg.put("role", "assistant");
			msg.put("content", root.path("content").isString() ? root.path("content").asString() : "");
			out.put("finish_reason", "stop");
		}
		JsonNode usage = root.path("usage");
		if (usage.isObject()) {
			normalized.set("usage", usage);
		}
		mapDialectUsage(root, providerType, normalized);
		return normalized;
	}

	/**
	 * Maps dialect-native usage shapes onto the OpenAI {@code usage} node the
	 * billing path reads (PRX-B24): without this, non-streaming Anthropic,
	 * Gemini, and Ollama completions bill zero tokens.
	 *
	 * @param root         upstream response root
	 * @param providerType upstream dialect
	 * @param normalized   completion being built (its {@code usage} replaced when mapped)
	 */
	private void mapDialectUsage(JsonNode root, ProviderType providerType, ObjectNode normalized) {
		long prompt = -1L;
		long completion = -1L;
		switch (providerType) {
			case ANTHROPIC -> {
				JsonNode usage = root.path("usage");
				if (usage.isObject()) {
					prompt = usage.path("input_tokens").asLong(-1L);
					completion = usage.path("output_tokens").asLong(-1L);
				}
			}
			case GEMINI, VERTEX_AI -> {
				JsonNode meta = root.path("usageMetadata");
				if (meta.isObject()) {
					prompt = meta.path("promptTokenCount").asLong(-1L);
					completion = meta.path("candidatesTokenCount").asLong(-1L);
				}
			}
			case OLLAMA -> {
				if (root.has("prompt_eval_count") || root.has("eval_count")) {
					prompt = root.path("prompt_eval_count").asLong(-1L);
					completion = root.path("eval_count").asLong(-1L);
				}
			}
			default -> {
			}
		}
		if (prompt >= 0 && completion >= 0) {
			ObjectNode mapped = objectMapper.createObjectNode();
			mapped.put("prompt_tokens", prompt);
			mapped.put("completion_tokens", completion);
			mapped.put("total_tokens", prompt + completion);
			normalized.set("usage", mapped);
		}
	}

	private void recordUsageAndCache(
			JsonNode normalized,
			JsonNode upstreamRoot,
			@Nullable OpenAiChatRequest chatRequest,
			ProviderType providerType,
			String providerName,
			String requestedModel,
			@Nullable String ownerId,
			UUID requestId,
			HttpServletRequest servletRequest,
			@Nullable SettlementContext settlementContext,
			@Nullable ReplayFlight replayFlight
	) {
		try {
			String normalizedJson = objectMapper.writeValueAsString(normalized);
			JsonNode usage = normalized.path("usage");
			long promptTokens = usage.path("prompt_tokens").isNumber() ? usage.path("prompt_tokens").asLong() : 0L;
			long completionTokens = usage.path("completion_tokens").isNumber()
					? usage.path("completion_tokens").asLong() : 0L;
			long costUsdMicros = costCalculator.calculate(
					providerType, requestedModel, promptTokens, completionTokens);
			eventPublisher.publishEvent(new TokenUsageEvent(
					requestId, ownerId, providerName, requestedModel,
					promptTokens, completionTokens,
					promptTokens + completionTokens,
					0L, costUsdMicros, Instant.now(),
					promptTokens, 0L, 0L, 0L,
					costUsdMicros, costUsdMicros, null
			));
			// Non-streaming completion measured: true the hold up to actual spend.
			settleQuietly(settlementContext, costUsdMicros, false);
		// Non-streaming payloads store byte-identical for exact re-delivery.
		replayStoreQuietly(replayFlight, normalizedJson.getBytes(StandardCharsets.UTF_8), false);
			if (cacheService != null && chatRequest != null) {
				try {
					int pt = (int) Math.min(Integer.MAX_VALUE, promptTokens);
					int ct = (int) Math.min(Integer.MAX_VALUE, completionTokens);
					@Nullable VirtualApiKey nonStreamApiKey = (VirtualApiKey) servletRequest
							.getAttribute(KeyAuthFilter.VIRTUAL_KEY_ATTRIBUTE);
					cacheService.storeResponse(
							chatRequest,
							servletRequest,
							ownerId,
							nonStreamApiKey,
							normalizedJson,
							pt,
							ct
					);
				} catch (RuntimeException ex) {
					log.debug("Cache store skipped for non-streaming completion: {}", ex.getMessage());
				}
			}
		} catch (JacksonException ex) {
			log.debug("Usage recording skipped for non-streaming completion: {}", ex.getMessage());
		}
	}

	private @Nullable String extractModel(@Nullable JsonNode root) {
		if (root == null || !root.isObject()) {
			return null;
		}
		JsonNode modelNode = root.get("model");
		return modelNode != null && modelNode.isString() ? modelNode.asString() : null;
	}

	/**
	 * Wires the spend-budget enforcer when present. Optional on purpose: unit-constructed controllers keep working
	 * with budget enforcement silently skipped, exactly like unauthenticated paths never reach them.
	 *
	 * @param budgetEnforcer the enforcer, if available
	 */
	@Autowired(required = false)
	public void setBudgetEnforcer(BudgetEnforcer budgetEnforcer) {
		this.budgetEnforcer = budgetEnforcer;
	}

	/**
	 * Wires the routing decision log writer when present. Optional on purpose: unit-constructed controllers keep
	 * working with decision logging silently skipped, exactly like the budget enforcer.
	 *
	 * @param decisionLogWriter the writer, if available
	 */
	@Autowired(required = false)
	public void setDecisionLogWriter(DecisionLogWriter decisionLogWriter) {
		this.decisionLogWriter = decisionLogWriter;
	}

	/**
	 * Wires the pricing catalog for decision rate annotation when present. Optional like the writer: unit-constructed
	 * controllers keep working with rates left unknown rather than fabricated.
	 *
	 * @param modelPriceCatalog the pricing read side, if available
	 */
	@Autowired(required = false)
	public void setModelPriceCatalog(ModelPriceCatalog modelPriceCatalog) {
		this.modelPriceCatalog = modelPriceCatalog;
	}

	/**
	 * Wires the observation tracer when present. Optional like the other
	 * subsystems: unit-constructed controllers keep working with span tagging
	 * silently skipped. Tagging runs at controller time (inside the server
	 * observation scope); post-chain filters are outside it and must not tag.
	 *
	 * @param tracer the tracer, if available
	 */
	@Autowired(required = false)
	public void setTracer(Tracer tracer) {
		this.tracer = tracer;
	}

	/**
	 * Wires the settlement orchestrator when present. Optional like the enforcer: unit-constructed controllers
	 * keep working with hold-then-settle silently skipped (admission then charges the prompt-only estimate).
	 *
	 * @param budgetSettlement the settlement facade, if available
	 */
	@Autowired(required = false)
	public void setBudgetSettlement(BudgetSettlement budgetSettlement) {
		this.budgetSettlement = budgetSettlement;
	}

	/**
	 * Wires the replay service when present. Optional like the settlement facade: unit-constructed
	 * controllers keep working with idempotent replay silently skipped (retries re-proxy; the budget
	 * dedupe still prevents double-charge).
	 *
	 * @param replayService the replay service, if available
	 */
	@Autowired(required = false)
	public void setReplayService(ReplayService replayService) {
		this.replayService = replayService;
	}

	/**
	 * An owned replay fill: this request won the claim and will store (or release) it. {@code null} when
	 * replay is unavailable, the key is absent, or another flight owns the key.
	 */
	private record ReplayFlight(String key, String bodyHash, @Nullable String budgetClaimId,
	                            @Nullable String tenant, @Nullable String keyHashHex) {
	}

	/**
	 * Settlement state carried from admission to stream end on one request. {@code null} throughout when
	 * settlement is unavailable — every settle call site null-guards, so the response path never depends on
	 * bookkeeping.
	 */
	private record SettlementContext(
			String holdId,
			String keyHex,
			@Nullable String ownerId,
			String origMonth,
			long holdMicros,
			int promptTokens,
			ProviderType budgetType,
			String model
	) {
	}

	/**
	 * Best-effort routing decision observation. Runs after the outcome is known, writes nothing on the request path,
	 * and never throws: a logging fault must not change serving. Skipped when the writer is unwired (unit-test
	 * controllers) and rates stay unknown when the pricing catalog is unwired rather than fabricated.
	 *
	 * @param aliasKey         requested alias key (the catalog name, not the served model)
	 * @param alias            the routing plan that was walked
	 * @param model            the requested model name
	 * @param routingContext   validated routing preferences
	 * @param providerResponse the winning response with tried legs, or {@code null} when all legs failed
	 */
	private void recordDecision(
			String aliasKey,
			ModelAlias alias,
			String model,
			RoutingDecisionContext routingContext,
			@Nullable ProviderResponse providerResponse) {
		DecisionLogWriter writer = this.decisionLogWriter;
		if (writer == null) {
			return;
		}
		try {
			List<String> chain = alias.chain().stream()
					.map(ref -> ref.modelOverride() == null
							? ref.providerName()
							: ref.providerName() + ":" + ref.modelOverride())
					.toList();
			List<String> tried = providerResponse == null
					? List.of()
					: List.copyOf(providerResponse.triedProviders());
			String winner = providerResponse == null ? null : providerResponse.providerName();
			BigDecimal inputRate = null;
			BigDecimal outputRate = null;
			ModelPriceCatalog catalog = this.modelPriceCatalog;
			if (providerResponse != null && catalog != null) {
				ProviderConfig winnerConfig =
						gatewayProperties.getProviders().get(providerResponse.providerName());
				if (winnerConfig != null && winnerConfig.type() != null) {
					String servedModel = model;
					for (ProviderRef ref : alias.chain()) {
						if (ref.providerName().equals(providerResponse.providerName())
								&& ref.modelOverride() != null) {
							servedModel = ref.modelOverride();
						}
					}
					Optional<ModelPricingEntry> price = catalog.lookup(winnerConfig.type(), servedModel);
					if (price.isPresent()) {
						inputRate = price.get().inputCostPerToken();
						outputRate = price.get().outputCostPerToken();
					}
				}
			}
			writer.record(aliasKey, model, routingContext.minQualityTier(), routingContext.tradeoffMode(),
					chain, tried, winner, inputRate, outputRate);
		} catch (RuntimeException ex) {
			log.debug("Dropping routing decision observation: {}", ex.getMessage());
		}
	}

	/**
	 * Admission verdict plus settlement state. {@code denied} is non-null on 429/503 (return it); otherwise
	 * {@code context} carries the hold for stream-end settlement (possibly {@code null} when no hold exists).
	 */
	private record BudgetAdmission(
			@Nullable ResponseEntity<StreamingResponseBody> denied,
			@Nullable SettlementContext context
	) {
	}

	/**
	 * Enforces spend budgets for one cache-miss request. Returns a {@link BudgetAdmission} carrying a 429/503
	 * entity when a cap denies or the budget service is unreachable (uniform fail-closed), or an allow with the
	 * settlement context for stream-end true-up. Skipped (allow, no context) when no enforcer is wired — the bean
	 * is unconditional in production, so a null enforcer only occurs in non-Spring unit-test contexts; or when
	 * the key digest is absent (internal callers such as the semantic-cache warmer have no key to charge).
	 * A malformed digest is internal corruption and fails closed with 503.
	 */
	private BudgetAdmission admitWithBudget(
			ModelAlias alias, String model, @Nullable String ownerId, String trimmed,
			@Nullable OpenAiChatRequest chatRequest, UUID requestId,
			@Nullable String keyHashHex, @Nullable String idempotencyKey, @Nullable String bodyHashHex) {
		BudgetEnforcer enforcer = this.budgetEnforcer;
		if (enforcer == null || keyHashHex == null || keyHashHex.isBlank()) {
			return new BudgetAdmission(null, null);
		}
		ProviderType budgetType;
		try {
			var primary = alias.chain().getFirst();
			ProviderConfig primaryConfig = gatewayProperties.getProviders().get(primary.providerName());
			budgetType = primaryConfig == null ? ProviderType.OPENAI : primaryConfig.type();
		} catch (RuntimeException ex) {
			budgetType = ProviderType.OPENAI;
		}
		final SHA256Hash keyHash;
		try {
			keyHash = SHA256Hash.fromHex(keyHashHex);
		} catch (IllegalArgumentException malformed) {
			return new BudgetAdmission(
					errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "Budget service unavailable"), null);
		}
		int promptTokens = BudgetEnforcer.estimatePromptTokens(trimmed.length());
		Integer maxTokens = chatRequest == null ? null : chatRequest.effectiveMaxTokens();
		final BudgetDecision decision;
		final BudgetEnforcer.HoldAuthorization auth;
		BudgetSettlement settlement = this.budgetSettlement;
		try {
			if (settlement != null) {
				auth = settlement.authorize(
						keyHash, ownerId, budgetType, model, trimmed.length(), maxTokens, idempotencyKey,
						bodyHashHex);
				decision = auth.decision();
			} else {
				decision = enforcer.checkBudget(
						keyHash, ownerId, budgetType, model, promptTokens, idempotencyKey, bodyHashHex);
				auth = null;
			}
		} catch (RateLimitUnavailableException unavailable) {
			return new BudgetAdmission(
					errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "Budget service unavailable"), null);
		}
		if (decision instanceof BudgetDecision.Denied denied) {
			long retryAfter = Math.max(1L, denied.retryAfterSeconds());
			HttpHeaders denyHeaders = new HttpHeaders();
			denyHeaders.setContentType(MediaType.APPLICATION_JSON);
			denyHeaders.set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
			denyHeaders.set("X-Budget-Remaining", "0");
			denyHeaders.set("X-Budget-Reset", Long.toString(System.currentTimeMillis() / 1000L + retryAfter));
			denyHeaders.set("X-Budget-Level", denied.level());
			denyHeaders.set("X-Budget-Window", denied.window());
			String denyBody = "{\"error\":{\"message\":\"budget exhausted (" + denied.level() + " "
					+ denied.window() + ")\"}}";
			return new BudgetAdmission(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).headers(denyHeaders)
					.body(out -> out.write(denyBody.getBytes(StandardCharsets.UTF_8))), null);
		}
		SettlementContext context = null;
		// auth is non-null whenever settlement is (the try above either assigns it or returns).
		if (settlement != null && auth.holdMicros() >= 0) {
			boolean held = settlement.createHold(
					requestId.toString(), keyHash.hex(), ownerId, auth);
			if (held) {
				context = new SettlementContext(requestId.toString(), keyHash.hex(), ownerId,
						auth.holdMonth(), auth.holdMicros(), promptTokens, budgetType, model);
			}
		}
		return new BudgetAdmission(null, context);
	}

	/**
	 * Renders the failover walk for the {@code X-CacheRelay-Tried} header: provider names in
	 * walk order with their leg outcomes. Entries originate from configuration names plus
	 * fixed reason suffixes, so the value is header-safe.
	 *
	 * @param providerResponse upstream outcome carrying the tried list
	 * @return comma-joined walk, or the winner alone when nothing was recorded
	 */
	private static String triedHeader(ProviderResponse providerResponse) {
		if (providerResponse.triedProviders() == null
				|| providerResponse.triedProviders().isEmpty()) {
			return providerResponse.providerName();
		}
		return String.join(",", providerResponse.triedProviders());
	}

	/**
	 * Coarse live-stream hold renewal that can never break the response: the
	 * settlement absorbs every failure and the cadence gate absorbs the clock.
	 *
	 * @param context live-stream settlement context, never {@code null} here
	 * @param lastRenewalNanos {@link System#nanoTime} of the last renewal attempt
	 * @return updated renewal timestamp for the next checkpoint
	 */
	private long renewHoldIfDue(SettlementContext context, long lastRenewalNanos) {
		BudgetSettlement settlement = this.budgetSettlement;
		if (settlement == null) {
			return lastRenewalNanos;
		}
		return settlement.renewHoldIfDue(context.holdId(), lastRenewalNanos, System.nanoTime());
	}

	/**
	 * Stream-end true-up that can never break the response: every failure is logged and absorbed (the hold H
	 * stays counted — the safe over-count direction).
	 */
	private void settleQuietly(@Nullable SettlementContext context, long actualMicros, boolean abort) {		BudgetSettlement settlement = this.budgetSettlement;
		if (settlement == null || context == null) {
			return;
		}
		try {
			settlement.settleStream(context.holdId(), context.keyHex(), context.ownerId(),
					context.origMonth(), actualMicros, abort);
		} catch (RuntimeException ex) {
			log.warn("Stream-end settle failed for hold {}", context.holdId());
		}
	}

	/**
	 * Settles the input-known portion (prompt cost) on abort, failure, or unmeasurable usage. When even the
	 * prompt cannot be priced, settles the hold value itself (no-op delta): no refund without proof.
	 *
	 * @param abort {@code true} for client aborts (re-arms the output hold for the grace window)
	 */
	private void settlePromptKnown(@Nullable SettlementContext context, boolean abort) {
		if (context == null) {
			return;
		}
		long actual;
		try {
			actual = costCalculator.calculate(context.budgetType(), context.model(), context.promptTokens(), 0);
		} catch (RuntimeException ex) {
			actual = context.holdMicros();
		}
		settleQuietly(context, Math.max(0L, actual), abort);
	}

	private ResponseEntity<StreamingResponseBody> errorResponse(HttpStatus status, String message) {
		String body = errorBody(objectMapper, message);
		return ResponseEntity.status(status)
		                     .contentType(MediaType.APPLICATION_JSON)
		                     .body(out -> out.write(body.getBytes(StandardCharsets.UTF_8)));
	}

	/**
	 * Serializes an error body with the mapper, never by concatenation: messages
	 * frequently embed client input (model ids, header values), and raw
	 * interpolation would let a quote break the JSON shape. A serialization
	 * failure degrades to a static body — the failure path itself must never
	 * throw.
	 *
	 * @param mapper  Jackson mapper, never {@code null}
	 * @param message client-facing message
	 * @return serialized error JSON
	 */
	static String errorBody(ObjectMapper mapper, String message) {
		try {
			ObjectNode error = mapper.createObjectNode();
			error.putObject("error").put("message", message);
			return mapper.writeValueAsString(error);
		} catch (RuntimeException failed) {
			return "{\"error\":{\"message\":\"request failed\"}}";
		}
	}

	private void writeSse(OutputStream out, String line) throws IOException {
		out.write(line.getBytes(StandardCharsets.UTF_8));
		out.write('\n');
	}

	private void writeSseError(OutputStream out, int limitBytes, int actualBytes, String provider) throws IOException {
		ObjectNode error = objectMapper.createObjectNode();
		error.put("code", "LINE_TOO_LONG");
		error.put("message", "SSE line exceeds configured maximum of " + limitBytes
				+ " bytes (actual: " + actualBytes + ")");
		error.put("limit", limitBytes);
		error.put("actual", actualBytes);
		error.put("provider", provider);
		String json = objectMapper.writeValueAsString(error);
		writeSse(out, "event: error");
		writeSse(out, "data: " + json);
		writeSse(out, "");
	}

	private void writeSseError(OutputStream out, String code, String message, String provider) throws IOException {
		ObjectNode error = objectMapper.createObjectNode();
		error.put("code", code);
		error.put("message", message);
		error.put("provider", provider);
		String json = objectMapper.writeValueAsString(error);
		writeSse(out, "event: error");
		writeSse(out, "data: " + json);
		writeSse(out, "");
	}
}