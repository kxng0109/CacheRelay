# CacheRelay

> **CacheRelay — Cache *then* relay:** the hot path checks `L0 → L1 → L2` (`X-Cache: HIT` → serve), and only on `MISS` relays to upstream (`relayJson`/`relaySse`).

CacheRelay is an AI gateway built in Java 25 on Spring Boot 4.1. It sits between your applications and large language model providers, exposing a single OpenAI compatible chat completions endpoint while handling authentication, rate limiting, secure upstream forwarding, protocol normalization, and usage based cost accounting.

## Repository layout (monorepo)

- `backend/` — the Java gateway (Maven): `src/`, `pom.xml`, `mvnw`, `Dockerfile`, `docs/`, `deploy/`, `loadtest/`, `monitoring/`. All backend commands below run from `backend/`; all source paths below are relative to `backend/` unless stated.
- `frontend/` — the operator SPA (separate workspace, see `backend/docs/BACKEND_API_REFERENCE.md` for the contract it builds against).
- Root — monorepo tooling only: `docker-compose.yml`, `.github/` workflows, `CHANGELOG.md`, commitlint, `.nvmrc` (Node `24.21.0`).

The project is developed in phases. Phase 1 delivered a transparent SSE streaming proxy with SSRF defense and header
sanitization. Phase 2 added virtual API key authentication and distributed rate limiting backed by Redis. Phase 3 added
resilient multi provider failover with distributed circuit breakers, real-time streaming guardrails (ingress secret
scanning, ephemeral PII vault with Nigerian regulatory compliance, prompt injection defense, streaming JSON PDA
validator, and mid-stream kill switch), and geo-sovereignty compliance with Merkle audit receipts. Phase 4 added
universal protocol normalization (OpenAI, Anthropic Claude, Google Gemini, Google Cloud Vertex AI, DeepSeek V3/R1/V4,
Cohere, native `ollama`), streaming extended reasoning extraction (`<think>` tags and native thinking deltas), a 50,000
RPS
lock-free Disruptor RingBuffer queue, a dual-trigger micro-batch PostgreSQL writer, a resilient append-only Spillway WAL
disk journal, and a FinOps FOCUS 1.4 prompt caching cost engine. Phase 6 added the Enterprise Model Context Protocol
(MCP) Security & Tool Governance Gateway: a unified `POST /v1/mcp` Streamable HTTP endpoint (plus a legacy
`GET /v1/mcp/sse` bridge for Claude Desktop-class clients), federated tool/resource/prompt catalogs across upstream MCP
servers with deterministic `server__tool` namespacing, tool-level RBAC/ABAC on virtual keys, JSON Schema Draft 2020-12
argument validation, credential/injection guardrails on tool I/O, Human-in-the-Loop approval enforcement with
AES-256-GCM resumption tokens, and per-server circuit breaking with auto-pruning of degraded tools.

## What it does

- Streams chat completions from a configured upstream provider chain to your client with zero buffering of the full response body. Each request runs on its own virtual thread.
- Authenticates every request with a gateway managed virtual API key. Keys are shown to the caller only once and are stored exclusively as SHA-256 hashes.
- Enforces two independent limits per key: requests per minute (RPM) and tokens per minute (TPM). The check and consume step is atomic, executed in a single Redis Lua script.
- Fails closed. If Redis is unreachable, requests are rejected with a 503 instead of being allowed through unthrottled.
- Rejects oversized request bodies with a 413 before they can exhaust memory.
- Routes every model through an ordered provider chain. When a provider returns a transient error (429, a 5xx, a dropped connection, or a timeout), the request automatically fails over to the next provider.
- Protects each provider with a circuit breaker that is shared across all gateway instances through Redis. After a few consecutive failures the provider is skipped for a cooldown period, then a single probe from one instance decides whether normal service resumes.
- Never fails over on 401, 403, or 400, because a client or key problem cannot be fixed by another provider.
- Validates upstream URLs against private, loopback, link local, multicast, and cloud metadata ranges before any connection is attempted.
- Strips client supplied identity and authorization headers and injects the configured upstream key.
- Never logs key material. Every sensitive value is wrapped so that its string representation is masked.
- Enforces a hard byte limit on every upstream SSE line during byte decoding before string materialization, immediately cancelling the upstream connection with an RST_STREAM frame if an oversized line arrives. See `proxy/sse`.
- Protects downstream clients with an adaptive SSE flush strategy that batches lines and flushes on line count or elapsed time, paired with a write watchdog and hot-reloadable configuration.
- Normalizes every provider dialect to the OpenAI SSE contract, so one client endpoint works with OpenAI compatible, Anthropic Claude, Google Gemini, Google Cloud Vertex AI, DeepSeek, Cohere, and Ollama upstreams. See `proxy/protocol`.
- Normalizes streaming reasoning thoughts across providers: parses `<think>...</think>` tags on the fly with an $O(1)$ sliding carry window and maps native thinking deltas (`thinking_delta`, `message.thinking`) into `choices[0].delta.reasoning_content`.
- Employs a 50,000 RPS lock-free circular RingBuffer queue (`DisruptorUsageLedgerQueue`, $N=65,536$) with atomic CAS sequence claiming for sub-microsecond latency ($<1\mu\text{s}$) and zero carrier thread pinning under Project Loom.
- Batches PostgreSQL ledger writes via a background Virtual Thread worker (`MicroBatchLedgerWriter`) using dual triggers ($B \ge 5000 \lor \Delta t \ge 50\text{ms}$) and JDBC batch rewrites (`rewriteBatchedInserts=true`), cutting database IOPS by 99.8%.
- Guarantees zero ledger record loss during database outages using an append-only Spillway WAL disk journal (`SpillwayJournalManager`) with atomic file rotation and automatic background replay upon database recovery.
- Computes FinOps FOCUS 1.4 prompt caching cost telemetry using 64-bit micro-dollar fixed-point integer math (`FinOpsPromptCacheCalculator`) supporting Anthropic, OpenAI, and DeepSeek cache pricing multipliers.
- Keeps prices current without manual edits. The gateway syncs the LiteLLM model pricing catalog into the database once a day, and the price table is seeded at first migration.
- Scans ingress request payloads in real-time for secret and credential leakage (OpenAI, Anthropic, AWS, GCP, GitHub, Slack, HuggingFace, private keys) using high-speed prefix filtering, branchless Shannon entropy checks ($H (X) \ge 4.2$), and algorithmic checksums with zero false positives.
- Anonymizes PII before prompt forwarding with semantic surrogates (`<PERSON_1>`, `<EMAIL_1>`, `<PHONE_1>`, `<IBAN_1>`, `<CARD_1>`) and an ephemeral AES-256-GCM request-scoped vault. Supports Nigerian PII (NCC phone numbering, NIMC NIN, CBN/NIBSS BVN, Verve card Luhn, Tax IDs) with a 4-tier disambiguation pipeline.
- Reconstitutes PII tokens in outbound SSE streams on the fly using a bounded lookahead Sliding Window Aho-Corasick automaton with zero buffering and <0.1ms latency overhead.
- Defends against prompt injection and jailbreaks with UTS #39 Unicode homoglyph flattening, multi-tier cascaded screening, and prevents system prompt exfiltration with 5-gram token shingling and Bloom filters.
- Incrementally validates streaming JSON schema outputs byte-by-byte using a 64-bit integer stack Pushdown Automaton (PDA).
- Provides mid-stream guardrail kill-switch (`TERMINATE_WITH_ERROR`) emitting compliant SSE error events and immediately sending HTTP/2 `RST_STREAM(CANCEL)` frames upstream to stop GPU token billing.
- Enforces Geo-Sovereignty and Data Residency (`STRICT_SOVEREIGN`, `SOVEREIGN_CASCADE`, `PERMISSIVE_FAILOVER_WITH_AUDIT`), Zero Data Retention headers, and cryptographic SHA-256 Merkle audit ledger non-repudiation receipts.
- Charges every admitted stream a **hold** (`prompt + max_tokens × output` micro-dollars, mandatory server-side `max_tokens` ceiling) through an atomic Lua gate and trues it up to measured actual spend at stream end with exactly-once semantics. Client aborts settle the input-known portion and re-arm the output hold for a grace window; crashes expire the hold to $0 and write an append-only gap row — counters can never silently under-count. A 30s advisory-locked sweeper reclaims orphaned holds across pods.
- **Replays** exact byte-identical responses for idempotent retries (`Idempotent-Replayed: true`, no upstream spend) from a Redis hot tier + partitioned PostgreSQL durable tier, distinct from the semantic cache. Same key + different body → 422, concurrent duplicate → 409, aborted streams never auto-replay.
- Runs a **background spend watchdog** (never on the hot path): static 50/90/100% thresholds, an EWMA exhaustion forecast, and a z-score anomaly detector, all writing an outbox that an advisory-locked dispatcher delivers to Alertmanager v2 and then to opt-in email / Microsoft Teams / Slack / signed webhooks — with a PII-free payload and an append-only tamper-evident audit hash chain.
- Keeps spend accounting on a **dedicated noeviction Redis** while the cache tier (exact/vector/replay-hot) lives on a separate evictable instance, so cache pressure can never erase a spend counter.

## How a request flows

An incoming request to `/v1/chat/completions` passes through several stages:

1. `RequestBodyCachingFilter` (Order 0) wraps the request in a `CachedBodyHttpServletRequest`. The body is buffered
   once, up to a configured cap, so it can be re-read by downstream filters. See
   `security/filter/RequestBodyCachingFilter.java` and `security/filter/CachedBodyHttpServletRequest.java`.

2. `KeyAuthFilter` (Order 1) authenticates the bearer token, checks the model allow list, estimates the token cost of
   the request, and consults the rate limiter. It sets the `X-RateLimit` response headers and either continues the chain
   or answers with 401, 403, 429, or 503. See `security/filter/KeyAuthFilter.java`.

3. `IngressSecurityFilter` (Order 2) executes high-throughput ingress guardrail inspection. In `ENFORCE` mode, it
   rejects requests containing hardcoded credentials (OpenAI, Anthropic, AWS, GCP, GitHub, Slack, etc.) or prompt
   injection attempts with an RFC 9457 `ProblemDetail` (HTTP 422). If PII is present, it encrypts the entities into a
   request-scoped `EphemeralPiiVault` (AES-256-GCM), replaces values with semantic surrogate tokens (`<PERSON_1>`,
   `<EMAIL_1>`, `<PHONE_1>`, etc.), and forwards an `AnonymizedBodyHttpServletRequest`. See
   `security/filter/IngressSecurityFilter.java`.

4. `ProxyController` resolves the requested model to a `ModelAlias` and asks `FailoverOrchestrator` to pick a winning
   provider. The orchestrator filters providers against the tenant's geo-sovereignty policy (`STRICT_SOVEREIGN`,
   `SOVEREIGN_CASCADE`, `PERMISSIVE_FAILOVER_WITH_AUDIT`), walks the chain, and returns the streaming response. During
   streaming relay, the controller incrementally validates JSON outputs via `StreamingJsonPdaValidator`, de-anonymizes
   surrogates in real-time via `SlidingWindowAhoCorasick`, monitors for system prompt exfiltration, injects
   `X-CacheRelay-Audit-Receipt` and Zero Data Retention headers, and executes a mid-stream kill-switch
   (`TERMINATE_WITH_ERROR`) if violations occur. See `proxy/ProxyController.java`, `security/guardrail/*`, and
   `security/compliance/*`.

Filter registration and ordering are defined in `security/filter/SecurityFilterConfig.java`.

## Multi provider failover

Providers are configured under `gateway.providers`, each with a name, a protocol type, a base URL, an API key, and timeouts. Models are mapped to a chain under `gateway.aliases`:

```yaml
gateway:
  providers:
    openai:
      type: OPENAI
      base-url: https://api.openai.com
      api-key: ${OPENAI_API_KEY:}
      connect-timeout: 5s
      request-timeout: 60s
    openrouter:
      type: OPENAI
      base-url: https://openrouter.ai/api
      api-key: ${OPENROUTER_API_KEY:}
      connect-timeout: 5s
      request-timeout: 60s
    anthropic:
      type: ANTHROPIC
      base-url: https://api.anthropic.com
      api-key: ${ANTHROPIC_API_KEY:}
      connect-timeout: 5s
      request-timeout: 60s
    ollama:
      type: OLLAMA
      base-url: http://localhost:11434
      api-key: ""
      connect-timeout: 3s
      request-timeout: 120s
  aliases:
    gpt-56-sol:
      chain:
        - provider-name: openai
          model-override: gpt-5.6-sol
      strategy: SEQUENTIAL
    claude-sonnet-5:
      chain:
        - provider-name: anthropic
      strategy: SEQUENTIAL
    local-llama:
      chain:
        - provider-name: ollama
          model-override: llama3.2
      strategy: SEQUENTIAL
    fast:
      chain:
        - provider-name: openai
          model-override: gpt-5.6-luna
        - provider-name: openrouter
          model-override: gpt-5.6-luna
        - provider-name: ollama
          model-override: llama3.2
      strategy: SEQUENTIAL
```

The `type` field selects the protocol dialect. `OPENAI` covers OpenAI itself plus every pre-wired
OpenAI-compatible entry (OpenRouter, Together, Groq, Mistral, xAI, DeepSeek, DeepInfra, Fireworks, Cerebras,
SambaNova, Nebius, Novita, Moonshot, Zhipu, MiniMax, Qwen, StepFun, Cloudflare, Hyperbolic, io.net, FriendliAI,
Bedrock Bearer-key, vLLM, llama.cpp, LM Studio). `ANTHROPIC` speaks the Anthropic Messages API, and `OLLAMA`
speaks the native Ollama chat API. The full set of shipped providers and aliases lives in `backend/src/main/resources/application.yml`. Each entry is inert until its key is set and an alias chain references it. The `model-override` on a chain step pins the concrete upstream model for that provider, which is how a client facing name maps to a provider specific id.

The behavior of a chain is decided by the classification rules in `FailoverOrchestrator`:

- A 200 response with a streaming content type, either an event stream or newline delimited JSON, is a success.
- A 429 or any 5xx is transient and fails over to the next provider.
- A 401, 403, or 400 is non transient and is returned to the client as is.
- Timeouts and connection failures are transient and fail over.
- Failover happens only before the first byte is sent to the client. Once streaming starts, switching providers is impossible.

Each provider has a circuit breaker whose state is held in Redis and shared by every gateway instance, with a local in-memory mirror as a fallback. It starts closed, opens after three consecutive failures, stays open for thirty seconds, then admits a single probe owned by one instance. A successful probe closes the circuit; a failed probe reopens it. See `proxy/failover/RedisCircuitBreaker.java` and `proxy/failover/ProviderCircuitBreaker.java`.

When every provider fails, the client sees a clean error: 502 when providers returned errors, 503 when nothing usable was reachable, 504 when the chain timed out. See `proxy/failover/GatewayExceptionHandler.java`.

## Protocol normalization

CacheRelay keeps one client contract, the OpenAI chat completions shape, and translates each provider's native protocol behind it. The `type` field on a provider selects the dialect:

- `OPENAI` speaks the OpenAI chat completions protocol directly. This covers OpenAI itself and all
  pre-wired compatible entries (see `application.yml`: Together, Groq, Mistral, xAI, DeepInfra, Fireworks,
  Cerebras, SambaNova, Nebius, Novita, Moonshot, Zhipu, MiniMax, Qwen, StepFun, Cloudflare, Hyperbolic,
  io.net, FriendliAI, Bedrock Bearer-key, vLLM, llama.cpp, LM Studio, plus OpenRouter).
- `ANTHROPIC` speaks the Anthropic Messages API (`/v1/messages`). Requests are translated with tool declarations, tool
  choices, and Anthropic streaming events are rewritten into OpenAI shaped chunks.
- `GEMINI` speaks the Google AI Studio Gemini Developer API (`generativelanguage.googleapis.com`), supporting
  `systemInstruction`, `contents`, and streaming reasoning thoughts (`thought: true`).
- `VERTEX_AI` speaks the Google Cloud Vertex AI REST API (`aiplatform.googleapis.com`) with OAuth2/Bearer
  authentication.
- `DEEPSEEK` speaks the DeepSeek API (`api.deepseek.com`), supporting hybrid reasoning thinking mode (`thinking`,
  `reasoning_effort`), prompt caching telemetry, and streaming `reasoning_content`.
- `OLLAMA` speaks the native Ollama chat API (`/api/chat`), which streams newline delimited JSON.

The translation lives in `proxy/protocol`. `ProtocolAdapterResolver` picks the adapter for a provider type. Each adapter
builds the native URL, headers, and request body, and each normalizer rewrites the upstream stream back to the client
contract. A normalizer is created fresh per stream and captures the token counts plus the model the provider reports,
which feed the ledger. See `proxy/protocol/UniversalToolNormalizer.java`, `proxy/protocol/ThinkingStreamStateNormalizer.java`,
`proxy/protocol/GeminiAdapter.java`, `proxy/protocol/DeepSeekAdapter.java`, `proxy/protocol/AnthropicAdapter.java`,
`proxy/protocol/OllamaAdapter.java`, and `proxy/protocol/OpenAiPassthroughAdapter.java`.

### Extended Reasoning & `<think>` Tag Stream Normalization

CacheRelay provides zero-buffer streaming normalization of extended reasoning and thinking traces across model providers:

- **Sliding-Window State Machine (`ThinkingStreamStateNormalizer`)**: Inspects upstream chunk deltas using a bounded $O(1)$ carry window ($\le 8$ characters) to detect `<think>` and `</think>` tags across arbitrary chunk boundaries.
- **Reasoning Content Separation**: Strips `<think>` tags and routes reasoning tokens exclusively to `choices[0].delta.reasoning_content` while emitting final output to `choices[0].delta.content`.
- **Native Dialect Extraction**: Normalizes Anthropic `thinking_delta` content blocks and Ollama NDJSON `message.thinking` streams into standard OpenAI reasoning deltas in real time.
- **Granular Token Telemetry**: Tracks `reasoning_tokens`, `prompt_eval_count`, `eval_count`, Anthropic cache tokens (`cache_creation_input_tokens`, `cache_read_input_tokens`), and DeepSeek `prompt_cache_hit_tokens` in `usage` chunks.

### Universal Tool & Function Calling Normalization

CacheRelay provides universal tool and function calling across all upstream providers:

- Translates canonical OpenAI `tools` definitions to Anthropic `input_schema` and Google Gemini OpenAPI 3.0 UPPERCASE
  types (`OBJECT`, `STRING`, `INTEGER`, `NUMBER`, `BOOLEAN`, `ARRAY`).
- Maps `tool_choice` directives (`"auto"`, `"required"`, `"none"`, and named function descriptors) across dialects.
- Normalizes conversational multi-turn tool loops: rewrites OpenAI `role: "tool"` execution results into Anthropic
  `role: "user"` `tool_result` blocks and Gemini `role: "user"` `functionResponse` parts.
- Generates stateless, deterministic synthetic tool call IDs (`call_gen_...`) for providers that omit IDs natively.
- Relays streaming tool arguments incrementally across SSE chunk deltas in real time.

Cost is attributed against the model the provider reports, falling back to the requested model when the provider never reports one. On the OpenAI compatible path the gateway always asks the upstream for usage so it can bill, but the usage chunk is only relayed to a client that explicitly asked for it.

## Model Context Protocol (MCP) Gateway

CacheRelay acts as an enterprise Model Context Protocol (MCP) security and tool governance gateway between MCP clients
(Claude Desktop, Cursor-class IDE integrations, custom MCP SDKs) and any number of upstream MCP servers (PostgreSQL,
GitHub, local sandboxes, SaaS tools). It exposes one client-facing endpoint per protocol era:

- **`POST /v1/mcp` — Streamable HTTP (protocol `2026-07-28`)**: STATELESS JSON-RPC 2.0 requests with fast-path L7 header
  routing (`Mcp-Method`, `Mcp-Name`, MIME Base64 sentinels), `MCP-Protocol-Version` negotiation, and zero-buffer
  proxying over virtual threads.
- **`GET /v1/mcp/sse` + `POST /v1/mcp/message` — Legacy HTTP+SSE bridge (protocols `2025-11-25` / `2024-11-05`)**:
  backwards-compatible endpoint event handshake for pre-2026 clients.

Supported methods: `initialize`, `ping`, `tools/list`, `tools/call`, `resources/list`, `prompts/list`, and the
`notifications/*` lifecycle notifications (with instant L0 catalog invalidation on `notifications/tools/list_changed`).

### Tool governance & security model

- **Federated catalogs with deterministic namespacing**: `McpCatalogAggregator` fans out `tools/list`, `resources/list`,
  and `prompts/list` to every enabled upstream server in parallel virtual threads, then presents the union to clients as
  `server_id__tool_name` (e.g. `postgres__run_query`). This prevents tool shadowing, namespace collisions, and prompt
  hijacking across servers.
- **Tool-level RBAC/ABAC**: every virtual API key carries `allowedTools` / `deniedTools` glob policies (e.g.
  `postgres__*`, `*:delete_*`). Tools are pruned from `tools/list` per caller and denied at `tools/call` with JSON-RPC
  error `-32603`. Glob matching is linear-time (no regex, no ReDoS) with ASCII-only case folding; policy sets are
  capped at 64 patterns of 256 chars each (blank patterns rejected with 400).
- **Resource/prompt visibility**: keys carry `allowedResources` / `deniedResources` (URI globs, e.g.
  `postgres://*`) and `allowedPrompts` / `deniedPrompts` (name globs); deny wins, empty means visible, and null
  callers fail closed to an empty catalog. Keys stored before these fields default to fully visible.
  Prompt patterns match the namespaced name (`server__prompt`, e.g. `server__review_*`); `resources/list` and
  `prompts/list` are filtered per caller — `resources/read` / `prompts/get` are not dispatched and not advertised.
- **Egress injection policy**: indirect prompt injection markers in tool output block delivery by default
  (JSON-RPC `-32603`, offending output never returned); per-key `injectionBlock` lets an admin flip
  noisy-but-legitimate keys to warn-and-deliver. Output is wrapped in a nonce-bound envelope (tool name escaped);
  non-text (binary/image) blocks bypass screening and are counted. Metrics: `mcp_egress_injection_detected_total`,
  `mcp_egress_blocked_total`, `mcp_egress_unscanned_total`.
- **JSON Schema Draft 2020-12 parameter validation**: tool arguments are validated strictly (required fields, types,
  string bounds, regex formats, IEEE 754 safe-integer limits, `additionalProperties: false`) with dangerous-path
  pre-filtering for path traversal and command separators.
- **Ingress/egress guardrails**: tool arguments are scanned for credential leakage before dispatch; tool results are
  wrapped in nonced `<tool_result nonce="..." context="EXTERNAL_UNTRUSTED_DATA">` tags and screened for indirect prompt
  injection markers.
- **Human-in-the-Loop (HITL) execution suspension**: tools declared via `hitlRequiredTools` are suspended with an MCP
  `InputRequiredResult` carrying an AES-256-GCM `requestState` resumption token (SHA-256 argument fingerprint, tenant
  binding, 300-second TTL, single-use Redis replay protection). Administrators review and approve/reject via
  `GET/POST /v1/admin/mcp/approvals/{tokenId}[/approve|/reject]`.
- **Resilience**: each upstream server runs an in-memory atomic CAS circuit breaker (`McpServerCircuitBreakerManager`);
  tripped servers are automatically pruned from the federated catalog and reject calls with error `-32603`. A dedicated
  HTTP/2 multiplexed client (`mcpHttpClient`, `Redirect.NEVER`) keeps connection counts and SSRF exposure minimal.
- **Per-key request throttling on `tools/call`**: the virtual key's RPM applies with the token dimension untouched;
  rejections answer HTTP `429` with `Retry-After` and a JSON-RPC `-32603` body, limiter outages fail closed with
  `503`. Lists and ping stay unthrottled (cached, local).

### Configuration

Configure upstream servers under the `gateway.mcp` prefix in `application.yml`:

```yaml
gateway:
  mcp:
    enabled: true
    default-protocol-version: "2026-07-28"
    allow-legacy-sse: true
    hitl-suspension-ttl: 300s
    hitl-secret: ${GATEWAY_MCP_HITL_SECRET}  # 32+ byte secret for AEAD resumption tokens (REQUIRED, no default)
    circuit-breaker-failure-threshold: 3
    circuit-breaker-cooldown: 30s
    catalog-cache-ttl: 5m
    catalog-refresh-cron: "0 */5 * * * *"
    servers:
      postgres:
        transport: STREAMABLE_HTTP
        base-url: "http://mcp-postgres:8080"
        api-key: ${POSTGRES_MCP_KEY}
        connect-timeout: 5s
        request-timeout: 30s
        hitl-required-tools: [ "execute_sql", "*:delete_*" ]
      github:
        transport: STREAMABLE_HTTP
        base-url: "http://mcp-github:8080"
```

## A2A agent proxy

CacheRelay also fronts upstream A2A (Agent-to-Agent) agents with the same governance posture as MCP tools: a
virtual-key authenticated JSON-RPC relay with per-key agent allow/deny lists, per-agent circuit breaking, bounded
request/response bodies, and redirect-disabled upstream calls. `message/send`, `tasks/get`, and `tasks/cancel` are relayed verbatim as JSON; `message/stream`
answers `text/event-stream` relayed byte-for-byte (transparent: each upstream SSE `data:` frame is a
complete JSON-RPC response, and a mid-stream upstream failure simply closes the stream so clients detect
the missing `final:true`). Unknown methods answer `-32601`, notifications are accepted with `202`, and RPM
exhaustion answers `429` with `Retry-After`. Push-notification configuration, task resubscription, gRPC,
and HTTP+JSON transports remain explicit non-goals.

| Endpoint | Purpose |
|---|---|
| `GET /.well-known/agent-card.json` | Public gateway discovery card (A2A v0.3). Deliberately discloses **no** agent inventory. |
| `POST /v1/a2a/{agent}` | JSON-RPC relay to a registered agent, authenticated by a virtual key (`Authorization: Bearer gw-...`), exactly like `/v1/mcp/**`. |
| `GET /v1/a2a/{agent}/card` | The upstream agent card with its `url` (and `additionalInterfaces[].url`) rewritten to the gateway address. Unknown and denied agents are indistinguishable (`404`). |

Local policy decisions follow the MCP convention so clients never branch on codes: JSON-RPC errors are
standardized on `-32603` (access denied, breaker open, agent unavailable), `-32700` for malformed JSON, `-32600`
for batches and oversized bodies, `-32601` for unsupported methods.

A rejected RPM budget answers HTTP `429` with `Retry-After` (fail-closed `503` when the limiter is down).
Per-key agent access uses `allowedAgents`/`deniedAgents` on virtual keys (create/update key endpoints): an empty
allow list means all agents, the deny list always wins, and glob patterns are supported.

```yaml
gateway:
  a2a:
    enabled: true
    public-base-url: ${GATEWAY_PUBLIC_BASE_URL:http://localhost:8080}
    max-request-bytes: 1048576
    max-result-bytes: 1048576
    circuit-breaker-failure-threshold: 3
    circuit-breaker-cooldown: 30s
    client-connect-timeout: 5s
    client-request-timeout: 60s
    # Streams are bounded by spring.mvc.async.request-timeout (10m in this stack).
    agents:
      research-agent:
        base-url: "https://agents.internal/a2a"
        api-key: ${A2A_RESEARCH_AGENT_KEY:}       # optional upstream Bearer credential
        protocol-version: "0.3"                    # optional pin for this agent
        card-path: "/.well-known/agent-card.json"  # optional upstream card path
        enabled: true
```

## Usage and cost ledger

Every completed stream that carries token usage is written to a PostgreSQL ledger through an asynchronous, lock-free, zero-loss ingestion pipeline:

1. **50,000 RPS Lock-Free RingBuffer (`DisruptorUsageLedgerQueue`)**: After the last SSE chunk is flushed, `ProxyController` publishes a `TokenUsageEvent`. The event is enqueued into a power-of-two circular buffer ($N=65,536$) using atomic CAS sequence claiming. Enqueue overhead is $<1\mu\text{s}$ with zero allocation and zero carrier thread pinning on Loom virtual threads.
2. **Dual-Trigger Micro-Batch Writer (`MicroBatchLedgerWriter`)**: A dedicated background virtual thread drains the queue into PostgreSQL using dual triggers: batch size threshold ($B \ge 5{,}000$ records) or time interval ($\Delta t \ge 50\text{ms}$). Using JDBC rewritten batching (`rewriteBatchedInserts=true`), single multi-row insert statements cut database write operations and connection contention by $99.8\%$.
3. **Spillway WAL Disk Journal (`SpillwayJournalManager`)**: If PostgreSQL experiences an outage, records are appended immediately to a resilient append-only WAL disk journal (`logs/ledger-deadletter.log`). When the database recovers, an automatic background worker rotates the active file via atomic staging rename and replays batched records with zero data loss.
4. **FinOps FOCUS 1.4 Financial Calculation Engine (`FinOpsPromptCacheCalculator`)**: Usage records compute exact costs in 64-bit micro-dollar fixed-point integers ($\mu\text{USD}$) using `RoundingMode.HALF_UP`. It applies canonical prompt caching multipliers (Anthropic $1.25\times$ write / $0.10\times$ read, OpenAI $0.50\times$ read, DeepSeek $0.00\times$ write / $0.10\times$ read), reporting `uncached_prompt_tokens`, `cache_read_tokens`, `cache_write_tokens`, `reasoning_tokens`, `effective_cost_micros`, and `billed_cost_micros`.

The schema is owned by Flyway migrations under `backend/src/main/resources/db/migration` (`V1__init.sql` through `V4__finops_focus_prompt_caching.sql`). Boot's Flyway autoconfiguration is disabled, Hibernate never creates or validates the schema, and `config/DatabaseMigrator.java` applies the migrations once the database is reachable, retrying on a schedule. While the database is down, ledger writes gracefully divert to the Spillway journal and the proxy hot path keeps working.

Costs come from a pricing catalog. `ledger/PricingSyncService.java` fetches the LiteLLM model pricing file (the URL is configurable and can be pinned to a tag or commit), keeps the chat oriented entries, and upserts them into `model_pricing`. It runs at startup and then daily at 03:00. `ledger/ModelPriceCatalog.java` serves lookups from
a short lived cache with exact id, provider composite, and longest prefix matching, and `ledger/CostCalculator.java`
computes cost in micro dollars. The sync is strictly best effort. A failed fetch leaves the previous rows in place,
and the seed rows in `V2__model_pricing.sql` cover the shipped aliases from the first migration. The startup sync
runs on a bounded async executor (never gates readiness) with exponential backoff on transport failures
(`gateway.pricing.max-attempts` default 5, `gateway.pricing.backoff-base-seconds` default 5); parse errors fail
fast.

Cost-router phase 1 is observation only: every chat request may emit one row into `routing_decision_log`
(sampled, `gateway.routing.decision-log-sample-per-mille` default 10) recording alias, model, quality
floor, tradeoff mode, planned chain, tried legs, winner, and known price rates - identifiers and rates
only, never prompts or keys. Clients may send `X-CacheRelay-Min-Quality-Tier` (FRONTIER/STANDARD/BUDGET)
and `X-CacheRelay-Tradeoff-Mode` (`quality`, default; `eco` is accepted but unenforced); unknown values
are `400`. Nothing routes on cost yet: effective policy stays quality-first.

## Project layout

The code is organized by responsibility under `backend/src/main/java/io/github/kxng0109/cacherelay`:

- `contracts` contains shared immutable types: `SHA256Hash`, `VirtualApiKey`, `RateLimitDecision`, `RateLimitState`, `RejectionReason`, `BootstrapKey`, `ProviderConfig`, `ProviderRef`, `ModelAlias`, `ProviderType`, `FailoverStrategy`, and `GatewayProperties`.
- `security` contains Phase 1 controls: `SsrfValidator`, `HeaderSanitizer`, and `CidrRange`.
- `security/filter` contains the servlet filter pipeline: `RequestBodyCachingFilter`, `CachedBodyHttpServletRequest`, `KeyAuthFilter`, `IngressSecurityFilter`, `AnonymizedBodyHttpServletRequest`, and `SecurityFilterConfig`.
- `security/ratelimit` contains the distributed rate limiter: `RateLimitEngine`, `RateLimitScriptConfig`, `KeyManagementService`, and `BootstrapKeySeeder`.
- `security/guardrail` contains real-time security scanners: `IngressSecretScanner`, `ShannonEntropyCalculator`, `LuhnValidator`, `ConfusablesFilter`, `PiiScanner`, `EphemeralPiiVault`, `PiiDisambiguationEngine`, `PromptInjectionScanner`, `SystemPromptProtectionEngine`, `StreamingJsonPdaValidator`, `SlidingWindowAhoCorasick`, `MidStreamKillSwitch`, `IbanValidator`, `BytePrefixTrie`, and `SecretScannerRuleDatabase`.
- `security/compliance` contains residency and audit controls: `GeoSovereigntyRouter`, `Jurisdiction`, `ResidencyPolicy`, `MerkleAuditLedger`, and `ZeroDataRetentionEnforcer`.
- `cache` contains the multi-tier caching engine: `CacheRelayCacheService`, `InMemoryExactCache`, `RedisExactCache`, `RediSearchVectorClient`, `RedisSemanticVectorCache`, `SingleFlightManager`, `CachedStreamReconstitution`, `CachePolicyEngine`, `CacheGuardrails`, `CacheKeyGenerator`, `AdminCacheController`, and `CacheRelayCacheProperties`.
- `proxy/embeddings` contains the embedding gateway: `EmbeddingService`, `EmbeddingBatchOrchestrator`, `EmbeddingAdapterResolver`, `OpenAiEmbeddingAdapter`, `CohereEmbeddingAdapter`, `OllamaEmbeddingAdapter`, `EmbeddingController`, and `VectorEncodingUtils`.
- `proxy/failover` contains the routing and resilience layer: `FailoverOrchestrator`, `CircuitBreaker`, `CircuitBreakerFactory`, `RedisCircuitBreaker`, `RedisCircuitBreakerFactory`, `ProviderCircuitBreaker`, `CircuitBreakerConfig`, `CircuitBreakerMetrics`, `CircuitBreakerProperties`, `InstanceId`, `ProviderClientAdapter`, `ProviderResponse`, `UpstreamUnavailableException`, and `GatewayExceptionHandler`.
- `proxy/protocol` contains dialect adapters and SSE normalizers: `ProtocolAdapterResolver`, `OpenAiPassthroughAdapter`, `AnthropicAdapter`, `GeminiAdapter`, `DeepSeekAdapter`, `OllamaAdapter`, `UniversalToolNormalizer`, `ThinkingStreamStateNormalizer`, `AnthropicSseNormalizer`, `GeminiSseNormalizer`, `DeepSeekSseNormalizer`, `OllamaSseNormalizer`, and `OpenAiSseNormalizer`.
- `proxy/sse` contains streaming protection and guardrails: `BoundedLineBodyHandler`, `DefaultSseLineGuard`, `DefaultSseLineGuardFactory`, `SseLineGuardProperties`, `SseLineGuardAutoConfig`, `AdaptiveSseFlushStrategy`, `SseFlushStrategy`, `SseFlushProperties`, `SseFlushAutoConfig`, `SseFlushConfigReloader`, `SseFlushHealthIndicator`, `TokenBucket`, and `LineTooLongException`.
- `ledger/queue` contains high-throughput ingestion components: `DisruptorUsageLedgerQueue` and `MicroBatchLedgerWriter`.
- `mcp/contracts` contains the MCP JSON-RPC 2.0 domain models: `McpJsonRpcRequest`, `McpJsonRpcResponse`,
  `McpJsonRpcError`, `McpToolDefinition`, `McpResourceDefinition`, `McpPromptDefinition`, `McpServerConfig`,
  `McpTransportType`, `McpPromptArgument`, and `McpProtocolVersion`.
- `mcp/config` contains the MCP gateway configuration (`McpGatewayProperties`, prefix `gateway.mcp`) and the dedicated
  HTTP/2 upstream client (`McpHttpClientConfig`).
- `mcp/protocol` contains the MCP transports: `McpStreamableHttpController` (Streamable HTTP 2026-07-28 + legacy SSE
  bridge), `McpHeaderNormalizer` (L7 fast-path headers and MIME Base64 sentinels), and `McpSseEventFormatter`.
- `mcp/router` contains the federated catalog layer: `McpRouter` (deterministic `server__tool` namespacing),
  `McpCatalogAggregator` (parallel virtual-thread federation), `McpCatalogCache` (L0 Caffeine W-TinyLFU),
  `McpAggregatedCatalog`, and `McpResolvedRoute`.
- `mcp/security` contains the tool governance firewall: `McpToolRbacPolicyEngine` (glob allow/deny),
  `McpJsonSchemaValidator` (Draft 2020-12), and `McpGuardrailScanner` (credential scanning + nonced tool-result
  wrapping).
- `mcp/hitl` contains Human-in-the-Loop governance: `McpHitlSuspensionEngine`, `McpAeadResumptionTokenService`
  (AES-256-GCM), `McpResumptionClaims`, and `AdminMcpApprovalController` (`/v1/admin/mcp/approvals/**`).
- `mcp/resilience` contains `McpServerCircuitBreakerManager` with per-server atomic CAS circuit breaking and catalog
  auto-pruning.
- `a2a/config` contains the A2A gateway configuration (`A2aGatewayProperties`, prefix `gateway.a2a`), the agent
  registry model (`A2aAgentConfig`), and the dedicated redirect-disabled HTTP client.
- `a2a/protocol` contains the A2A surfaces: `A2aProxyController` (JSON-RPC relay + rewritten agent cards) and
  `A2aDiscoveryController` (public well-known gateway card).
- `a2a/registry`, `a2a/security`, and `a2a/resilience` contain the agent registry, the agent RBAC policy engine
  (`allowedAgents`/`deniedAgents` glob semantics, deny-first), and per-agent circuit breakers.
- `ledger` contains the FinOps ledger engine: `FinOpsPromptCacheCalculator`, `SpillwayJournalManager`, `UsageLedgerListener`, `UsageLedgerRepository`, `UsageLedgerRepositoryImpl`, `UsageLedgerService`, `CostCalculator`, `ModelPriceCatalog`, `ModelPricingRepository`, and `PricingSyncService`.
- `budget` contains the spend-gate engine plus settlement and watchdog internals: `BudgetEnforcer`,
  `BudgetSettlement`, `BudgetHoldSweeper`, `AdvisoryLock`, `BudgetDetector`, `AlertEvent`/`AlertDispatcher`/
  `AlertmanagerClient`, notification preferences/service, and `RetentionJanitor`.
- `replay` contains the idempotent replay store: `ReplayService` (Redis hot + PG durable), `ReplayRecord`/
  `ReplayId`/`ReplayRepository`.
- `notify` contains the opt-in delivery senders: `ChannelSender`, `TeamsSender`, `SlackSender`, `WebhookSender`
  (Stripe-style HMAC), `GraphEmailSender`, `NotificationFanout`, `NotificationPayload`.
- `cache/config` contains the cache-tier Redis wiring (`CacheRedisConfig`, `CacheRedisTemplate`,
  `CacheRedisProperties`).
- `proxy` contains `ProxyController` and shared `HttpClient` bean in `proxy/config/HttpClientConfig.java`.
- `config` contains `SensitiveString`, OpenAPI configuration `OpenApiConfig`, and retrying `DatabaseMigrator`.

The Lua script that implements the atomic RPM and TPM counters lives in `backend/src/main/resources/rate_limit.lua`. The circuit breaker state machine lives in `backend/src/main/resources/circuit_try_acquire.lua`, `circuit_record_failure.lua`, and `circuit_record_success.lua`. Runtime configuration lives in `backend/src/main/resources/application.yml`, and the database schema is defined by the Flyway migrations under `backend/src/main/resources/db/migration`.

## Technology stack

- Java 25 LTS with virtual threads enabled
- Spring Boot 4.1 and Spring MVC
- Redis 8 via Spring Data Redis and Lettuce over a single shared native connection per tier (no
  client-side pool: the app defines its own `LettuceConnectionFactory` beans, so Boot's pool
  auto-configuration backs off and `spring.data.redis.lettuce.pool.*` keys are intentionally absent).
  Query Engine vector search, JSON,
  TimeSeries, and Bloom ship in the server binary; compose pins `redis:8.10.1-alpine3.23` — two tiers: an
  accounting instance (`noeviction`, rate/budget/hold/dedupe/breaker keys) and a cache instance
  (`allkeys-lru`, L1/L2/replay-hot)
- PostgreSQL via Spring Data JPA and Hibernate, with Flyway owning the schema
- Caffeine for the short lived key lookup cache and the pricing catalog
- JSpecify nullness annotations at package level
- JUnit Jupiter, Mockito, and Testcontainers for testing
- JaCoCo with a strict coverage gate

Dependency versions are managed by the Spring Boot 4.1 BOM. See `backend/pom.xml`.

## Prerequisites

- JDK 25
- Redis 8 (`redis:8.10.1-alpine3.23` in compose; 8.x required for the built-in Query Engine index used by
  the L2 semantic cache), reachable at `localhost:6379` by default. Redis is also required for the distributed circuit breaker, which fails closed when Redis is unreachable.
- PostgreSQL, reachable at `localhost:5432` by default. The gateway starts without it, but the ledger and pricing table need it.
- Docker, only if you want to run the Testcontainers integration tests

The Maven wrapper is included, so no separate Maven installation is needed.

## Getting started

### Running with Docker Compose

Everything runs locally with free, open-source images — **no paid service or subscription is required**.
Provider API keys are optional: the gateway boots without them, and you can use a locally installed
[Ollama](https://ollama.com) for free models and embeddings (`OLLAMA_BASE_URL` in `.env`).

First run — generate `.env` with random local secrets (no external tools needed):

```powershell
# Windows
scripts\init-env.cmd
```

```bash
# macOS / Linux
./scripts/init-env.sh
```

Then start what you need:

```bash
# Start only the dependencies (Redis 8 & PostgreSQL 16 for local IDE development)
docker compose --profile deps up -d

# Start the gateway with backing databases and the full observability stack (Prometheus & Grafana)
docker compose --profile monitoring up -d

# Start the full containerized stack
docker compose --profile all up -d --build
```

The bootstrap fills every hard-required secret (`POSTGRES_PASSWORD`, `POSTGRES_EXPORTER_PASSWORD`,
`REDIS_PASSWORD`, `REDIS_CACHE_PASSWORD`, `GATEWAY_ADMIN_MASTERKEY`, `GATEWAY_AUTH_JWT_SECRET`,
`GATEWAY_MCP_HITL_SECRET`, `GRAFANA_ADMIN_PASSWORD`) with locally generated random hex values. It refuses to
overwrite an existing `.env` unless you force it, and `.env` is gitignored — never commit it.

- **CacheRelay Gateway**: `http://localhost:8080` (Actuator & Health: `http://localhost:9091/actuator/health` — a dedicated
  management port published on host loopback only; metrics are never reachable from the network)
- **Grafana Dashboard**: `http://localhost:3000` (Pre-configured `CacheRelay — Production Operations` dashboard, 51 panels across 12 rows: request path, rate limiting, ledger, JVM, pools, Redis, Postgres, client connections)
- **Prometheus TSDB**: `http://localhost:9090` (Scraping the app plus `redis-exporter:9121` and `postgres-exporter:9187`, with 20 pre-loaded alert rules)

The Postgres exporter role is provisioned automatically: `backend/deploy/postgres-init/01-exporter-role.sh`
runs once at first volume init and creates the least-privilege `pg_monitor` member from your `.env`
(`POSTGRES_EXPORTER_PASSWORD` is filled by `scripts/init-env.*`; compose refuses to start while it is blank):

Configuration is startup-bound: provider, embedding-model, budget, and pricing-source changes require a
container recreate (`docker compose up -d --force-recreate cacherelay`, ~15 s boot) — there is no hot reload
for `gateway.*` outside the SSE flush tunables. Env-var changes never apply without a recreate since process
environment is immutable.

### Running manually

If you prefer starting containers individually:

```bash
docker run -d --name cacherelay-redis -p 127.0.0.1:6379:6379 redis:8.10.1-alpine3.23 redis-server --requirepass <your-redis-password>
docker run -d --name cacherelay-postgres -p 127.0.0.1:5432:5432 -e POSTGRES_USER=cacherelay -e POSTGRES_PASSWORD=<your-password> -e POSTGRES_DB=cacherelay postgres:16.15-alpine
```

Every port binds `127.0.0.1` by default — including the gateway itself (`GATEWAY_BIND_HOST`, default
`127.0.0.1`). Set it to `0.0.0.0` only behind a firewall/load balancer, and never together with the seeded
dev bootstrap keys (they are public by design). Both Redis tiers require AUTH
(`REDIS_PASSWORD` / `REDIS_CACHE_PASSWORD`, distinct secrets, `openssl rand -hex 32`); Postgres enforces
`scram-sha-256` with a hard-required password. The k8s equivalent is `backend/deploy/k8s/networkpolicy-allow.yaml`.

Provide your provider keys and, optionally, a bootstrap key for local testing:

```bash
export OPENAI_API_KEY=your-provider-key
export ANTHROPIC_API_KEY=your-provider-key
export POSTGRES_URL=jdbc:postgresql://localhost:5432/cacherelay
export POSTGRES_USER=cacherelay
export POSTGRES_PASSWORD=<your-password>
export GATEWAY_BOOTSTRAPKEYS_0_OWNERID=local
export GATEWAY_BOOTSTRAPKEYS_0_NAME=local-dev
export GATEWAY_BOOTSTRAPKEYS_0_PLAINTEXTKEY=gw-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
export GATEWAY_BOOTSTRAPKEYS_0_RPMLIMIT=60
export GATEWAY_BOOTSTRAPKEYS_0_TPMLIMIT=100000
```

Build and run (from `backend/`):

```bash
cd backend && ./mvnw clean verify
cd backend && ./mvnw spring-boot:run
```

Operate the UI against it: run the Vite dev server (`frontend/`) and set `SPRING_PROFILES_ACTIVE=dev` on the gateway (same variable
`docker-compose.yml` already reads) so the dev-only CORS allow-list (`http://localhost:5173`) activates. Production has no CORS:
it serves the built SPA same-origin from `backend/src/main/resources/static/` (populated at release time from `frontend/dist`;
that directory is gitignored build output, never committed).

The service listens on port 8080.

## Configuration

All configuration lives in `backend/src/main/resources/application.yml`. The most important settings:

- `gateway.providers` describes every upstream provider with its dialect, URL, key, and per request timeout. Base URLs are prefixes: the adapter appends the chat path (default
  `/v1/chat/completions`, overridable per provider via `chat-completions-path` for odd shapes like Zhipu
  and DeepInfra) - never include the chat path in `base-url`.
- `gateway.aliases` maps each client facing model name to a provider chain and a strategy. A step can pin its upstream model with `model-override`.
- `spring.data.redis.*` controls the Redis connection (host/port/topology selection only — there is
  no Lettuce pool; each custom factory shares one native connection).
- `spring.data.redis.sentinel.*`, `spring.data.redis.cluster.*`, and `spring.data.redis.masterreplica.*` switch the Redis connection used by the rate limiter and the circuit breaker to Sentinel, Cluster, or master/replica topology; the factories in `proxy/failover/CircuitBreakerConfig.java` derive the topology the same way Boot's auto-configuration does. Without any of these the gateway connects to a single host and port.
- `gateway.circuit-breaker.redis-timeout` bounds how long the breaker waits on Redis before it fails closed to the local mirror. The default is 250ms. Setting `spring.application.instance-id` gives each instance a stable name used to arbitrate the single probe.
- `spring.datasource.*` controls the PostgreSQL connection that backs the usage ledger and pricing catalog.
- `gateway.pricing.source-url` and `gateway.pricing.refresh-cron` control where the LiteLLM pricing file is fetched from and how often. The default is a daily sync at 03:00.
- `gateway.ledger.dead-letter-path` is the last-resort file for ledger records when the database is unavailable.
  Failed records first park in the shared `usage_ledger_staging` table (drained by any instance, exactly once per
  row); only a total PostgreSQL outage falls back to the per-pod file.
- Clients may send `Idempotency-Key` (1–255 printable ASCII) per logical operation and reuse it on retry: the
  gateway derives a deterministic ledger id, so a retried request cannot duplicate rows on any instance. Malformed
  keys are rejected with HTTP 400; see `backend/docs/high-throughput/README.md` for the reconnect contract.
- `gateway.database-migrate-enabled` and `gateway.database-migrate-interval` control the non fatal migration retry.
- `gateway.bootstrap-keys-seed-interval` controls how often key seeding is retried if Redis was unavailable at startup.
- `gateway.budget.settlement.*` controls hold-then-settle accounting: `enabled`, `max-tokens-ceiling` (default 4096),
  `hold-ttl-seconds` (3600), `abort-grace-seconds` (30), `sweeper-batch` (500), `sweep-interval` (30s).
- `gateway.budget.detection.*` controls the spend watchdog: `enabled`, `interval` (60s), `alertmanager-url`
  (blank = log-only), `dispatch-batch`, `dispatch-interval`.
- `gateway.embeddings.keep-warm-enabled` and `gateway.embeddings.keep-warm-interval` control the Ollama
  keep-warm heartbeat (default on, 5s) that prevents the measured iGPU deep-sleep wake penalty (~2.2 s
  first-call tax, 18–33 ms steady) on the local stack; disable on battery-powered hosts.
- `gateway.embeddings.local-onnx.*` opts the L2 semantic-cache embed path into an in-process ONNX
  embedder (`enabled` default false, `model-path`, `tokenizer-path`, `intra-op-threads` default 2,
  `max-tokens` default 2048). Local scores sit ~0.03 cosine below Ollama GGUF — re-index and
  recalibrate the similarity threshold (0.77 local ⇔ 0.80 Ollama) before enabling on a populated index.
- `gateway.redis.cache.host/port` points the cache tier at its dedicated evictable Redis
  (`REDIS_CACHE_HOST`/`REDIS_CACHE_PORT`, default `localhost:6380`); accounting stays on `spring.data.redis.*`.
- `gateway.notify.email.graph.*` configures Microsoft Graph mail (`tenant-id`, `client-id`, `secret-ref`,
  `mailbox`, `per-minute-cap`); all blank disables the email channel.
- `gateway.maintenance.*` controls the monthly retention janitor (`retention-enabled`, `retention-batch`,
  `retention-cron`).
- `cacherelay.sse.flush.*` controls the adaptive downstream SSE flush strategy: `max-lines-per-flush` (default 16),
  `max-interval-ms` (default 20ms), `flush-backpressure-threshold-ms` (default 500ms), `max-buffer-bytes` (default
  64KB), `max-flushes-per-second` (default 1000), `enabled` (default true), and `reload-interval` (default 30s).
- `cacherelay.sse.line-guard.*` controls the upstream SSE line guard: `global-default-bytes` (default 16KB),
  `safety-margin-percent` (default 10%), `action` (`REJECT_LINE_AND_CLOSE` or `REJECT_LINE_CONTINUE`), `per-provider`
  (overrides for `OPENAI`, `ANTHROPIC`, `OLLAMA`), `write-timeout` (default 30s), `write-timeout-check-interval`
  (default 5s), and `reload-interval` (default 30s).
- `server.tomcat.accept-count` (high-throughput profile default `8192`, matching kernel `somaxconn`) sizes the
  OS accept queue once `max-connections` is hit; `TOMCAT_ACCEPT_COUNT` / `TOMCAT_MAX_CONNECTIONS` override both
  per environment (empty default = profile value rules).
- `JAVA_TOOL_OPTIONS` passes JVM flags into the compose app container (e.g.
  `-Djdk.virtualThreadScheduler.parallelism=8` for carrier A/B runs); empty by default, so the Dockerfile
  flags (`parallelism=4`) rule.
- Kubernetes lives in `backend/deploy/k8s/` (`kubectl kustomize` / `kubectl apply -k`): share-nothing Deployment
  (2vCPU/2Gi floor), ClusterIP Service, workload-metric HPA, PDB, default-deny + allow NetworkPolicies.
  Secrets are operator-supplied (`cacherelay-secrets`) and never committed; manifests are render-validated
  only until a live cluster proves them.
- Bare-metal/VM deploys use `backend/docs/high-throughput/cacherelay.service` (`LimitNOFILE=131072` — systemd ignores
  `limits.conf`); the ops guide (`backend/docs/high-throughput/README.md`) and proof gate (`backend/docs/high-throughput/gate-checklist.md`) cover
  sysctl, Redis/PG references, and ceiling sign-off.

The per provider `request-timeout` bounds the time to the first byte of the response for that attempt. It is the failover timer; it does not limit a long lived SSE stream. Per provider `connect-timeout` bounds connection establishment. The shared `HttpClient` in `proxy/config/HttpClientConfig.java` applies a conservative connect timeout and never follows redirects.

Bootstrap keys are provisioned exclusively through environment variables such as `GATEWAY_BOOTSTRAPKEYS_0_PLAINTEXTKEY`. Plaintext keys never belong in the repository. The `BootstrapKeySeeder` in `security/ratelimit/BootstrapKeySeeder.java` seeds them after the application is ready and retries on a schedule until Redis is reachable.

## API

### POST `/v1/chat/completions`

The request body is an OpenAI style chat completion payload. The gateway forwards it to the winning provider, including `stream` semantics, and returns the SSE stream.

Authentication uses the `Authorization` header:

```bash
curl -N http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer gw-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-56-luna","messages":[{"role":"user","content":"Hello"}]}'
```

Session owners may act as themselves without handling key material: send the session JWT as the
Bearer token with `X-Act-As-Key: default` (or an owned key hash hex) to execute with an owned key.
Quotas burn on the key and ledger attributes to the account. Every failure answers the same 401 as
an unknown key.

Status codes:

- `200` the stream started and is being relayed.
- `400` the request body is empty, malformed, or missing its model.
- `401` the key is missing, malformed, or unknown. Revoked, unowned, or orphaned keys answer byte-identical 401s (no existence oracle); reversibly disabled keys answer `403`.
- `403` the key is disabled or the requested model is not allowed for it.
- `404` the requested model has no configured alias.
- `413` the request body exceeds the configured limit.
- `429` a rate limit was exceeded. The `Retry-After` header tells the client when to retry.
- `502` every provider returned an error.
- `503` nothing usable was reachable, or the authentication and rate limiting services are unavailable.
- `504` the provider chain timed out.

On success the response carries the rate limit state in the `X-RateLimit-Limit-RPM`, `X-RateLimit-Remaining-RPM`, `X-RateLimit-Reset-RPM`, and the matching TPM headers. The reset values are epoch seconds.

### Embeddings API (`/v1/embeddings`)

Accepts OpenAI-compatible embedding requests, automatically handles transparent batch partitioning across upstream
provider limits (e.g., Cohere max 96, Ollama max 32), dispatches sub-batches concurrently over Virtual Threads, and
reassembles dense vector results with deterministic `0..N-1` index preservation:

```bash
curl http://localhost:8080/v1/embeddings \
  -H "Authorization: Bearer gw-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" \
  -H "Content-Type: application/json" \
  -d '{"model":"text-embedding-3-small","input":["First text to embed","Second text to embed"]}'
```

### Model Catalog (`/v1/models`)

OpenAI-compatible key-authenticated listing of the configured model aliases (`id`, `owned_by` primary provider).
Unmetered metadata — no budget or rate-limit charge:

```bash
curl http://localhost:8080/v1/models \
  -H "Authorization: Bearer gw-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
```

Supports:

- Single string input (`"input": "text"`), multi-text arrays (`"input": ["text1", "text2"]`), and token ID arrays.
- High-efficiency Little-Endian IEEE 754 Base64 binary float encoding (`"encoding_format": "base64"`).
- Matryoshka Representation Learning (MRL) dimension truncation (`"dimensions": 512`).
- Seamless routing to OpenAI, Cohere (`v2/embed`), and Ollama (`/api/embed`).

### Administrative Endpoints (`/v1/admin/**`)

Administrative endpoints require the configured master key via `Authorization: Bearer <GATEWAY_ADMIN_MASTERKEY>` or
`X-Admin-Key`. The key is mandatory: the application fails fast at startup when `GATEWAY_ADMIN_MASTERKEY` is
missing, blank, shorter than 32 bytes, or a published default (e.g. `cacherelay_admin_secret_key` — rotate
immediately if the old shipped default was ever used; generate fresh with `openssl rand -base64 32`):

- **`POST /v1/admin/keys`**: Creates a new virtual API key with custom RPM/TPM quotas and allowlists. Returns the
  single-exposure plaintext key:
  ```json
  {
    "keyId": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
    "key": "gw-aB3_x9...32chars",
    "keyPrefix": "gw-",
    "ownerId": "tenant-corp",
    "name": "production-key",
    "rpmLimit": 120,
    "tpmLimit": 500000,
    "allowedModels": ["gpt-56-luna", "claude-sonnet-4-5"],
    "allowedProviders": ["openai", "anthropic"],
    "enabled": true,
    "createdAt": "2026-08-31T14:30:00Z"
  }
  ```
- **`GET /v1/admin/keys`**: Lists registered virtual keys with safe public metadata (optional `?ownerId=...` filter).
- **`GET /v1/admin/keys/{keyId}`**: Retrieves metadata for a specific key.
- **`GET /v1/admin/budgets`**: Lists every spend cap (`level`, `subjectId`, `minuteMicros`, `monthMicros`).
- **`GET /v1/admin/mcp/approvals/pending`**: Lists suspended tool calls newest-first without arguments; per-token
  detail keeps them. Approve/reject accept optional `{"reason", "decidedBy"}` recorded to a 24 h decision trail
  (the resumption `APPROVED` literal is never disturbed).
- **`PATCH /v1/admin/keys/{keyId}`**: Dynamically updates name, RPM/TPM quotas, allowlists, or enabled status.
- **`DELETE /v1/admin/keys/{keyId}`**: Permanently deletes a virtual API key and purges caches.
- **`POST /v1/admin/keys/{keyId}/revoke`**: Sets the irreversible revocation tombstone (`200`;
  `404` unknown). Nothing - not even an admin - can un-revoke a key.
- Every key requires an active owning account (`ownerUserId` on create, reassignable via `PATCH`;
  unknown/disabled owners are `400`). User deactivation suspends access; user deletion terminally
  revokes every attached key first.
- **`PUT /v1/admin/users/{id}/disabled`**, **`DELETE /v1/admin/users/{id}`**: Account lifecycle
  (`204`; `404` unknown). Deletion cascades terminal revocation before removing the row.
- **`/v1/me/keys`**: Session-authenticated self-service - list owned key metadata (never secrets),
  select the act-as-self default, and terminally revoke owned keys. Foreign keys read as absent.
- **`GET /v1/admin/models`**: Lists every effective model alias with a `source` flag (`file` = configuration-bound
  and read-only, `database` = admin-managed and editable).
- **`POST /v1/admin/models`**: Creates a database-managed model alias (`name`, provider `chain`, `strategy`).
  Duplicate or file-shadowed names are `409`; unknown providers and bad payloads are `400`.
- **`PUT /v1/admin/models/{name}`**: Replaces a database-managed alias routing plan (`404` unknown,
  `409` file-bound).
- **`DELETE /v1/admin/models/{name}`**: Permanently deletes a database-managed alias (`204`; `404` unknown,
  `409` file-bound). Every mutation is audit-logged with the admin actor.
- **`GET /v1/admin/providers`**: Lists every configured upstream provider with dialect, base URL,
  `keyConfigured` (boolean only, never the key), timeouts, live `circuitState`, and how many alias chain
  steps reference it, and `validationStatus` (`CONTRACT_CHECKED` / `AUTH_REACHABLE` /
  `LIVE_VERIFIED` / `UNVERIFIED`)— the provider dropdown source for admin UIs.
- **`GET /v1/admin/model-catalog`**: Searches the pricing catalog snapshot for model suggestions
  (`provider` filter, case-insensitive `q` substring, `limit` 1-200) with context windows, per-token
  prices, and curated quality tiers (`qualityTier` / `benchmarkRefs`, null when unrated) - the model
  picker source when composing alias chain steps.
- **`GET /v1/admin/model-quality/{modelId}`**, **`PUT /v1/admin/model-quality/{modelId}`**,
  **`DELETE /v1/admin/model-quality/{modelId}`**: read, curate (`tier` FRONTIER/STANDARD/BUDGET plus
  optional benchmark references), or unrate a model quality tier (`404` when unrated). Tiers live
  apart from pricing so the daily sync can never clobber them.
- **`GET /v1/admin/circuits`**: Inspects real-time circuit breaker states (`CLOSED`, `OPEN`, `HALF_OPEN`) across all
  providers.
- **`POST /v1/admin/circuits/{provider}/reset`**: Force-resets an upstream circuit breaker to `CLOSED`.
- **`GET /v1/admin/ledger/summary`**: Returns aggregated token consumption, USD costs, duration, and multi-dimensional
  breakdowns by tenant, model, and provider (supports optional `?ownerId=...&provider=...&model=...&from=...&to=...`).
- **`GET /v1/admin/ledger/entries`**: Returns paginated audit log records with allowlisted sort parameters (supports
  `?page=0&size=20&sort=createdAt,desc`).
- **`GET /v1/admin/ledger/entries/{requestId}`**: Retrieves full transaction and token coordinates for a single
  correlated client request.
- **`GET /v1/admin/ledger/user/{userId}/summary`**: Admin drill-down into one account's usage dashboard
  (same shape as the personal view; every access is audit-logged with the admin actor).
- **Usage dashboards (`GET /v1/me/usage`, org-wide admin summary):** on-demand views computed only when
  opened — nothing precomputes in the background. Personal views resolve the caller to their owned keys
  server-side (callers can only ever read their own usage); windows default to the trailing 7 days
  (90-day cap). Results cache for 5 minutes with newest-row watermarks (exact delta merges, averages
  recomputed from merged sums); settled days persist as lazy daily buckets; concurrent identical views
  coalesce; scans run under a statement timeout and per-user views are rate-limited. Freshness rides on
  `X-Dashboard-Generated-At` / `X-Dashboard-Watermark` headers. Team scope arrives with SSO teams
  (Phase 2). Tune via `gateway.dashboard.*` (`GATEWAY_DASHBOARD_*`).
- **`POST /v1/admin/budgets`**: Creates a hard spend budget (`KEY` = key sha256 hex, `TEAM` = owner slug, `ORG` =
  global scope) with rolling-60s and UTC-calendar-month caps in micro-dollars (`0` = no cap). Duplicate
  level/subject is `409`; bad levels/subjects/negative caps are `400`.
- **`GET /v1/admin/budgets/{level}/{subject}/balance`**: Reads caps plus live spend counters (missing counters read
  as zero; Redis outage degrades to `503`, never to a zero-spend lie).
- **`PUT /v1/admin/budgets/{id}`**: Replaces a budget's caps (optimistic locking fails concurrent edits with `409`).
- **`DELETE /v1/admin/budgets/{id}`**: Deletes a cap, snapshotting live spend into the append-only audit row first so
  chargeback history survives the subject.
- **`POST /v1/admin/notifications`**: Opts a scope into alert delivery over `email`, `teams`, `slack`, or `webhook`
  (URL targets SSRF-validated at save time; secrets travel as environment-variable references only, never inline).
- **`GET /v1/admin/notifications?scope=...`**: Lists all subscriptions for one alert scope.
- **`DELETE /v1/admin/notifications/{id}`**: Opts out (unknown ids are no-ops).

### Human Authentication (`/v1/auth/**` + SSO)

Humans log in with local username+password or any configured SSO provider; API keys stay machine-only:

- **`POST /v1/auth/login`**: Local login → short-lived access JWT (memory only) + `httpOnly` refresh cookie.
- **`POST /v1/auth/refresh`**: Rotates the refresh family (requires the `X-CacheRelay-Refresh: 1` CSRF header);
  replaying a rotated token revokes the whole family. Returns a fresh access token.
- **`POST /v1/auth/logout`**: Revokes every session of the account and clears the cookie.
- **`GET /v1/auth/me`**: Current session identity.
- **`POST /v1/auth/redeem`**: Consumes a single-use invite into an account (410 when consumed or expired) and
  logs it in. The first-ever redemption bootstraps the initial admin.
- **`POST /v1/admin/invites`**: Creates an invite (master key or admin session). Always returns a copyable
  redemption link; emails it too when an address is given and the mail channel is configured.
  Links use `gateway.auth.invite-base-url` (`GATEWAY_AUTH_INVITE_BASE_URL`) when set — point it at the
  public frontend origin (Vite dev `http://localhost:5173`, prod frontend URL); blank (default) derives
  the base from the request host, which is only correct for single-origin stacks. Malformed values fail
  startup; A2A agent-card rewriting keeps its own backend-origin `gateway.a2a.public-base-url`.
- **SSO**: Google, GitHub, Entra ID, Azure B2C, Okta, and generic OIDC via Authorization Code + PKCE. Providers
  activate from `SSO_*` env credentials; identities link to shadow accounts by `(sub, iss)`, never email.
  Success sets the refresh cookie and redirects to the SPA with the access token in the URL fragment.
- **SSO teams (IdP-driven)**: per-registration claim mappings (`GATEWAY_SSO_TEAMS_REGISTRATIONS_*`) bind
  IdP groups/roles to orgs and teams via exact/prefix patterns (exact beats prefix, TEAM LEAD is max);
  tenant allowlists fail closed; gateway admin stays locally assigned, never from claims. Accounts with
  no mapped team land in the org's least-privilege `unassigned` team. `GET /v1/me/teams` (own memberships),
  `GET /v1/admin/teams?org=` (inventory with live counts), team-scoped dashboard views for leads.
- **SSO backfill (first login)**: where tokens cannot carry membership, per-registration modes
  (`GATEWAY_SSO_BACKFILL_REGISTRATIONS_*`: `ENTRA_GRAPH`, `OKTA_API`, `GOOGLE_DIRECTORY`, `GITHUB_API`)
  fetch one user's groups blocking the first login and fail closed (disabled/deleted deny; misconfiguration
  fails startup). GitHub uses the user's own OAuth token; Okta uses a scoped service app (`private_key_jwt`
  with `kid`); Google uses domain-wide delegation. Later logins use claims; revalidation sweeps keep them
  fresh (Phase 3).
- **SSO revalidation sweep**: two ShedLock single-holder jobs (hot 15-min, nightly) re-check known SSO
  accounts at the IdP (`GATEWAY_SSO_REVALIDATION_*`). Watermarks self-seed at the epoch and advance on
  every attempt, so outages retry at cadence instead of hot-looping; revocation (account disable, key
  revocation, session revocation, `SSO_REVOKE` audit) happens only on positive IdP-disabled signals,
  never on transport errors. GitHub links skip (no service credential); removals still deny the
  next login.
- **SSO webhooks (fast-lane invalidation)**: `POST /v1/sso/webhooks/{github,okta,entra,google}`
  (`GATEWAY_SSO_WEBHOOKS_*`, secrets ≥ 32 chars) validate per-IdP contracts (GitHub HMAC-SHA256 +
  ping/member/org events; Okta verification challenge + header secret + lifecycle events; Entra
  validation-token handshake + client-state echo; Google channel-token match) and stamp watermarks
  at the epoch for a prompt sweep re-check. Receivers never revoke; redeliveries are idempotent.
- **Strict admin posture**: 5-minute admin access tokens, 7-day admin refresh ceilings, stealth-404 on every
  admin denial (probing cannot confirm the control plane exists), mandatory audit on every admin mutation, and
  per-response CSP nonces. No token ever touches `localStorage`.

### Operational Observability (for dashboards)

- **Circuits**: `GET /v1/admin/circuits` (list) and `GET /v1/admin/circuits/{provider}` report state plus
  `failures` (consecutive, CLOSED), `cooldownMsRemaining` (0 unless OPEN), and `halfOpenProbe` admission;
  `POST …/reset` force-closes. Same shape under `GET /v1/admin/mcp/circuits[/{server}[/reset]]` for MCP servers.
  Countdowns are mirror-local (approximate across instances).
- **Per-request headers** on proxied responses: `X-CacheRelay-Provider` (winner), `X-CacheRelay-Tried`
  (walk order with leg outcomes), `X-Budget-Held-Micros` (admission hold; settled figures post-date headers on
  streams, so query below).
- **Hold-versus-settled**: `GET /v1/admin/budgets/holds/{requestId}` returns held micros, applied settled micros
  (`null` before settle), and lifecycle state; 404 once the hold record expires.
- **Prometheus**: `cacherelay.circuit.breaker.state` / `.failures` per provider and
  `cacherelay.mcp.circuit.breaker.state` / `.failures` per server (state encoded 0/1/2).
- **Embeddings** (`/v1/embeddings`): single attempt (no failover) with `X-CacheRelay-Tried` naming the provider;
  upstream errors return a generic message plus correlation id; `embedding_requests_total{provider,model,outcome}`
  and `embedding_upstream_latency` meters; check-only budget gate (no holds).

Every proxied request passes a single atomic Lua spend gate (`budget_limit.lua`, V7 `budget_limits` + `budget_audit`
tables, V8 append-only trigger) across KEY → TEAM → ORG levels: check-before-increment (denials consume nothing),
first-denied level wins, implicit month rollover via TTL (no reset job to race), uniform fail-closed on Redis/script/
pricing outage. Keys with no configured budget skip the script entirely (zero overhead on the existing path); spend
estimates are prompt-side admission heuristics while the ledger holds post-hoc truth. All gate keys share one Cluster
slot tag (atomicity survives a future Cluster move); Redis config publishes only after the DB transaction commits
(startup backfill reconciles the rest); cross-pod presence invalidates via pg_notify fan-out on top of a 5s negative
TTL; retried idempotency keys are claimed in-Lua so a retry admits without double-debiting. A Spring Security
default-deny boundary refuses any route not explicitly declared, so new endpoints can never silently bypass
authentication. On top of the gate, admitted streams carry a hold (`budget:{b:global}:hold:*`) that settlement
(`settle.lua`, exactly-once `settled` flag) trues up to measured usage; aborts/crashes settle input-known and
surface as append-only `budget_gap` rows. The same key hash is reused as the idempotent replay fingerprint: a
stored completion is served with `Idempotent-Replayed: true` and no second charge, a reused key with a different
body is 422, and a concurrent duplicate is 409.
- **`GET /v1/admin/cache/stats`**: Inspects active cache configuration, layer statuses, and similarity thresholds.
- **`DELETE /v1/admin/cache`**: Executes an emergency global purge across L0 in-memory, L1 Redis exact keys, and L2
  vector document indexes (supports optional `?ownerId=...` for single-tenant scoped purges).

### Multi-Tier Semantic Caching Layer

CacheRelay provides an enterprise-grade, high-throughput (2,000+ concurrent users) multi-tiered caching architecture:

- **L0 (In-Memory)**: Bounded Caffeine cache for sub-millisecond ($<0.1\text{ms}$) exact-match hot prompt lookups, payload-weighed at 256 MiB (`gateway.cache.exact.l0-max-bytes`).
- **L1 (Distributed Exact Match)**: Redis key-value store partitioned by SHA-256 compound keys.
- **L2 (Vector Similarity Search)**: RediSearch / Redis VSS HNSW vector search executing cosine distance queries over
  dense float32 vectors generated by CacheRelay's configured embedding model.
- **Multi-Turn Prefix Partitioning**: Employs hybrid prefix-exact hashing ($H_{\text{prefix}}$) over prior
  turns $[0..N-2]$
  and dense vector embedding on the active user turn $[N-1]$, preventing context drift and infinite replay loops.
- **Anti-Hallucination Guardrails**:
    - **Polarity Guard**: Rejects intent reversals (`enable` vs `disable`, `true` vs `false`).
    - **Entity Guard**: Rejects conflicting named entities and numbers (`Apple` vs `Microsoft`, `42` vs `100`).
    - **Temperature Gating**: Requests with $T > 0.1$ bypass caching to preserve requested stochastic creativity; requests without a temperature bypass the L2 semantic tier both ways (no read, no store).
- **Synthetic Streaming SSE Replay**: Automatically reconstitutes cached completions into valid OpenAI SSE chunk
  sequences with Time-To-First-Token (**TTFT**) in **$< 5\text{ms}$**.
- **Server-side scope isolation (SEC-01)**: `X-CacheRelay-Cache-Scope` only selects within the key's server-side
  `allowedCacheScopes` (default TENANT-only; out-of-policy values are silently ignored). `X-User-Id` is never
  trusted — USER scope degrades to TENANT until a server-verified end-user claim exists. GLOBAL additionally
  requires the operator flag `GATEWAY_CACHE_GLOBAL_SCOPE_ENABLED=true` (default false).

### Interactive Swagger & OpenAPI 3.1 Documentation

CacheRelay provides rich, interactive Swagger UI and OpenAPI 3.1 documentation with group switching, request duration
tracking, syntax highlighting, and live Try-It-Out execution:

- **Swagger UI**: [`http://localhost:8080/swagger-ui.html`](http://localhost:8080/swagger-ui.html)
- **OpenAPI 3.1 JSON Specification**: [`http://localhost:8080/v3/api-docs`](http://localhost:8080/v3/api-docs)
- **OpenAPI 3.1 YAML Specification**: [`http://localhost:8080/v3/api-docs.yaml`](http://localhost:8080/v3/api-docs.yaml)

#### Swagger UI Group Partitions:

1. **`1. Public Gateway APIs`**: `/v1/chat/**` and `/v1/embeddings/**` with Bearer Virtual API Key authentication
   (`Authorization: Bearer gw-...`).
2. **`2. Administrative APIs`**: `/v1/admin/**` (Keys, Circuits, Ledger, Cache) with Master Admin Key authentication
   (`Authorization: Bearer <master-key>` or `X-Admin-Key: <master-key>`).
3. **`3. Observability & Actuator`**: `/actuator/**` health and Prometheus metrics on the dedicated management port
   (`http://localhost:9091`, host loopback only; SEC-15). The app port serves no actuator route.

## Security model

- Keys are 32 random base64url characters behind a `gw-` prefix, giving 192 bits of entropy. They are generated with `SecureRandom` in `KeyManagementService`.
- Only the SHA-256 digest is stored in Redis. `SHA256Hash.toString()` is masked, and no log statement ever receives a plaintext key.
- Key metadata is cached locally with a five second expiry and a bounded size. Confirmed misses are cached as well, so invalid keys cannot flood Redis.
- The gateway fails closed. Redis connectivity errors and pool exhaustion are caught in `RateLimitEngine` and mapped to 503, and lookup failures in the authentication path are handled the same way.
- Every provider URL is validated against a private address block list before its first use, and redirects are never followed.
- Client supplied headers that could spoof identity are stripped before forwarding.
- Error responses carry only generic messages. Internal details never reach the client.
- No CORS in production: the operator SPA is served same-origin, and cross-origin browser traffic stays
  default-denied. Local UI development uses a `dev`-profile-only allow-list for `http://localhost:5173`
  (`/v1/**` fully, actuator health/prometheus read-only so the UI probes report truthfully).
- Operator SPA shell (`/`, `/index.html`, `/assets/**` + extensionless deep links) is served with immutable
  caching on versioned assets and `no-store` on the shell; unknown `/v1/**` routes still refuse with 403.
- Human sessions are hybrid: short-lived Bearer JWTs plus rotating `__Host-` refresh cookies with reuse
- `GATEWAY_AUTH_JWT_SECRET` (32+ bytes) is mandatory outside the `dev`/`test` profiles — startup fails without it, so sessions survive restarts and validate across instances.
  revocation; admin paths deny with stealth-404 and audit every mutation.

## Testing

Run the full suite with coverage and the packaging step (from `backend/`):

```bash
cd backend && ./mvnw clean verify
```

The suite currently has 1,666 tests (100% passing):

JaCoCo coverage gates (BUNDLE, `backend/target/site/jacoco/jacoco.xml` is single-session honest via
`<append>false</append>` on `prepare-agent`): INSTRUCTION/BRANCH/LINE/METHOD/CLASS ≥ 95%, COMPLEXITY ≥ 90%.

### Fast local loop (iteration only — never the gate)

Targeted runs skip coverage and the SBOM step; the full gate stays mandatory before any work is
declared done:

```bash
cd backend
# one class (or comma list), quiet, gate deferred
./mvnw test "-Dtest=RateLimitEngineTest" "-Djacoco.skip=true" -q
# local profile: full suite minus SBOM packaging and coverage *rendering*
# (agent + check stay active, fork/context behavior unchanged)
./mvnw test -Plocal -q
# completion gate, always: full suite with coverage check + SBOM
./mvnw verify -q
```

`-Plocal` is explicit opt-in (never default, never CI): it skips CycloneDX packaging and the JaCoCo
HTML/XML/CSV rendering only. A green targeted or `-Plocal` run is not evidence — only a green
`verify` with all coverage checks met counts.

Container-backed tests share one Postgres + one Redis per JVM (`SharedContainersBase`: single start,
single Flyway migrate, per-method truncate + flush). Two rules: never declare per-class containers
unless the image/config differs (passworded Redis, redis-stack, no-Docker skip), and any future
migration that *seeds data rows* must join the truncation exclusion list next to `model_pricing`
(seeds are read by tests; everything else truncates to fresh-DB semantics).

- Enterprise Model Context Protocol (MCP) gateway tests in `mcp/*`: `McpContractsAndDtoTest`, `McpHeaderNormalizerTest`,
  `McpSseEventFormatterTest`, `McpStreamableHttpControllerTest`, `McpAdversarialCoverageTest`,
  `McpFullCoverageBranchTest`, `McpRouterTest`, `McpCatalogCacheTest`, `McpCatalogAggregatorTest`,
  `McpToolRbacPolicyEngineTest`, `McpJsonSchemaValidatorTest`, `McpGuardrailScannerTest`,
  `McpAeadResumptionTokenServiceTest`, `McpAeadPbkdf2Test`, `McpGatewayPropertiesValidationDiagnosticTest`,
  `McpHitlSuspensionEngineTest`, `AdminMcpApprovalControllerTest`,
  `McpServerCircuitBreakerManagerTest`, `McpServerCircuitBreakerManagerResetTest`,
  `McpStressAndConcurrencyHarnessTest`, `McpJsonRpcContractTest`, `McpVersionNegotiationMatrixTest`, and
  `CoverageCompletionTest` covering
  JSON-RPC 2.0 framing and spec-allowed error codes (-32700..-32603 plus -32020/-32021/-32022 only), `resultType`
  injection (`complete`/`input_required`), id-member omission on unreadable errors, per-request `_meta` version
  negotiation (header/body match, `-32020` mismatch, `-32022` unsupported, `-32021` missing capabilities as a
  spec object), Streamable HTTP batch rejection (`-32600`), PBKDF2-HMAC-SHA256 (600K iterations) key derivation,
  protocol version negotiation, AES-256-GCM resumption token
  tamper/expiry/owner-bound attack vectors, single-use Redis replay prevention, glob-pattern RBAC, JSON Schema Draft
  2020-12 bounds, tool shadowing/namespacing collisions, circuit-breaker auto-pruning, malformed upstream payloads,
  white-box usage-projection fixtures, and a 10,000 virtual-thread simultaneous burst harness.
- Extended reasoning and streaming normalizer tests in `proxy/protocol`: `ThinkingStreamStateNormalizerTest`,
  `ProtocolNormalizerAdversarialTest`, Anthropic Claude 3.5/3.7 thinking & tool calling tests, and Ollama NDJSON edge cases
  covering $O(1)$ sliding carry buffer `<think>` tag extraction into `delta.reasoning_content`, Anthropic `thinking_delta`
  parsing, Ollama `message.thinking` deltas, and malformed chunk boundary resilience.
- High-throughput ledger & FinOps prompt caching tests in `ledger` and `ledger/queue`:
  `DisruptorUsageLedgerQueueTest`, `MicroBatchLedgerWriterTest`, `SpillwayJournalManagerTest`,
  `FinOpsPromptCacheCalculatorTest`, `FinOpsPromptCacheCalculatorPropertyTest`, `CostCalculatorPrecisionTest`,
  `UsageLedgerEntryTest`, and
  `LedgerStressAndBackpressureIntegrationTest` covering lock-free CAS sequence claiming at 50,000 req/s, dual-trigger
  ($B \ge 5000 \lor \Delta t \ge 50\text{ms}$) micro-batching, append-only WAL disk failover, atomic staging
  rotation/replay,
  micro-dollar fixed-point rounding precision, signed cache savings (negative on cold-cache write surcharge),
  effective-equals-billed FOCUS 1.4 invariant, HALF_UP rounding boundaries, and 17-field FinOps FOCUS 1.4 schema
  compliance.
- Real-time guardrail and security scanner tests in `security/guardrail/*`: `ShannonEntropyCalculatorTest`,
  `LuhnValidatorTest`, `ConfusablesFilterTest`, `GuardrailPropertiesTest`, `BytePrefixTrieTest`,
  `SecretScannerRuleDatabaseTest`, `IngressSecretScannerTest`, `SecretLeakageExceptionTest`,
  `PromptInjectionScannerTest`, `PromptInjectionExceptionTest`, `SystemPromptProtectionEngineTest`,
  `PiiScannerTest`, `PiiDisambiguationEngineTest`, `IbanValidatorTest`, `EphemeralPiiVaultTest`,
  `PiiAnonymizerTest`, `SlidingWindowAhoCorasickTest`, `StreamingJsonPdaValidatorTest`, and
  `MidStreamKillSwitchTest` covering branchless LUT Shannon entropy, UTS #39 Unicode homoglyphs, 18 credential patterns,
  Nigerian phone/BVN/NIN 4-tier disambiguation, Verve Luhn checks, AES-256-GCM ephemeral request vault with zero-trace
  memory wiping, bounded lookahead SSE chunk reassembly, and upstream HTTP/2 RST_STREAM cancellation.
- Geo-sovereignty & compliance tests in `security/compliance/*`: `JurisdictionAdequacyTest`,
  `GeoSovereigntyRouterTest`, `MerkleAuditLedgerTest`, and `ZeroDataRetentionEnforcerTest` covering GDPR Art. 45 & NDPA
  2023 cross-border adequacy DAGs, forward-secure SHA-256 hash chains, HMAC-SHA256 receipts (`X-CacheRelay-Audit-Receipt`),
  and `X-No-Storage` zero-retention headers.
- Ingress filter pipeline tests in `security/filter/*`: `IngressSecurityFilterTest`,
  `SecurityFilterConfigTest`, and `AnonymizedBodyHttpServletRequestTest` covering order 0 -> 1 -> 2 filter chaining, RFC
  9457 HTTP 422 ProblemDetails in ENFORCE mode, audit logging in AUDIT_ONLY mode, and request wrapping.
- OpenAPI 3.1 & documentation tests in `config`: `OpenApiConfigTest` covering global specification metadata, security
  scheme registrations (`BearerAuth`, `AdminKeyAuth`, `AdminBearerAuth`), and GroupedOpenApi partitions.
- Multi-tier semantic caching tests in `cache`: `CacheKeyGeneratorTest`, `CacheGuardrailsTest`,
  `CacheGuardrailsAdversarialTest`, `TemperatureIsolationTest`,
  `RedisSemanticVectorCacheTest`, `RediSearchVectorClientTest`, `InMemoryExactCacheTest`,
  `RedisExactCacheTest`, `SingleFlightManagerTest`, `CachedStreamReconstitutionTest`,
  `CachePolicyEngineTest`, `CacheRelayCacheServiceTest`, `AdminCacheControllerTest`, `CacheContractsTest`,
  `CacheRelayCachePropertiesTest`, `CacheFullCoverageTest`, and `SemanticCacheIntegrationTest` covering L0 in-memory caching,
  L1 Redis exact matching, L2 RediSearch HNSW vector search, multi-turn prefix partitioning, polarity and
  slot-aligned entity-contradiction guardrails, temperature-gated store/lookup symmetry with L2 tag filtering,
  RFC 9111 directive inspection, single-flight stampede prevention, synthetic SSE stream reconstitution, and
  administrative purge APIs.
- Embedding gateway tests in `proxy/embeddings`: `VectorEncodingUtilsTest`, `EmbeddingDtoTest`,
  `OpenAiEmbeddingAdapterTest`, `CohereEmbeddingAdapterTest`, `OllamaEmbeddingAdapterTest`,
  `EmbeddingBatchOrchestratorTest`, `EmbeddingAdapterResolverTest`, `EmbeddingServiceTest`, and
  `EmbeddingControllerTest` covering Little-Endian IEEE 754 float32 Base64 encoding/decoding, auto-batching, concurrency
  bounding, index reassembly, and provider normalization.
- Administrative, billing & key management tests in `admin`: `AdminAuthFilterTest`, `AdminKeyControllerTest`,
  `AdminCircuitControllerTest`, `AdminLedgerControllerTest`, `AdminFilterConfigTest`, and `AdminDtoTest` covering
  constant-time master key authentication, fail-closed isolation, key creation (single-exposure plaintext), updates,
  deletions, circuit breaker force-resets (observed-state passthrough), aggregated tenant billing queries, and paginated
  audit logs.
- Unit tests for hashing, key management, the rate limit engine, both filters, the body wrapper, the circuit breaker, the provider adapter, the orchestrator, the error handler, and the Phase 1 security components.
- Distributed circuit breaker tests in `proxy/failover`: `RedisCircuitBreakerTest` and
  `CircuitBreakerCrossInstanceIntegrationTest` run against a real Redis container and verify shared state, the single
  flight probe, and the mirror fallback, while `CircuitBreakerConfigTest`, `CircuitBreakerMetricsTest`,
  `RedisCircuitBreakerFactoryTest`, `ProviderCircuitBreakerResetConcurrencyTest`, and `RedisCircuitBreakerEdgeTest`
  cover configuration, metrics, lock-free CAS force-reset under 11,000 virtual-thread contention, and the slow or
  unavailable Redis paths.
- Unit tests for the streaming protection and guard layer in `proxy/sse`: `AdaptiveSseFlushStrategyTest`,
  `SseFlushConfigReloaderTest`, `SseFlushHealthIndicatorTest`, `SseFlushLoadTest`, `SseFlushSecurityTest`,
  `BoundedLineBodyHandlerTest`, `DefaultSseLineGuardTest`, `TokenBucketTest`, `SseLineGuardPropertiesTest`, and
  `E12CoverageSupportTest` covering adaptive flushing, rate-limit token buckets, bounded byte decoding, OOM prevention,
  and hot-reload.
- Unit tests for the protocol layer in `proxy/protocol`: request translation, header construction, and stream normalization for the OpenAI, Anthropic, Gemini, Vertex AI, DeepSeek, and Ollama dialects, including malformed input tolerance.
- Unit tests for the ledger in `ledger`: cost calculation, catalog matching, the pricing sync, and the dead letter fallback.
- MockWebServer based tests in `proxy/failover/FailoverOrchestratorTest` that stand in for real providers and verify failover on 500 and 429, no failover on 401 and 400, circuit opening and recovery, timeout behavior, and the RACE strategy.
- A MockWebServer based integration test in `proxy/protocol/ProtocolNormalizationIntegrationTest.java` that drives the real orchestrator, adapters, and normalizers against fake Anthropic and Ollama upstreams.
- A Testcontainers integration test in `security/ratelimit/RateLimitIntegrationTest.java` that runs the whole application against a real Redis container and exercises authentication, both limits, the model allow list, the failover path, and the fail closed behavior.
- A Testcontainers integration test in `ledger/UsageLedgerIntegrationTest.java` that runs the ledger against a real PostgreSQL container and verifies async persistence, duplicate request handling, the seeded prices, and a pricing refresh.
- A context load test that verifies the application starts without a live Redis or PostgreSQL.
- A gate-throughput tripwire in `budget/GateThroughputSmokeTest.java` that replays 2,000 `budget_limit.lua`
  decisions against real Redis (floor 200 decisions/s, 5s slowest-single-call ceiling) to catch
  order-of-magnitude hot-path regressions; the 200K ceiling itself needs the distributed k6 harness.
- Gate-only k6 tripwires in `backend/loadtest/k6/`: `01-gate-smoke.js` (60s @100rps local-404 probe that measures
  gateway latency, never upstream health) and `02-gate-burst.js` (3m @500rps measurement feeding the carrier
  A/B and the P3 bottleneck order).

JaCoCo enforces a minimum coverage of 95 percent on every counter at the bundle level. The current gate passes at
**INSTRUCTION 98.70%, BRANCH 95.05%, LINE 98.09%, COMPLEXITY 93.72%, METHOD 99.56%** (1,589 tests). The
circuit breaker and orchestrator retry and race coordination branches are excluded from the gate because they cannot be
reached deterministically; the state transitions and failover semantics themselves are fully covered. The Mockito inline
mock maker is attached as a Java agent through the `argLine` Maven property, so the suite is future proof against the
JDK restriction on self attachment.

> **Build hygiene note:** Maven never deletes stale files from `backend/target/test-classes`. If a test resource is
> deleted from `backend/src/test/resources`, its ghost copy continues to shadow `backend/src/main/resources` on the test
> classpath (this once silently blanked all `gateway.*` binding — diagnosed via `Environment.getProperty`
> returning null while the YAML was correct). After deleting any test resource, manually remove its
> `backend/target/test-classes` copy or run `mvn clean`.

## Design notes

The request body wrapper exists because Spring's `ContentCachingRequestWrapper` cannot replay the body. It caches bytes as they are read and hands the same exhausted stream to any later reader, which would silently deliver an empty body to the controller. `CachedBodyHttpServletRequest` buffers once at construction and serves a fresh stream on every call, which keeps byte level fidelity including multi byte UTF-8 input.

The rate limiter uses a fixed window per key. Redis documents this as the simplest approach for per client quotas; its only weakness is a possible double burst at window boundaries, which is an acceptable trade for per minute limits. The entire check and consume sequence runs inside one Lua script so concurrent requests cannot overshoot the limit. See `src/main/resources/rate_limit.lua`.

The circuit breaker follows the canonical pattern described by Fowler and the microservices community: closed, open, and half open states with a cooldown and a single probe. State lives in an atomic reference with compare and set transitions, so the hot path is lock free. No background thread or timer exists; transitions out of open happen lazily on the next attempt. See `proxy/failover/ProviderCircuitBreaker.java`.

The distributed version in `proxy/failover/RedisCircuitBreaker.java` keeps that same state machine but stores state in a single Redis hash per provider. The open to half open transition and probe ownership are decided by an atomic Lua script, `src/main/resources/circuit_try_acquire.lua`, keyed on the instance id, so exactly one instance probes after a cooldown and a crashed probe owner is stolen after a lease. A dedicated Redis template with a short command timeout and a `Semaphore` bulkhead keep a slow Redis from stalling virtual threads; on a Redis error the in-memory mirror decides, so the gateway fails closed. Per provider state is exported to Prometheus through `proxy/failover/CircuitBreakerMetrics.java`.

Failover happens only before the first byte. The orchestrator returns a response whose status and content type are already known, so the controller never begins streaming before a winner exists. In the RACE strategy every losing attempt is cancelled at the socket level, which also stops the upstream provider from generating and billing further tokens.

Protocol normalization happens on two sides of the hop. The request adapters translate the client contract into the winning provider's native dialect before the attempt is sent, and the stream normalizer translates the response back as it flows. Each normalizer is a small state machine per stream, which keeps the mapping deterministic and lets the gateway capture exact token counts without buffering the body.

The ledger uses a lock-free, zero-allocation circular ring buffer (`DisruptorUsageLedgerQueue`, $N=65,536$) with atomic CAS sequence claiming. By decoupling ingestion from database I/O, the gateway achieves $<1\mu\text{s}$ enqueue latency at 50,000 req/s with zero carrier thread pinning under Project Loom. Micro-batching is handled by a background Virtual Thread worker (`MicroBatchLedgerWriter`) that drains the queue into PostgreSQL on dual triggers ($B \ge 5{,}000$ or $\Delta t \ge 50\text{ms}$) using JDBC batch rewriting (`rewriteBatchedInserts=true`), eliminating database connection pool contention. If PostgreSQL is temporarily unreachable, writes spill into an append-only WAL journal (`SpillwayJournalManager`) on disk, which is automatically rotated and replayed upon reconnection, ensuring zero usage data loss.

## Maintainer

CacheRelay is maintained by Joshua Ike.