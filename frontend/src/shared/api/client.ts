import type {
  A2aAgentCard,
  ApiKeyCreated,
  ApiKeyRecord,
  BudgetBalance,
  BudgetHold,
  BudgetRecord,
  BudgetSnapshot,
  CachePurge,
  CacheStats,
  CacheTiers,
  ChatCompletionRequest,
  ChatCompletionResponse,
  CircuitSnapshot,
  DashboardView,
  EmbeddingRequest,
  EmbeddingResponse,
  HitlApproval,
  HitlDecision,
  InviteReceipt,
  KeyPolicy,
  LedgerLogEntry,
  LedgerReceipt,
  LedgerSummary,
  McpSuspended,
  McpTool,
  McpToolAnnotations,
  MemberResponse,
  ModelAliasRecord,
  ModelCatalogEntry,
  ModelQuality,
  NotificationPreference,
  OrgResponse,
  OrgTeam,
  OwnedKey,
  ProviderStatus,
  PageResponse,
  ProviderChainStep,
  RateLimitDimension,
  RateLimitSnapshot,
  SessionIdentity,
  TeamMembership,
  UserPage,
} from './types.js'
import * as z from 'zod/v4'
import { useAuthStore } from '../auth/store.js'
import { refreshSession } from '../auth/session.js'

/**
 * Extracts a user-safe message from an unknown throw.
 *
 * @param error - Caught value of unknown shape.
 * @param fallback - Message when the value carries no usable text.
 * @returns The error message, or the fallback.
 */
export function toErrorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback
}

/**
 * Base URL for gateway calls.
 *
 * @remarks
 * Dev default is `VITE_API_BASE_URL` (`http://localhost:8080`). Production
 * falls back to same-origin (`''`) when the variable is unset, so the SPA
 * works behind the Spring Boot static serving without CORS.
 *
 * @returns The configured base URL, or `''` for same-origin.
 */
export function resolveApiBase(): string {
  const raw: unknown = import.meta.env.VITE_API_BASE_URL
  if (typeof raw !== 'string') return ''
  const trimmed = raw.trim()
  if (trimmed.length > 0) return trimmed.replace(/\/+$/, '')
  return ''
}

/**
 * Base URL for actuator scrapes (health, prometheus).
 *
 * @remarks
 * SEC-15 isolates actuator endpoints on the dedicated management port
 * (`VITE_MANAGEMENT_BASE_URL`, dev default `http://localhost:9091`); the
 * app port answers every `/actuator/**` path with `404` and no CORS grant
 * (backend truth: `backend/docs/BACKEND_API_REFERENCE.md` SEC-15). An
 * explicitly configured variable always wins. When unset, loopback pages
 * (local dev) default to the management port on the same host; any other
 * host falls back to same-origin (`''`), preserving deployed behavior.
 *
 * @returns The management base URL, or `''` for same-origin.
 */
export function resolveManagementBase(): string {
  const raw: unknown = import.meta.env.VITE_MANAGEMENT_BASE_URL
  if (typeof raw === 'string' && raw.trim().length > 0) {
    return raw.trim().replace(/\/+$/, '')
  }
  try {
    const host = window.location.hostname
    if (host === 'localhost' || host === '127.0.0.1' || host === '[::1]') {
      return `http://${host}:9091`
    }
  } catch {
    // Non-browser runtimes keep the same-origin fallback below.
  }
  return ''
}
/**
 * Whether the console may open SSE streams.
 *
 * @remarks
 * Kill-switch for constrained networks: when `VITE_FEATURE_STREAMING` is
 * exactly `false` (any casing), the playground falls back to non-streaming
 * JSON completions. Anything else — including unset — streams.
 *
 * @returns False only when the flag explicitly disables streaming.
 */
export function isStreamingEnabled(): boolean {
  const raw: unknown = import.meta.env.VITE_FEATURE_STREAMING
  if (typeof raw !== 'string') return true
  return raw.toLowerCase() !== 'false'
}

/**
 * Failure thrown for non-2xx gateway responses.
 */
export class ApiError extends Error {
  readonly status: number
  readonly requestId: string | null
  readonly rateLimit: RateLimitSnapshot
  readonly budget: BudgetSnapshot | null
  readonly cacheStatus: string | null
  readonly debugId: string | null
  /** Gateway `error.code` (for example `RPM_EXCEEDED`), or null when absent. */
  readonly code: string | null

  constructor(args: {
    message: string
    status: number
    requestId: string | null
    rateLimit: RateLimitSnapshot
    budget?: BudgetSnapshot | null
    cacheStatus: string | null
    debugId: string | null
    code: string | null
  }) {
    super(args.message)
    this.name = 'ApiError'
    this.status = args.status
    this.requestId = args.requestId
    this.rateLimit = args.rateLimit
    this.budget = args.budget ?? null
    this.cacheStatus = args.cacheStatus
    this.debugId = args.debugId
    this.code = args.code
  }
}

/**
 * Parses a decimal header value under the specified gateway grammar.
 * Hex (`0x10`), exponents (`1e3`), and padded values (` 5 `) never
 * coerce — only plain decimal strings parse.
 *
 * @param v - Raw header value, or null when absent.
 * @returns The number, or null when absent, `unlimited`, or off-grammar.
 */
function decimalOrNull(v: string | null): number | null {
  if (v === null || v === 'unlimited') return null
  if (!/^\d+(\.\d+)?$/.test(v)) return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/**
 * Parses an integer header value (`Retry-After`, epoch resets, micros).
 *
 * @param v - Raw header value, or null when absent.
 * @returns The integer, or null when absent or off-grammar.
 */
function intOrNull(v: string | null): number | null {
  if (v === null) return null
  if (!/^\d+$/.test(v)) return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/**
 * Reads one rate-limit dimension triple from response headers.
 *
 * @param headers - Response headers to inspect.
 * @param dimension - Which header family suffix to read.
 * @returns The dimension triple (nulls when absent or `unlimited`).
 */
function parseDimension(
  headers: Headers,
  dimension: RateLimitDimension,
): { limit: number | null; remaining: number | null; reset: number | null } {
  return {
    limit: decimalOrNull(headers.get(`X-RateLimit-Limit-${dimension}`)),
    remaining: decimalOrNull(headers.get(`X-RateLimit-Remaining-${dimension}`)),
    reset: intOrNull(headers.get(`X-RateLimit-Reset-${dimension}`)),
  }
}

/**
 * Selects the binding dimension to display in the single-row strip.
 *
 * @remarks
 * A backend-named 429 `error.code` (`RPM_EXCEEDED` / `TPM_EXCEEDED`) always
 * wins — the gateway states which dimension rejected the request. Otherwise
 * the most-constrained capped dimension (lowest remaining fraction) leads;
 * uncapped (`unlimited`/absent) dimensions never race. Ties and unknowns
 * fall back to RPM, the primary operator quota.
 *
 * @param rpm - Parsed RPM triple.
 * @param tpm - Parsed TPM triple.
 * @param code - Gateway `error.code`, or null outside failure paths.
 * @returns The binding dimension, or null when none is capped and observed.
 */
export function selectPrimaryDimension(
  rpm: { limit: number | null; remaining: number | null },
  tpm: { limit: number | null; remaining: number | null },
  code: string | null,
): RateLimitDimension | null {
  if (code === 'RPM_EXCEEDED') return 'RPM'
  if (code === 'TPM_EXCEEDED') return 'TPM'
  const fraction = (d: { limit: number | null; remaining: number | null }): number | null =>
    d.limit !== null && d.limit > 0 && d.remaining !== null ? d.remaining / d.limit : null
  const rpmFraction = fraction(rpm)
  const tpmFraction = fraction(tpm)
  if (rpmFraction === null) return tpmFraction === null ? null : 'TPM'
  if (tpmFraction === null) return 'RPM'
  return tpmFraction < rpmFraction ? 'TPM' : 'RPM'
}

/**
 * Operational headers the backend emits on gateway responses.
 *
 * @remarks
 * Backend truth (`KeyAuthFilter`): the gateway sends an RPM trio
 * (`X-RateLimit-Limit-RPM`, `X-RateLimit-Remaining-RPM`,
 * `X-RateLimit-Reset-RPM`, reset as epoch seconds) plus a TPM trio with the
 * same shapes, on success and on 429. `Retry-After` arrives on deny paths
 * only, and an uncapped dimension reports the literal `unlimited` instead of
 * a number (mapped to null; the strip renders it as quiet text, never a bar).
 * The displayed row is the binding dimension from {@link selectPrimaryDimension}.
 *
 * @param headers - Response headers to inspect.
 * @param code - Gateway `error.code` naming the binding dimension on 429, if any.
 * @returns Parsed rate-limit snapshot (nulls when absent).
 */
export function parseRateLimit(headers: Headers, code: string | null = null): RateLimitSnapshot {
  const rpm = parseDimension(headers, 'RPM')
  const tpm = parseDimension(headers, 'TPM')
  const dimension = selectPrimaryDimension(rpm, tpm, code)
  const primary = dimension === 'TPM' ? tpm : rpm
  return {
    dimension,
    limit: primary.limit,
    remaining: primary.remaining,
    reset: primary.reset,
    retryAfter: intOrNull(headers.get('Retry-After')),
  }
}

/**
 * Reads the gateway budget header family from a response.
 *
 * @remarks
 * Backend truth: denials set `X-Budget-Remaining` (`0`),
 * `X-Budget-Reset` (epoch seconds), `X-Budget-Level`, and
 * `X-Budget-Window`. Successes with a hold set `X-Budget-Held-Micros`
 * (pre-settle estimate, never final cost) and `X-Budget-Subject`.
 * Absent headers read as null. No success-path remaining is ever set.
 *
 * @param headers - Response headers to inspect.
 * @returns Parsed budget snapshot (nulls when absent).
 */
export function parseBudget(headers: Headers): BudgetSnapshot {
  const text = (v: string | null): string | null => {
    if (v === null) return null
    const t = v.trim()
    return t.length > 0 && t.length <= 64 ? t : null
  }
  return {
    remaining: intOrNull(headers.get('X-Budget-Remaining')),
    reset: intOrNull(headers.get('X-Budget-Reset')),
    level: text(headers.get('X-Budget-Level')),
    window: text(headers.get('X-Budget-Window')),
    heldMicros: intOrNull(headers.get('X-Budget-Held-Micros')),
    subject: text(headers.get('X-Budget-Subject')),
  }
}

/**
 * Reads cache provenance from response headers. The wire header is
 * `X-Cache` (`HIT (L0-Memory)` / `HIT (L1-Exact)` / `HIT (L2-Semantic)`);
 * a legacy `X-Cache-Status` alias is accepted as fallback, never preferred.
 *
 * @param headers - Response headers to inspect.
 * @returns The tier string, or null when absent.
 */
export function cacheStatusOf(headers: Headers): string | null {
  return headers.get('X-Cache') ?? headers.get('X-Cache-Status')
}

/**
 * Extracts the gateway `error.code` from a failure body without throwing.
 *
 * @remarks
 * Backend truth (`KeyAuthFilter.writeJsonError`): deny bodies carry
 * `{"error":{"message":…,"code":"RPM_EXCEEDED"|…}}`. Overlong values are
 * rejected so adversarial bodies can never flow a payload into the UI —
 * unknown codes map to null at selection time and are never rendered raw.
 *
 * @param body - Raw response text (may be empty or non-JSON).
 * @returns The code string, or null when absent, malformed, or oversized.
 */
export function parseGatewayErrorCode(body: string): string | null {
  if (body.length === 0) return null
  try {
    const parsed: unknown = JSON.parse(body)
    if (typeof parsed === 'object' && parsed !== null) {
      const err = (parsed as Record<string, unknown>).error
      if (typeof err === 'object' && err !== null) {
        const code = (err as Record<string, unknown>).code
        if (typeof code === 'string' && code.length > 0 && code.length <= 64) return code
      }
    }
  } catch {
    // Malformed bodies carry no code; callers fall back to header selection.
  }
  return null
}

/**
 * Vendor-screening verdict header. Present on ENFORCE 422 denials and on
 * AUDIT_ONLY 200s as `<vendor>:<reason>`; absent when clean or disabled.
 * Values are CR/LF-stripped server-side — treat as opaque tokens.
 */
export const VERDICT_HEADER = 'X-CacheRelay-Vendor-Verdict'

/**
 * Default MCP protocol version. Sent explicitly so the gateway never
 * resolves an ambiguous default. Matches the documented default
 * `2026-07-28` which requires `params._meta` on every call.
 */
export const MCP_PROTOCOL_VERSION = '2026-07-28'

/**
 * Builds the required `params._meta` object for the default protocol.
 *
 * @returns Meta with namespaced protocol version plus empty capabilities.
 */
export function mcpMeta(): Record<string, unknown> {
  return {
    'io.modelcontextprotocol/protocolVersion': MCP_PROTOCOL_VERSION,
    'io.modelcontextprotocol/clientCapabilities': {},
  }
}

/**
 * Reads an opaque vendor verdict token from a header value.
 *
 * @param value - Raw header value, or null when absent.
 * @returns The trimmed token, or null when absent, blank, or oversized.
 */
export function parseVerdictHeader(value: string | null): string | null {
  if (value === null) return null
  const token = value.trim()
  if (token.length === 0 || token.length > 128) return null
  return token
}

/**
 * Splits a verdict token into vendor and reason on the first colon.
 *
 * @param token - Opaque `<vendor>:<reason>` token, or null.
 * @returns Vendor plus reason, or null when unsplittable or oversized.
 */
export function splitVerdict(token: string | null): { vendor: string; reason: string } | null {
  if (token === null) return null
  const cut = token.indexOf(':')
  if (cut <= 0) return null
  const vendor = token.slice(0, cut)
  const reason = token.slice(cut + 1)
  if (vendor.length === 0 || reason.length === 0) return null
  if (vendor.length > 64 || reason.length > 64) return null
  return { vendor, reason }
}

/**
 * Composes a vendor-screening refusal from an RFC 9457 problem body.
 *
 * @remarks
 * Backend truth (spec: guardrails): ENFORCE denials answer 422 with an
 * exact `type` URI. Vendor denials carry human `title` + `detail` plus
 * `vendor`/`reason` attribution — pass through, do not compose.
 * Credential denials add `rule_id`, `masked_token`, `json_path`
 * (fingerprints never render); injection denials add `category`,
 * `risk_score`, `matched_pattern`. Mapping prefers the verdict header; display prefers
 * the body. Attribution is display-only (never logic) and drops when
 * absent or oversized. Anything else returns null so every other 422 —
 * and every 500, including vendor *failure* bodies without the screening
 * signal — keeps its existing path.
 *
 * @param status - HTTP status code.
 * @param record - Decoded JSON body.
 * @param headerVerdict - Parsed verdict-header split, or null when absent.
 * @returns The composed refusal, or null when not a screening denial.
 */
function screeningRefusal(
  status: number,
  record: Record<string, unknown>,
  headerVerdict: { vendor: string; reason: string } | null,
): string | null {
  if (status !== 422) return null
  const type = record.type
  if (type === 'https://cacherelay.io/errors/credential-leakage-detected') {
    return guardrailRefusal(record, credentialDetails(record))
  }
  if (type === 'https://cacherelay.io/errors/prompt-injection-detected') {
    return guardrailRefusal(record, injectionDetails(record))
  }
  const typed =
    typeof type === 'string' && type === 'https://cacherelay.io/errors/vendor-screening-rejection'
  if (!typed && headerVerdict === null) return null
  const title = record.title
  const detail = record.detail
  const hasTitle = typeof title === 'string' && title.length > 0
  const hasDetail = typeof detail === 'string' && detail.length > 0
  if (!hasTitle && !hasDetail) return null
  const text = hasTitle && hasDetail ? `${title}: ${detail}` : hasTitle ? title : detail
  const vendor = record.vendor
  const reason = record.reason
  const bodyAttributed =
    typeof vendor === 'string' &&
    vendor.length > 0 &&
    vendor.length <= 64 &&
    typeof reason === 'string' &&
    reason.length > 0 &&
    reason.length <= 64
  if (bodyAttributed) return `${String(text)} Screened by ${vendor} (${reason}).`
  if (headerVerdict !== null)
    return `${String(text)} Screened by ${headerVerdict.vendor} (${headerVerdict.reason}).`
  return String(text)
}

/**
 * Reads one bounded string extension field from a problem body.
 *
 * @param record - Decoded problem body.
 * @param key - Extension field name.
 * @param max - Maximum accepted length.
 * @returns The field value, or null when absent or oversized.
 */
function extensionField(record: Record<string, unknown>, key: string, max: number): string | null {
  const value = record[key]
  if (typeof value !== 'string' || value.length === 0 || value.length > max) return null
  return value
}

/**
 * Composes the base `title: detail` text of a guardrail denial.
 *
 * @param record - Decoded problem body.
 * @param suffix - Extension detail, or null when none rendered.
 * @returns The composed refusal, or null when title and detail are absent.
 */
function guardrailRefusal(record: Record<string, unknown>, suffix: string | null): string | null {
  const title = record.title
  const detail = record.detail
  const hasTitle = typeof title === 'string' && title.length > 0
  const hasDetail = typeof detail === 'string' && detail.length > 0
  if (!hasTitle && !hasDetail) return null
  const text = hasTitle && hasDetail ? `${title}: ${detail}` : hasTitle ? title : detail
  return suffix === null ? String(text) : `${String(text)} ${suffix}`
}

/**
 * Formats credential-leakage extension fields. The fingerprint never
 * renders: it is an identifier, not a locator.
 *
 * @param record - Decoded problem body.
 * @returns The suffix, or null when no field rendered.
 */
function credentialDetails(record: Record<string, unknown>): string | null {
  const parts: string[] = []
  const rule = extensionField(record, 'rule_id', 64)
  if (rule !== null) parts.push(`Rule ${rule}`)
  const path = extensionField(record, 'json_path', 256)
  if (path !== null) parts.push(`at ${path}`)
  const token = extensionField(record, 'masked_token', 64)
  if (token !== null) parts.push(`(token ${token})`)
  return parts.length === 0 ? null : `${parts.join(' ')}.`
}

/**
 * Formats prompt-injection extension fields.
 *
 * @param record - Decoded problem body.
 * @returns The suffix, or null when no field rendered.
 */
function injectionDetails(record: Record<string, unknown>): string | null {
  const parts: string[] = []
  const category = extensionField(record, 'category', 64)
  if (category !== null) parts.push(`Category ${category}`)
  const risk = record.risk_score
  if (typeof risk === 'number' && Number.isFinite(risk) && risk >= 0 && risk <= 1) {
    parts.push(`risk ${risk.toFixed(2)}`)
  }
  const pattern = extensionField(record, 'matched_pattern', 256)
  if (pattern !== null) parts.push(`Matched ${pattern}`)
  return parts.length === 0 ? null : `${parts.join(', ')}.`
}

/**
 * Reads a generic gateway error message without leaking internals.
 *
 * @param status - HTTP status code.
 * @param body - Raw response text (may be empty or non-JSON).
 * @param verdict - Raw `X-CacheRelay-Vendor-Verdict` header value, or null
 * when absent. Preferred mapping signal for screening 422s; the body
 * stays the display source.
 * @returns A user-safe message describing what happened and what to do next.
 */
export function safeErrorMessage(status: number, body: string, verdict?: string | null): string {
  if (body.length > 0) {
    try {
      const parsed: unknown = JSON.parse(body)
      if (typeof parsed === 'object' && parsed !== null) {
        const record = parsed as Record<string, unknown>
        const screening = screeningRefusal(
          status,
          record,
          splitVerdict(parseVerdictHeader(verdict ?? null)),
        )
        if (screening !== null) return screening
        const detail = record.detail
        if (typeof detail === 'string' && detail.length > 0) {
          if (status === 403)
            return 'Request refused by gateway policy. Check the route and key scope, then retry.'
          return detail
        }
        const err = record.error
        if (typeof err === 'object' && err !== null) {
          const errorRecord = err as Record<string, unknown>
          if (status === 503 && errorRecord.code === 'NO_COMPLIANT_ECONOMY_PROVIDER') {
            return 'No compliant provider for economy routing. Lower the minimum quality tier or switch to quality, then retry.'
          }
          const message = errorRecord.message
          if (typeof message === 'string' && message.length > 0) {
            if (status === 429 && /budget exhausted/i.test(message)) return message
            if (status === 503 && errorRecord.code === 'DATA_SOVEREIGNTY_VIOLATION') {
              return 'No in-region provider is available. Policy block, not an outage.'
            }
            if (status === 429) return 'Rate limit reached. Wait for the reset window, then retry.'
            if (status === 503) return 'Gateway is temporarily unavailable. Retry shortly.'
            return message
          }
        }
        const topMessage = record.message
        if (typeof topMessage === 'string' && topMessage.length > 0) {
          return topMessage
        }
      }
    } catch {
      // Fall through to status-mapped generic messages.
    }
  }
  switch (status) {
    case 400:
      return 'Invalid request. Check the fields and try again.'
    case 401:
      return 'Missing or invalid API key. Update the key and retry.'
    case 403:
      return 'Request refused by gateway policy. Check the route and key scope, then retry.'
    case 404:
      return 'Resource not found. Refresh the list and try again.'
    case 409:
      return 'Conflict: the resource already exists or changed concurrently. Refresh and resolve, then retry.'
    case 413:
      return 'Request body too large. Shrink the payload and try again.'
    case 422:
      return 'Unprocessable request: a guardrail or idempotency check refused it. See details, then retry.'
    case 429:
      return 'Rate limit reached. Wait for the reset window, then retry.'
    case 502:
      return 'No upstream provider could serve this request. Check circuits, then retry.'
    case 503:
      return 'Gateway is temporarily unavailable. Retry shortly.'
    case 504:
      return 'Upstream request timed out. Retry, or narrow the request.'
    default:
      return `Request failed (HTTP ${String(status)}). Retry, or contact support if it persists.`
  }
}

export interface RequestOptions {
  signal?: AbortSignal
  /**
   * Act-as-self key selector (owned key hash hex or `default`).
   * Sent as `X-Act-As-Key` alongside the session JWT; never logged.
   */
  actAsKey?: string
  /**
   * Optional `A2A-Version` pin (`Major.Minor`) for agent calls. Omitted
   * lets the gateway fall back to the agent pin.
   */
  a2aVersion?: string
  /**
   * Idempotency key for the logical run. Generated per call when absent
   * and reused by the caller across retries, so a retried static
   * completion replays byte-identically instead of billing twice.
   */
  idempotencyKey?: string
  /**
   * Ignore the in-memory session and send the constructor token verbatim.
   * Gateway endpoints (`/v1/chat`, `/v1/embeddings`, `/v1/models`) need
   * this for pasted-key flows: the session JWT is not a virtual key, so
   * session precedence would turn every logged-in paste into a 401.
   */
  ignoreSession?: boolean
  /**
   * Receives raw response headers on every settled gateway response
   * (success and failure), plus the gateway `error.code` on deny paths
   * (null elsewhere). Lets the shell mirror operational headers into
   * a memory-only store without threading return shapes through call sites.
   */
  onHeaders?: (headers: Headers, code: string | null) => void
}

/**
 * Module-level headers reporter for the shell strip.
 *
 * @remarks
 * Every feature page constructs its own short-lived `GatewayClient`, so no
 * instance persists to carry a subscription. The layout registers one
 * process-wide reporter instead; per-call `RequestOptions.onHeaders` takes
 * precedence when both are set. The reporter must stay synchronous and
 * side-effect-light (a zustand `set`) so it never perturbs the transport.
 */
let headersReporter: ((headers: Headers, code: string | null) => void) | null = null

/**
 * Registers the process-wide response-headers reporter.
 *
 * @param reporter - Receiver for settled response headers plus the deny-path
 * error code, or null to clear.
 */
export function setHeadersReporter(
  reporter: ((headers: Headers, code: string | null) => void) | null,
): void {
  headersReporter = reporter
}

/**
 * Checks the pasted virtual-key shape without sending it. Gateway keys
 * are `gw-` plus exactly 32 `[A-Za-z0-9_-]` chars (35 total). Anything
 * else fails closed server-side; the form names truncation early so a
 * half-paste never reads as a revocation.
 *
 * @param key - Trimmed pasted key candidate.
 * @returns True for well-formed `gw-` keys.
 */
export function isWellFormedGatewayKey(key: string): boolean {
  return /^gw-[A-Za-z0-9_-]{32}$/.test(key)
}

/**
 * Mints one correlation id per request for admin audit attribution.
 * The gateway reads `X-Request-ID` for audit correlation; every
 * frontend-initiated request carries one.
 *
 * @returns A unique opaque id for the request.
 */
export function createRequestId(): string {
  const g = globalThis as { crypto?: { randomUUID?: () => string } }
  if (typeof g.crypto?.randomUUID === 'function') return g.crypto.randomUUID()
  return `${Date.now().toString(36)}-${Math.floor(Math.random() * 0xffffff).toString(36)}`
}

/**
 * Session-owned paths: 401s here mean the access token died (expiry or
 * revocation), never a bad gateway key. Public paths keep their errors
 * untouched so gateway-key problems are never misread as session loss.
 *
 * @param path - Gateway path starting with `/`.
 * @returns True for session-owned routes (excluding the login exchange).
 */
function isSessionRoute(path: string): boolean {
  if (path.startsWith('/v1/admin/')) return true
  if (path.startsWith('/v1/me/')) return true
  if (path.startsWith('/v1/auth/') && !path.startsWith('/v1/auth/login')) return true
  return false
}

/**
 * Sends one gateway request with network-error and status guards.
 *
 * @remarks
 * Single choke point for the transport: aborts propagate untouched so
 * callers can distinguish cancellation from failure, unreachable networks
 * map to an actionable message with the original error as `cause`, and
 * non-2xx responses throw {@link ApiError} carrying the request id,
 * rate-limit snapshot, cache status, and debug id. Bodies are read as text
 * (never blind `res.json()`) so empty or non-JSON errors stay safe.
 * A 401 on a session-owned route with a session present triggers one
 * refresh attempt (the restored token serves the *next* request; this one
 * still throws so callers never see a silently swapped credential). When
 * refresh fails the session clears and the UI falls back to locked states.
 *
 * @param base - Resolved API base (or `''` for same-origin).
 * @param path - Gateway path starting with `/`.
 * @param init - Fetch init.
 * @param opts - Optional abort signal and headers listener.
 * @returns The response; throws on network failure or non-2xx status.
 */
async function sendGatewayRequest(
  base: string,
  path: string,
  init: RequestInit,
  opts?: RequestOptions,
): Promise<Response> {
  let res: Response
  try {
    res = await fetch(`${base}${path}`, {
      ...init,
      ...(opts?.signal === undefined ? {} : { signal: opts.signal }),
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new Error('Network unreachable. Check the gateway URL and connection, then retry.', {
      cause: error,
    })
  }
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    const code = parseGatewayErrorCode(text)
    ;(opts?.onHeaders ?? headersReporter)?.(res.headers, code)
    if (res.status === 401 && useAuthStore.getState().session !== null && isSessionRoute(path)) {
      await refreshSession()
    }
    const baseMessage = safeErrorMessage(res.status, text, res.headers.get(VERDICT_HEADER))
    throw new ApiError({
      message:
        res.status === 401 && isSessionRoute(path)
          ? 'Session expired. Sign in again to continue.'
          : baseMessage,
      status: res.status,
      requestId: res.headers.get('X-Request-Id'),
      rateLimit: parseRateLimit(res.headers, code),
      budget: parseBudget(res.headers),
      cacheStatus: cacheStatusOf(res.headers),
      debugId: res.headers.get('X-Request-Debug'),
      code,
    })
  }
  ;(opts?.onHeaders ?? headersReporter)?.(res.headers, null)
  return res
}

/**
 * Type guard for owned-key rows. Unknown backend fields are ignored;
 * rows missing required fields are dropped, never crash.
 *
 * @param r - Unknown decoded row.
 * @returns True when the row carries the picker fields.
 */
function isOwnedKey(r: unknown): r is OwnedKey {
  if (typeof r !== 'object' || r === null) return false
  const record = r as Record<string, unknown>
  return (
    typeof record.keyId === 'string' &&
    typeof record.name === 'string' &&
    Array.isArray(record.allowedModels)
  )
}

/**
 * Type guard for provider rows. Unknown backend fields are ignored;
 * rows missing required fields are dropped, never crash.
 *
 * @param r - Unknown decoded row.
 * @returns True when the row carries the provider fields.
 */
function isProviderStatus(r: unknown): r is ProviderStatus {
  if (typeof r !== 'object' || r === null) return false
  const record = r as Record<string, unknown>
  return typeof record.name === 'string' && typeof record.circuitState === 'string'
}

/**
 * Derives a non-secret cache identity for a credential.
 *
 * @remarks Query keys must refetch when the credential changes, but must
 * never carry key material (query state is inspectable). FNV-1a is a
 * non-cryptographic mixer: it discriminates keys without being reversible
 * to them. Collisions only risk a stale catalog entry, never auth.
 *
 * @param key - Credential text (never stored or logged by the caller).
 * @returns Length-prefixed hex digest identifying the credential.
 */
export function keyFingerprint(key: string): string {
  let hash = 0x811c9dc5
  for (let i = 0; i < key.length; i += 1) {
    hash ^= key.charCodeAt(i)
    hash = Math.imul(hash, 0x01000193)
  }
  return `${String(key.length)}:${(hash >>> 0).toString(16)}`
}

/**
 * Wire-drift reporter: notified with the endpoint name whenever a gateway
 * response fails transport-boundary validation and is hidden instead of
 * crashing the screen. The shell mirrors it into the drift store.
 */
let driftReporter: ((endpoint: string) => void) | null = null

/**
 * Registers the process-wide wire-drift reporter.
 *
 * @param reporter - Receiver for hidden-payload endpoint names, or null
 * to clear.
 */
export function setDriftReporter(reporter: ((endpoint: string) => void) | null): void {
  driftReporter = reporter
}

/**
 * Records one hidden payload with the shell.
 *
 * @param endpoint - Endpoint name whose body failed validation.
 */
function noteDrift(endpoint: string): void {
  if (driftReporter !== null) driftReporter(endpoint)
}

const circuitRowSchema = z.object({
  provider: z.string(),
  // Open string by design: backend states added tomorrow must render via
  // the Unknown chip, never be dropped by validation.
  state: z.string(),
  failures: z.number(),
  cooldownMsRemaining: z.number(),
  halfOpenProbe: z.boolean(),
})

const budgetBalanceSchema = z.object({
  level: z.string(),
  subject: z.string(),
  minuteLimitMicros: z.number(),
  minuteSpentMicros: z.number(),
  monthLimitMicros: z.number(),
  monthSpentMicros: z.number(),
})

const budgetHoldSchema = z.object({
  requestId: z.string(),
  subject: z.string(),
  heldMicros: z.number(),
  settledMicros: z.number().nullable(),
  state: z.string(),
})

const tierStatsSchema = z.object({
  reachable: z.boolean(),
  usedBytes: z.number().nullable().default(null),
  maxBytes: z.number().nullable().default(null),
  usedPercent: z.number().nullable().default(null),
  maxmemoryPolicy: z.string().nullable().default(null),
  evictedKeysTotal: z.number().nullable().default(null),
  keyspaceHits: z.number().nullable().default(null),
  keyspaceMisses: z.number().nullable().default(null),
})

const cacheTiersSchema = z.object({
  generatedAt: z.string(),
  accounting: tierStatsSchema,
  cache: tierStatsSchema,
})

const apiKeyRowSchema = z.object({
  keyId: z.string(),
  keyPrefix: z.string(),
  ownerId: z.string(),
  name: z.string(),
  rpmLimit: z.number(),
  tpmLimit: z.number(),
  allowedModels: z.array(z.string()),
  allowedProviders: z.array(z.string()),
  enabled: z.boolean(),
  createdAt: z.string(),
  // Legacy rows may omit these entirely; absence reads as unresolvable.
  ownerUserId: z.string().nullable().default(null),
  ownerUsername: z.string().nullable().default(null),
  // Policy sets shipped later; absence reads as server default.
  allowedTools: z.array(z.string()).optional(),
  deniedTools: z.array(z.string()).optional(),
  allowedResources: z.array(z.string()).optional(),
  deniedResources: z.array(z.string()).optional(),
  allowedPrompts: z.array(z.string()).optional(),
  deniedPrompts: z.array(z.string()).optional(),
  allowedAgents: z.array(z.string()).optional(),
  deniedAgents: z.array(z.string()).optional(),
  injectionBlock: z.boolean().nullable().optional(),
  allowedCacheScopes: z.array(z.string()).optional(),
})

const ownerSummaryRowSchema = z.object({
  ownerId: z.string(),
  totalRequests: z.number(),
  totalPromptTokens: z.number(),
  totalCompletionTokens: z.number(),
  totalTokens: z.number(),
  totalCostUsdMicros: z.number(),
  totalCostUsd: z.string(),
  averageDurationMs: z.number(),
})

const modelSummaryRowSchema = z.object({
  provider: z.string(),
  model: z.string(),
  totalRequests: z.number(),
  totalPromptTokens: z.number(),
  totalCompletionTokens: z.number(),
  totalTokens: z.number(),
  totalCostUsdMicros: z.number(),
  totalCostUsd: z.string(),
  averageDurationMs: z.number(),
})

const providerSummaryRowSchema = z.object({
  provider: z.string(),
  totalRequests: z.number(),
  totalPromptTokens: z.number(),
  totalCompletionTokens: z.number(),
  totalTokens: z.number(),
  totalCostUsdMicros: z.number(),
  totalCostUsd: z.string(),
  averageDurationMs: z.number(),
})

const ledgerSummarySchema = z.object({
  totalRequests: z.number(),
  totalPromptTokens: z.number(),
  totalCompletionTokens: z.number(),
  totalTokens: z.number(),
  totalCostUsdMicros: z.number(),
  totalCostUsd: z.string(),
  averageDurationMs: z.number(),
  byOwner: z.array(ownerSummaryRowSchema),
  byModel: z.array(modelSummaryRowSchema),
  byProvider: z.array(providerSummaryRowSchema),
})

/** Zeroed summary shown with a drift notice when the wire shape changes. */
const EMPTY_LEDGER_SUMMARY: LedgerSummary = {
  totalRequests: 0,
  totalPromptTokens: 0,
  totalCompletionTokens: 0,
  totalTokens: 0,
  totalCostUsdMicros: 0,
  totalCostUsd: '0.000000',
  averageDurationMs: 0,
  byOwner: [],
  byModel: [],
  byProvider: [],
}

const ledgerRowSchema = z.object({
  id: z.string(),
  requestId: z.string(),
  ownerId: z.string(),
  provider: z.string(),
  model: z.string(),
  promptTokens: z.number(),
  completionTokens: z.number(),
  totalTokens: z.number(),
  costUsdMicros: z.number(),
  costUsd: z.string(),
  durationMs: z.number(),
  createdAt: z.string(),
})

const ledgerPageSchema = z.object({
  content: z.array(z.unknown()),
  page: z.number(),
  size: z.number(),
  totalElements: z.number(),
  totalPages: z.number(),
  hasNext: z.boolean(),
})

/** Empty page shown with a drift notice when the wire shape changes. */
const EMPTY_LEDGER_PAGE: PageResponse<LedgerLogEntry> = {
  content: [],
  page: 0,
  size: 0,
  totalElements: 0,
  totalPages: 0,
  hasNext: false,
}

const ledgerReceiptSchema = z.object({
  id: z.string(),
  requestId: z.string(),
  ownerId: z.string(),
  provider: z.string(),
  model: z.string(),
  promptTokens: z.number().nullable(),
  completionTokens: z.number().nullable(),
  totalTokens: z.number(),
  costUsdMicros: z.number(),
  costUsd: z.string(),
  durationMs: z.number(),
  cached: z.boolean().optional(),
  cacheTier: z.string().nullable().optional(),
  createdAt: z.string(),
})

const budgetRowSchema = z.object({
  id: z.string(),
  level: z.string(),
  subjectId: z.string(),
  minuteMicros: z.number(),
  monthMicros: z.number(),
  webhookUrl: z.string().nullable(),
  createdAt: z.string(),
  updatedAt: z.string(),
})

const hitlRowSchema = z.object({
  tokenId: z.string().regex(/^[0-9a-f]{32}$/),
  toolName: z.string(),
  serverName: z.string(),
  ownerId: z.string(),
  keyName: z.string(),
  createdAt: z.string(),
  expiresAt: z.string(),
})

const hitlDecisionSchema = z.object({
  status: z.string(),
  tokenId: z.string(),
  message: z.string(),
})

const hitlEnvelopeSchema = z.object({ approvals: z.array(z.unknown()) })

const modelIdRowSchema = z.object({ id: z.string() })

const modelQualitySchema = z.object({
  modelId: z.string(),
  tier: z.string(),
  benchmarkRefs: z.string().nullable(),
  updatedAt: z.string(),
})

const notificationRowSchema = z.object({
  id: z.string(),
  scope: z.string(),
  channel: z.string(),
  target: z.string(),
  secretRef: z.string().nullable(),
  minSeverity: z.string(),
  createdAt: z.string(),
})

const userSummarySchema = z.object({
  userId: z.string(),
  username: z.string(),
  admin: z.boolean(),
  disabled: z.boolean(),
  createdAt: z.string(),
})

const a2aCardSchema = z.object({
  protocolVersion: z.string(),
  name: z.string(),
  url: z.string(),
  description: z.string().nullable(),
  version: z.string().nullable(),
})

const alertProbeReceiptSchema = z.object({
  received: z.number(),
})

const inviteReceiptSchema = z.object({
  link: z.string(),
  emailed: z.boolean(),
})

const modelEnvelopeSchema = z.object({ data: z.array(z.unknown()) })

const modelCatalogEntrySchema = z.object({
  modelId: z.string(),
  provider: z.string(),
  mode: z.string(),
  inputCostPerToken: z.number(),
  outputCostPerToken: z.number(),
  cacheReadInputTokenCost: z.number().nullable(),
  cacheCreationInputTokenCost: z.number().nullable(),
  maxInputTokens: z.number().nullable(),
  maxOutputTokens: z.number().nullable(),
  qualityTier: z.string().nullable(),
  benchmarkRefs: z.string().nullable(),
  embeddingDimensions: z.number().nullable(),
})

/**
 * Mutation/creation response shapes. Read paths degrade to empty states,
 * but a mutation has no honest empty: the server may have applied the
 * change (a key may exist, a budget may be live), so a drifted body
 * surfaces as a safe error through the caller's existing error UI —
 * never a silent empty, never fabricated state, never a raw downstream
 * `TypeError` (DEF-09).
 */
const apiKeyCreatedSchema = z.object({
  keyId: z.string(),
  key: z.string(),
  keyPrefix: z.string(),
  ownerId: z.string(),
  name: z.string(),
  rpmLimit: z.number().optional(),
  tpmLimit: z.number().optional(),
  allowedModels: z.array(z.string()).optional(),
  allowedProviders: z.array(z.string()).optional(),
  enabled: z.boolean().optional(),
  createdAt: z.string().optional(),
  ownerUserId: z.string().nullable().optional(),
  ownerUsername: z.string().nullable().optional(),
  allowedTools: z.array(z.string()).optional(),
  deniedTools: z.array(z.string()).optional(),
  allowedResources: z.array(z.string()).optional(),
  deniedResources: z.array(z.string()).optional(),
  allowedPrompts: z.array(z.string()).optional(),
  deniedPrompts: z.array(z.string()).optional(),
  allowedAgents: z.array(z.string()).optional(),
  deniedAgents: z.array(z.string()).optional(),
  injectionBlock: z.boolean().nullable().optional(),
  allowedCacheScopes: z.array(z.string()).optional(),
})

const providerChainStepSchema = z.object({
  providerName: z.string(),
  modelOverride: z.string().nullable(),
})

const modelAliasSchema = z.object({
  name: z.string(),
  chain: z.array(providerChainStepSchema),
  strategy: z.string(),
  source: z.string(),
})

const cacheStatsSchema = z.object({
  enabled: z.boolean(),
  defaultScope: z.string(),
  similarityThreshold: z.number(),
  embeddingModel: z.string(),
  l0MaxBytes: z.number(),
  l0InMemoryTtlSeconds: z.number(),
  l1RedisEnabled: z.boolean(),
  l2SemanticEnabled: z.boolean(),
  polarityGuardEnabled: z.boolean(),
  entityGuardEnabled: z.boolean(),
})

const purgeCacheSchema = z.object({
  success: z.boolean(),
  message: z.string(),
  evictedScope: z.string(),
  evictedKeys: z.number(),
})

/**
 * Top-level guards for completion payloads. The mandate is the crash
 * vector (a missing or non-array `choices`/`data` breaks every consumer
 * below); nested chunk shapes stay backend-truth and flow through the
 * existing error UI on mismatch (DEF-09).
 */
const chatTopSchema = z.object({
  choices: z.array(z.unknown()),
  model: z.string(),
})

const embeddingsTopSchema = z.object({
  data: z.array(z.unknown()),
  model: z.string(),
})

const teamRowSchema = z.object({
  teamId: z.string(),
  teamName: z.string(),
  orgSlug: z.string(),
  role: z.string(),
  status: z.string(),
})

const orgTeamRowSchema = z.object({
  teamId: z.string(),
  orgSlug: z.string(),
  name: z.string(),
  idpGroupId: z.string(),
  activeMembers: z.number(),
})

const orgResponseSchema = z.object({
  id: z.string(),
  slug: z.string(),
  displayName: z.string(),
})

const memberResponseSchema = z.object({
  userId: z.string(),
  teamId: z.string(),
  role: z.string(),
  status: z.string(),
})

/**
 * Account UUID shape for team membership and invite placement.
 * Trimmed before testing; lowercase/uppercase hex both accepted.
 */
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

const identitySchema = z.object({
  userId: z.string(),
  username: z.string(),
  admin: z.boolean(),
})

/**
 * Validates row arrays: non-arrays degrade to empty, malformed rows are
 * dropped with a drift note. Never throws, never fabricates.
 *
 * @remarks `undefined` arrives only from empty `204` bodies (see
 * `request`): it degrades silently to empty — no content is not drift.
 *
 * @param rowSchema - Per-row shape.
 * @param value - Unknown decoded rows.
 * @param endpoint - Drift notice name.
 * @returns Valid rows only.
 */
function rowsOrEmpty<T>(rowSchema: z.ZodType<T>, value: unknown, endpoint: string): T[] {
  if (value === undefined) return []
  if (!Array.isArray(value)) {
    noteDrift(endpoint)
    return []
  }
  const out: T[] = []
  for (const row of value) {
    const parsed = rowSchema.safeParse(row)
    if (parsed.success) out.push(parsed.data)
    else noteDrift(endpoint)
  }
  return out
}

/**
 * Validates one value with a fallback: mismatches degrade to the fallback
 * with a drift note instead of throwing into the render tree. Empty `204`
 * bodies (`undefined`) degrade silently — no content is not drift.
 *
 * @param schema - Expected shape.
 * @param value - Unknown decoded body.
 * @param endpoint - Drift notice name.
 * @param fallback - Degraded value.
 * @returns The parsed body, or the fallback.
 */
function valueOr<T>(schema: z.ZodType<T>, value: unknown, endpoint: string, fallback: T): T {
  if (value === undefined) return fallback
  const parsed = schema.safeParse(value)
  if (parsed.success) return parsed.data
  noteDrift(endpoint)
  return fallback
}

/**
 * Validates one mutation/creation response: mismatches note drift and
 * reject with a safe error instead of flowing an unvalidated cast into
 * the render tree. Follows the `authMe` precedent (`auth-me`).
 *
 * @param schema - Expected body shape.
 * @param value - Unknown decoded body.
 * @param endpoint - Drift notice name.
 * @param label - Human subject for the safe error.
 * @returns The parsed body.
 */
function valueOrThrow<T>(schema: z.ZodType<T>, value: unknown, endpoint: string, label: string): T {
  if (value === undefined) {
    noteDrift(endpoint)
    throw new Error(`${label} changed shape. Try again.`)
  }
  const parsed = schema.safeParse(value)
  if (!parsed.success) {
    noteDrift(endpoint)
    throw new Error(`${label} changed shape. Try again.`)
  }
  return parsed.data
}

/**
 * Minimal typed gateway client over `fetch`.
 *
 * @remarks
 * Auth precedence: a logged-in human session attaches its short-lived
 * access JWT as Bearer; otherwise the caller-supplied virtual key goes
 * out as Bearer. The master secret has no UI path by design
 * (terminal/curl-only), so no `X-Admin-Key` branch exists here. Tokens
 * live in memory only (zustand store) and are never written to storage
 * by this client.
 */
export class GatewayClient {
  private readonly base: string
  private readonly token: string

  constructor(args: { base?: string; token?: string } = {}) {
    this.base = args.base ?? resolveApiBase()
    this.token = args.token ?? ''
  }

  private headers(
    extra?: Record<string, string>,
    actAsKey?: string,
    ignoreSession?: boolean,
  ): Record<string, string> {
    const h: Record<string, string> = {
      Accept: 'application/json',
      ...(extra ?? {}),
    }
    const session = ignoreSession === true ? null : useAuthStore.getState().session
    const bearer = session?.accessToken ?? this.token
    // No credential, no header: an empty `Bearer ` line confuses gateway
    // logs and suggests an authenticated call that never was.
    if (bearer.length > 0) h.Authorization = `Bearer ${bearer}`
    if (actAsKey !== undefined && actAsKey.length > 0) {
      h['X-Act-As-Key'] = actAsKey
    }
    h['X-Request-Id'] ??= createRequestId()
    return h
  }

  private async request<T>(path: string, init: RequestInit, opts?: RequestOptions): Promise<T> {
    const res = await sendGatewayRequest(this.base, path, init, opts)
    if (res.status === 204) return undefined as T
    return (await res.json()) as T
  }

  /**
   * Sends a request whose success carries no payload (for example DELETE).
   *
   * @param path - Gateway path.
   * @param init - Fetch init.
   * @param opts - Optional abort signal and headers listener.
   */
  private async requestEmpty(
    path: string,
    init: RequestInit,
    opts?: RequestOptions,
  ): Promise<void> {
    await sendGatewayRequest(this.base, path, init, opts)
  }

  /**
   * Sends a non-streaming chat completion.
   *
   * @remarks The top-level shape (`choices` array) is validated before
   * consumption; nested chunk shapes stay backend-truth (DEF-09).
   *
   * @param body - Chat request (stream is forced to false here; use the SSE client for streams).
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The completion payload.
   */
  async chat(body: ChatCompletionRequest, opts?: RequestOptions): Promise<ChatCompletionResponse> {
    const raw: unknown = await this.request<unknown>(
      '/v1/chat/completions',
      {
        method: 'POST',
        headers: this.headers(
          {
            'Content-Type': 'application/json',
            'Idempotency-Key': opts?.idempotencyKey ?? createRequestId(),
          },
          opts?.actAsKey,
          opts?.ignoreSession,
        ),
        body: JSON.stringify({ ...body, stream: false }),
      },
      opts,
    )
    valueOrThrow(chatTopSchema, raw, 'chat-completion', 'Completion')
    return raw as ChatCompletionResponse
  }

  /**
   * Creates embeddings for the given input.
   *
   * @remarks The top-level shape (`data` array) is validated before
   * consumption; nested vector shapes stay backend-truth (DEF-09).
   *
   * @param body - Embedding request.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Embedding vectors with index positions preserved.
   */
  async embeddings(body: EmbeddingRequest, opts?: RequestOptions): Promise<EmbeddingResponse> {
    const raw: unknown = await this.request<unknown>(
      '/v1/embeddings',
      {
        method: 'POST',
        headers: this.headers(
          {
            'Content-Type': 'application/json',
            'Idempotency-Key': opts?.idempotencyKey ?? createRequestId(),
          },
          opts?.actAsKey,
          opts?.ignoreSession,
        ),
        body: JSON.stringify(body),
      },
      opts,
    )
    valueOrThrow(embeddingsTopSchema, raw, 'embeddings', 'Embedding')
    return raw as EmbeddingResponse
  }

  /**
   * Lists public models.
   *
   * @remarks Act-as-self aware (backend `ModelController`): pass the owned
   * key hash via `opts.actAsKey` with a session to list without pasting
   * key material.
   *
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Model identifiers the gateway accepts.
   */
  async models(opts?: RequestOptions): Promise<{ data: { id: string }[] }> {
    const body: unknown = await this.request<unknown>(
      '/v1/models',
      { headers: this.headers(undefined, opts?.actAsKey, opts?.ignoreSession) },
      opts,
    )
    const envelope = valueOr(modelEnvelopeSchema, body, 'models', { data: [] })
    return { data: rowsOrEmpty(modelIdRowSchema, envelope.data, 'models') }
  }

  /**
   * Lists the caller's owned keys as metadata (never secrets).
   *
   * @remarks Backend truth (`MeKeyController`): session-owned `KeyResponse`
   * rows; unknown shapes degrade to empty, never throw.
   *
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Owned key metadata.
   */
  async myKeys(opts?: RequestOptions): Promise<{ keys: OwnedKey[] }> {
    const rows: unknown = await this.request<unknown>(
      '/v1/me/keys',
      { headers: this.headers(undefined, opts?.actAsKey) },
      opts,
    )
    if (!Array.isArray(rows)) return { keys: [] }
    return { keys: rows.filter(isOwnedKey) }
  }

  /**
   * Validates a 64-char SHA-256 hex key hash before self-service calls.
   *
   * @remarks Backend truth (`MeKeyController`): malformed hashes answer
   * 400 `malformed key hash`; the client rejects them before sending.
   *
   * @param hash - Key hash to check.
   * @param label - Human subject for the error.
   */
  private static assertKeyHash(hash: string, label: string): void {
    if (!/^[a-fA-F0-9]{64}$/.test(hash)) {
      throw new Error(`${label} needs a 64-char hex key hash.`)
    }
  }

  /**
   * Sets the default act-as key for the session account. Answers 204;
   * foreign or unknown hashes answer 404 (never 403, no oracle).
   *
   * @param keyId - 64-char hex hash of an owned key.
   * @param opts - Optional request options (abort signal, headers listener).
   * @throws Error synchronously when the hash is malformed (never sent).
   */
  setDefaultKey(keyId: string, opts?: RequestOptions): Promise<void> {
    GatewayClient.assertKeyHash(keyId, 'Default key')
    return this.requestEmpty(
      '/v1/me/keys/default',
      {
        method: 'PUT',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ keyId }),
      },
      opts,
    ).catch((error: unknown) => {
      if (error instanceof ApiError && error.status === 404) {
        throw new Error('Key not found: unknown or foreign key.')
      }
      throw error
    })
  }

  /**
   * Revokes one owned key (terminal tombstone, no inverse).
   *
   * @param hashHex - 64-char hex hash of an owned key.
   * @param opts - Optional request options (abort signal, headers listener).
   * @throws Error synchronously when the hash is malformed (never sent).
   */
  revokeOwnKey(hashHex: string, opts?: RequestOptions): Promise<void> {
    GatewayClient.assertKeyHash(hashHex, 'Key revocation')
    return this.requestEmpty(
      `/v1/me/keys/${encodeURIComponent(hashHex)}/revoke`,
      { method: 'POST', headers: this.headers() },
      opts,
    ).catch((error: unknown) => {
      if (error instanceof ApiError && error.status === 404) {
        throw new Error('Key not found: unknown or foreign key.')
      }
      throw error
    })
  }

  /**
   * Lists every effective model alias with its origin. Admin only.
   *
   * @remarks Backend truth (`AdminModelController`): `GET
   * /v1/admin/models` returns a `{ models }` envelope, file-bound
   * first. `source` is `file` (read-only) or `database` (editable).
   *
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Effective aliases.
   */
  async listModelAliases(opts?: RequestOptions): Promise<{ models: ModelAliasRecord[] }> {
    const body = await this.request<{ models: ModelAliasRecord[] }>(
      '/v1/admin/models',
      { headers: this.headers() },
      opts,
    )
    // A drifted gateway must degrade to an empty board, never throw.
    // `source` normalizes case-insensitively: the contract spells
    // `FILE`/`DATABASE` while the board gates on `file`/`database`.
    if (!Array.isArray(body.models)) return { models: [] }
    return {
      models: body.models.map((m) => {
        const source = typeof m.source === 'string' ? m.source.toLowerCase() : m.source
        return source === 'file' || source === 'database' ? { ...m, source } : m
      }),
    }
  }

  /**
   * Creates a database-managed model alias. Responds 201; 400 on invalid
   * payload, 409 on duplicate or file-bound name.
   *
   * @remarks The created record is validated: a drifted body rejects with
   * a safe error instead of flowing into the alias list (DEF-09).
   *
   * @param body - Alias name, provider chain, and strategy.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The created alias.
   */
  async createModelAlias(
    body: { name: string; chain: ProviderChainStep[]; strategy: string },
    opts?: RequestOptions,
  ): Promise<ModelAliasRecord> {
    const raw: unknown = await this.request<unknown>(
      '/v1/admin/models',
      {
        method: 'POST',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(body),
      },
      opts,
    )
    return valueOrThrow(modelAliasSchema, raw, 'models-create', 'Alias creation')
  }

  /**
   * Replaces the routing plan of a database-managed alias. File-bound
   * aliases answer 409 and stay read-only.
   *
   * @remarks The replaced record is validated like creation (DEF-09).
   *
   * @param name - Client facing model name (path, cannot rename).
   * @param body - Replacement chain and strategy.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The replaced alias.
   */
  async updateModelAlias(
    name: string,
    body: { chain: ProviderChainStep[]; strategy: string },
    opts?: RequestOptions,
  ): Promise<ModelAliasRecord> {
    const raw: unknown = await this.request<unknown>(
      `/v1/admin/models/${encodeURIComponent(name)}`,
      {
        method: 'PUT',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(body),
      },
      opts,
    )
    return valueOrThrow(modelAliasSchema, raw, 'models-update', 'Alias update')
  }

  /**
   * Deletes a database-managed alias. Answers 204; 404 when unknown, 409
   * when file-bound.
   *
   * @param name - Client facing model name.
   * @param opts - Optional request options (abort signal, headers listener).
   */
  deleteModelAlias(name: string, opts?: RequestOptions): Promise<void> {
    return this.requestEmpty(
      `/v1/admin/models/${encodeURIComponent(name)}`,
      { method: 'DELETE', headers: this.headers() },
      opts,
    )
  }

  /**
   * Reads one model quality rating. Unrated models answer 404
   * (`model is unrated`), which reads as null — the unrated state,
   * never an error.
   *
   * @remarks Backend truth (`AdminModelQualityController`): `GET
   * /v1/admin/model-quality/{modelId}` returns `QualityResponse`;
   * tier is one of FRONTIER, STANDARD, BUDGET.
   *
   * @param modelId - Exact model id.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The rating, or null when unrated.
   */
  async getModelQuality(modelId: string, opts?: RequestOptions): Promise<ModelQuality | null> {
    try {
      const raw: unknown = await this.request<unknown>(
        `/v1/admin/model-quality/${encodeURIComponent(modelId)}`,
        { headers: this.headers() },
        opts,
      )
      const parsed = modelQualitySchema.safeParse(raw)
      if (!parsed.success) {
        noteDrift('model-quality')
        return null
      }
      return parsed.data
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) return null
      throw error
    }
  }

  /**
   * Rates or re-rates a model (create-or-replace).
   *
   * @remarks Backend truth: `PUT /v1/admin/model-quality/{modelId}`
   * with `{ tier, benchmarkRefs }`; tier is required and uppercase;
   * blank refs read as null. Invalid ids, tiers, and refs answer 400.
   *
   * @param modelId - Exact model id (max 128, `[\w./:-]+`).
   * @param body - Tier plus optional benchmark refs.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The stored rating.
   */
  async setModelQuality(
    modelId: string,
    body: { tier: string; benchmarkRefs?: string | null },
    opts?: RequestOptions,
  ): Promise<ModelQuality> {
    if (!/^[\w./:-]{1,128}$/.test(modelId)) {
      throw new Error('Invalid model id.')
    }
    if (body.tier !== 'FRONTIER' && body.tier !== 'STANDARD' && body.tier !== 'BUDGET') {
      throw new Error('Tier must be FRONTIER, STANDARD, or BUDGET.')
    }
    const refs = body.benchmarkRefs ?? null
    if (refs !== null && refs.length > 2000) {
      throw new Error('Benchmark refs too long (max 2000).')
    }
    const raw: unknown = await this.request<unknown>(
      `/v1/admin/model-quality/${encodeURIComponent(modelId)}`,
      {
        method: 'PUT',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ tier: body.tier, benchmarkRefs: refs }),
      },
      opts,
    )
    return valueOrThrow(modelQualitySchema, raw, 'model-quality-write', 'Quality rating')
  }

  /**
   * Removes a model quality rating. Absent ratings answer 404.
   *
   * @param modelId - Exact model id.
   * @param opts - Optional request options (abort signal, headers listener).
   */
  clearModelQuality(modelId: string, opts?: RequestOptions): Promise<void> {
    return this.requestEmpty(
      `/v1/admin/model-quality/${encodeURIComponent(modelId)}`,
      { method: 'DELETE', headers: this.headers() },
      opts,
    )
  }

  /**
   * Searches the admin model catalog with pricing, windows, and quality.
   *
   * @remarks Backend truth (`AdminModelCatalogController`): `GET
   * /v1/admin/model-catalog` returns a `{ models }` envelope ordered by
   * model id. `limit` defaults to 50 and clamps to 1-200; `provider`
   * (max 64) and `q` (max 128) are optional filters. Four cost/window/
   * quality fields are nullable. Drifted bodies degrade to empty.
   *
   * @param query - Optional provider substring, query, and limit.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Catalog entries.
   */
  async searchModelCatalog(
    query: { provider?: string; q?: string; limit?: number } = {},
    opts?: RequestOptions,
  ): Promise<{ models: ModelCatalogEntry[] }> {
    const limit = Math.min(200, Math.max(1, Math.floor(query.limit ?? 50)))
    const params = new URLSearchParams({ limit: String(limit) })
    if (query.provider !== undefined && query.provider.length > 0) {
      params.set('provider', query.provider.slice(0, 64))
    }
    if (query.q !== undefined && query.q.length > 0) {
      params.set('q', query.q.slice(0, 128))
    }
    const body: unknown = await this.request<unknown>(
      `/v1/admin/model-catalog?${params.toString()}`,
      { headers: this.headers() },
      opts,
    )
    if (typeof body !== 'object' || body === null || !('models' in body)) {
      noteDrift('model-catalog')
      return { models: [] }
    }
    return {
      models: rowsOrEmpty(
        modelCatalogEntrySchema,
        (body as Record<string, unknown>).models,
        'model-catalog',
      ),
    }
  }

  /**
   * Reads configured upstream providers with live routing health.
   *
   * @remarks Backend truth (DTO-verified): `GET /v1/admin/providers`
   * returns a `{ providers }` envelope of `ProviderStatusResponse` rows.
   * A bare array is also accepted for backward compatibility with older
   * mocks. Key material never crosses; only the `keyConfigured` boolean.
   * Unknown validation strings degrade to grey at the call site. Rows
   * missing required fields are dropped, never crash.
   *
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns One status row per provider.
   */
  async listProviders(opts?: RequestOptions): Promise<{ providers: ProviderStatus[] }> {
    const body: unknown = await this.request<unknown>(
      '/v1/admin/providers',
      { headers: this.headers() },
      opts,
    )
    const rows: unknown =
      typeof body === 'object' && body !== null && 'providers' in body
        ? (body as Record<string, unknown>).providers
        : body
    if (!Array.isArray(rows)) return { providers: [] }
    return { providers: rows.filter(isProviderStatus) }
  }

  /**
   * Reads aggregated circuit state for every known provider.
   *
   * @remarks Backend truth (live-verified): `GET /v1/admin/circuits`
   * returns a bare array — never `/state`, never a `{ circuits }`
   * envelope. Rows carry live `failures` and `cooldownMsRemaining`
   * counters; there is no transition timestamp, so none is mapped.
   *
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns One snapshot per provider.
   */
  async circuitState(opts?: RequestOptions): Promise<{ circuits: CircuitSnapshot[] }> {
    const body: unknown = await this.request<unknown>(
      '/v1/admin/circuits',
      { headers: this.headers() },
      opts,
    )
    // The state union is narrower than the wire on purpose: unknown future
    // states validate as strings and render via the Unknown chip.
    const rows = rowsOrEmpty(circuitRowSchema, body, 'circuits')
    return {
      circuits: rows.map((r) => ({ ...r, state: r.state as CircuitSnapshot['state'] })),
    }
  }

  /**
   * Reads one provider circuit. Unknown providers answer 404, which
   * reads as null — never a crash.
   *
   * @param provider - Provider name.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The snapshot, or null when unknown.
   */
  async getCircuit(provider: string, opts?: RequestOptions): Promise<CircuitSnapshot | null> {
    try {
      const raw: unknown = await this.request<unknown>(
        `/v1/admin/circuits/${encodeURIComponent(provider)}`,
        { headers: this.headers() },
        opts,
      )
      const parsed = circuitRowSchema.safeParse(raw)
      if (!parsed.success) {
        noteDrift('circuits-single')
        return null
      }
      return { ...parsed.data, state: parsed.data.state as CircuitSnapshot['state'] }
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) return null
      throw error
    }
  }

  /**
   * Force-resets a provider circuit. Live-verified against the gateway.
   *
   * @remarks The reset answer is the observed post-reset snapshot in the
   * full `CircuitStateResponse` shape (not a separate receipt): unknown
   * future states validate as strings and render via the Unknown chip.
   *
   * @param provider - Provider name (for example `openai`).
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The post-reset snapshot.
   */
  async resetCircuit(provider: string, opts?: RequestOptions): Promise<CircuitSnapshot> {
    const raw: unknown = await this.request<unknown>(
      `/v1/admin/circuits/${encodeURIComponent(provider)}/reset`,
      {
        method: 'POST',
        headers: this.headers(),
      },
      opts,
    )
    const parsed = valueOrThrow(circuitRowSchema, raw, 'circuits-reset', 'Circuit reset')
    return { ...parsed, state: parsed.state as CircuitSnapshot['state'] }
  }

  /**
   * Reads MCP per-server breaker states. The shared `CircuitStateResponse`
   * DTO is reused: the `provider` key carries the MCP server name.
   *
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns One snapshot per MCP server.
   */
  async listMcpCircuits(opts?: RequestOptions): Promise<{ circuits: CircuitSnapshot[] }> {
    const body: unknown = await this.request<unknown>(
      '/v1/admin/mcp/circuits',
      { headers: this.headers() },
      opts,
    )
    const rows = rowsOrEmpty(circuitRowSchema, body, 'mcp-circuits')
    return {
      circuits: rows.map((r) => ({ ...r, state: r.state as CircuitSnapshot['state'] })),
    }
  }

  /**
   * Reads one MCP server circuit. Unknown servers answer 404 → null.
   *
   * @param server - MCP server id (for example `postgres`).
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The snapshot, or null when unknown.
   */
  async getMcpCircuit(server: string, opts?: RequestOptions): Promise<CircuitSnapshot | null> {
    try {
      const raw: unknown = await this.request<unknown>(
        `/v1/admin/mcp/circuits/${encodeURIComponent(server)}`,
        { headers: this.headers() },
        opts,
      )
      const parsed = circuitRowSchema.safeParse(raw)
      if (!parsed.success) {
        noteDrift('mcp-circuits-single')
        return null
      }
      return { ...parsed.data, state: parsed.data.state as CircuitSnapshot['state'] }
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) return null
      throw error
    }
  }

  /**
   * Force-resets one MCP server circuit, returning the post-reset snapshot.
   *
   * @param server - MCP server id.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The post-reset snapshot.
   */
  async resetMcpCircuit(server: string, opts?: RequestOptions): Promise<CircuitSnapshot> {
    const raw: unknown = await this.request<unknown>(
      `/v1/admin/mcp/circuits/${encodeURIComponent(server)}/reset`,
      {
        method: 'POST',
        headers: this.headers(),
      },
      opts,
    )
    const parsed = valueOrThrow(circuitRowSchema, raw, 'mcp-circuits-reset', 'MCP circuit reset')
    return { ...parsed, state: parsed.state as CircuitSnapshot['state'] }
  }

  /**
   * Lists virtual API keys (metadata only, never plaintext).
   *
   * @param opts - Optional owner filter, abort signal, headers listener.
   * @returns Key metadata records.
   */
  async listKeys(
    opts?: RequestOptions & { ownerId?: string | undefined },
  ): Promise<{ keys: ApiKeyRecord[] }> {
    const owner = opts?.ownerId?.trim()
    const path =
      owner !== undefined && owner.length > 0
        ? `/v1/admin/keys?ownerId=${encodeURIComponent(owner)}`
        : '/v1/admin/keys'
    const body: unknown = await this.request<unknown>(
      path,
      {
        headers: this.headers(),
      },
      opts,
    )
    return { keys: rowsOrEmpty(apiKeyRowSchema, body, 'keys') }
  }

  /**
   * Reads one key by hash (metadata only, never plaintext).
   *
   * @remarks Backend truth: `GET /v1/admin/keys/{hashHex}` answers 200,
   * empty-body 404 when unknown, and 400 for malformed hashes (rejected
   * client-side before sending).
   *
   * @param hashHex - 64 lowercase hex key hash.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The key record, or null when unknown.
   * @throws Error synchronously when the hash is malformed (never sent).
   */
  async getKey(hashHex: string, opts?: RequestOptions): Promise<ApiKeyRecord | null> {
    GatewayClient.assertKeyHash(hashHex, 'Key read')
    try {
      const raw: unknown = await this.request<unknown>(
        `/v1/admin/keys/${encodeURIComponent(hashHex)}`,
        {
          headers: this.headers(),
        },
        opts,
      )
      return valueOrThrow(apiKeyRowSchema, raw, 'keys-read', 'Key read')
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) return null
      throw error
    }
  }

  /**
   * Creates a virtual key. Plaintext is exposed exactly once.
   *
   * @remarks Backend truth (live-verified): `ownerId`, `ownerUserId`, and
   * `name` are required; `0` means unlimited for both limits; empty model
   * sets mean all allowed. Unknown or disabled owners answer `400`. There
   * is no daily quota — TPM is the token dimension.
   *
   * @param body - Key parameters.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Metadata plus the single-exposure plaintext.
   */
  async createKey(
    body: {
      ownerId: string
      ownerUserId: string
      name: string
      rpmLimit: number
      tpmLimit: number
      allowedModels: string[]
    } & KeyPolicy,
    opts?: RequestOptions,
  ): Promise<ApiKeyCreated> {
    const raw: unknown = await this.request<unknown>(
      '/v1/admin/keys',
      {
        method: 'POST',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(body),
      },
      opts,
    )
    return valueOrThrow(apiKeyCreatedSchema, raw, 'keys-create', 'Key creation')
  }

  /**
   * Toggles a key between enabled and disabled. Reversible.
   *
   * @remarks Re-enabling a terminally revoked key answers `400`; the
   * message names the reason and surfaces inline.
   *
   * @param id - Key identifier.
   * @param enabled - Desired state.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Updated metadata.
   */
  async setKeyEnabled(id: string, enabled: boolean, opts?: RequestOptions): Promise<ApiKeyRecord> {
    const raw: unknown = await this.request<unknown>(
      `/v1/admin/keys/${encodeURIComponent(id)}`,
      {
        method: 'PATCH',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ enabled }),
      },
      opts,
    )
    return valueOrThrow(apiKeyRowSchema, raw, 'keys-update', 'Key update')
  }

  /**
   * Patches ownership and policy of a key. Reversible except revoke.
   *
   * @remarks Backend truth: `ownerUserId != null` routes to the atomic
   * patch path, otherwise the legacy update path. Only set fields
   * travel; the response is the updated metadata.
   *
   * @param id - Key hash hex.
   * @param patch - Owner and policy fields to replace.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Updated metadata.
   */
  async patchKey(
    id: string,
    patch: { ownerUserId?: string } & KeyPolicy,
    opts?: RequestOptions,
  ): Promise<ApiKeyRecord> {
    const raw: unknown = await this.request<unknown>(
      `/v1/admin/keys/${encodeURIComponent(id)}`,
      {
        method: 'PATCH',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(patch),
      },
      opts,
    )
    return valueOrThrow(apiKeyRowSchema, raw, 'keys-patch', 'Key patch')
  }

  /**
   * Terminally revokes a key. There is no inverse.
   *
   * @param id - Key identifier.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Tombstoned metadata.
   */
  async revokeKey(id: string, opts?: RequestOptions): Promise<ApiKeyRecord> {
    const raw: unknown = await this.request<unknown>(
      `/v1/admin/keys/${encodeURIComponent(id)}/revoke`,
      { method: 'POST', headers: this.headers() },
      opts,
    )
    return valueOrThrow(apiKeyRowSchema, raw, 'keys-revoke', 'Key revocation')
  }

  /**
   * Deletes a virtual key.
   *
   * @param id - Key identifier.
   * @param opts - Optional request options (abort signal, headers listener).
   */
  deleteKey(id: string, opts?: RequestOptions): Promise<void> {
    return this.requestEmpty(
      `/v1/admin/keys/${encodeURIComponent(id)}`,
      { method: 'DELETE', headers: this.headers() },
      opts,
    )
  }

  /**
   * Reads aggregated billing.
   *
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Totals across tenants.
   */
  async ledgerSummary(opts?: RequestOptions): Promise<LedgerSummary> {
    const body: unknown = await this.request<unknown>(
      '/v1/admin/ledger/summary',
      { headers: this.headers() },
      opts,
    )
    return valueOr(ledgerSummarySchema, body, 'ledger-summary', EMPTY_LEDGER_SUMMARY)
  }

  /**
   * Reads a page of the audit log.
   *
   * @remarks Backend truth (`AdminLedgerController`): the path is
   * `/v1/admin/ledger/entries` returning a `PageResponse` envelope —
   * never `/logs`, never a bare `{ entries }` array. Optional filters
   * scope server-side (unknown sort props fall back to newest first);
   * the 90-day window is enforced server-side with 400 on violation.
   *
   * @param page - Zero-based page index.
   * @param size - Page size (backend clamps to its maximum).
   * @param filters - Optional server filters (owner, provider, model,
   * window) plus sort property.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The page envelope with entry rows.
   */
  async ledgerLogs(
    page: number,
    size: number,
    filters: {
      ownerId?: string
      provider?: string
      model?: string
      from?: string
      to?: string
      sort?: string
    } = {},
    opts?: RequestOptions,
  ): Promise<PageResponse<LedgerLogEntry>> {
    const q = new URLSearchParams({ page: String(page), size: String(size) })
    for (const key of ['ownerId', 'provider', 'model', 'from', 'to', 'sort'] as const) {
      const value = filters[key]
      if (value !== undefined && value.trim().length > 0) q.set(key, value.trim())
    }
    const body: unknown = await this.request<unknown>(
      `/v1/admin/ledger/entries?${q.toString()}`,
      { headers: this.headers() },
      opts,
    )
    const envelope = valueOr(ledgerPageSchema, body, 'ledger-entries', EMPTY_LEDGER_PAGE)
    return {
      ...envelope,
      content: rowsOrEmpty(ledgerRowSchema, envelope.content, 'ledger-entries'),
    }
  }

  /**
   * Reads one full receipt by request id.
   *
   * @remarks Backend truth (`AdminLedgerController`): unknown ids answer
   * empty-body `404`. The inspector treats that as a gone receipt, never
   * a crash.
   *
   * @param requestId - Receipt to hydrate.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The twelve-field receipt.
   */
  async ledgerReceipt(requestId: string, opts?: RequestOptions): Promise<LedgerReceipt | null> {
    const body: unknown = await this.request<unknown>(
      `/v1/admin/ledger/entries/${encodeURIComponent(requestId)}`,
      { headers: this.headers() },
      opts,
    )
    // Null reads as a gone receipt in the inspector (same as empty 404),
    // never a blank screen. Money is never fabricated: no zeroed fallback.
    const parsed = ledgerReceiptSchema.safeParse(body)
    if (parsed.success) return parsed.data
    noteDrift('ledger-receipt')
    return null
  }

  /**
   * Reads cache configuration flags.
   *
   * @remarks The tier flags render as live config, so a drifted body
   * rejects with a safe error instead of rendering fabricated toggles
   * (DEF-09).
   *
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Tier configuration (scopes, caps, tier and guard flags).
   */
  async cacheStats(opts?: RequestOptions): Promise<CacheStats> {
    const raw: unknown = await this.request<unknown>(
      '/v1/admin/cache/stats',
      { headers: this.headers() },
      opts,
    )
    return valueOrThrow(cacheStatsSchema, raw, 'cache-stats', 'Cache stats')
  }

  /**
   * Reads live tier telemetry: fill, eviction, and hit/miss counters.
   *
   * @remarks Backend truth: `GET /v1/admin/cache/tiers` answers a
   * memoised probe; dead tiers degrade to absent metrics, never errors —
   * check `reachable` before reading any numeric.
   *
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Generation time plus both tier probes.
   */
  async cacheTiers(opts?: RequestOptions): Promise<CacheTiers> {
    const raw: unknown = await this.request<unknown>(
      '/v1/admin/cache/tiers',
      { headers: this.headers() },
      opts,
    )
    return valueOrThrow(cacheTiersSchema, raw, 'cache-tiers', 'Cache tiers')
  }

  /**
   * Purges cache entries, optionally scoped to one owner.
   *
   * @remarks Backend truth (live-verified): `DELETE /v1/admin/cache`
   * with optional `ownerId` scope. Glob characters in `ownerId` answer
   * 400 with an INVALID receipt. `evictedKeys` counts Redis SCAN-deletes
   * only. Unknown drift rejects with a safe error (DEF-09).
   *
   * @param ownerId - Optional tenant scope; omitted purges globally.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Full purge receipt with message and evicted key count.
   */
  async purgeCache(ownerId?: string, opts?: RequestOptions): Promise<CachePurge> {
    const q = ownerId === undefined ? '' : `?${new URLSearchParams({ ownerId }).toString()}`
    const raw: unknown = await this.request<unknown>(
      `/v1/admin/cache${q}`,
      { method: 'DELETE', headers: this.headers() },
      opts,
    )
    return valueOrThrow(purgeCacheSchema, raw, 'cache-purge', 'Cache purge')
  }

  /**
   * Lists budgets.
   *
   * @remarks Backend truth (live-verified): `GET /v1/admin/budgets`
   * returns a bare array of `{id, level, subjectId, minuteMicros,
   * monthMicros, webhookUrl, createdAt, updatedAt}`. There is no
   * single-limit or spent field — minute-vs-month is the money truth.
   *
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Budget records.
   */
  async listBudgets(opts?: RequestOptions): Promise<{ budgets: BudgetRecord[] }> {
    const body: unknown = await this.request<unknown>(
      '/v1/admin/budgets',
      { headers: this.headers() },
      opts,
    )
    return { budgets: rowsOrEmpty(budgetRowSchema, body, 'budgets') }
  }

  /**
   * Creates a budget.
   *
   * @remarks Backend truth (live-verified): `{level, subjectId,
   * minuteMicros, monthMicros, webhookUrl?}`; minute/month default 0
   * means none. Display uses subjectId — there is no name field.
   *
   * @param body - Budget parameters.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The created record.
   */
  async createBudget(
    body: {
      level: string
      subjectId: string
      minuteMicros: number
      monthMicros: number
      webhookUrl?: string
    },
    opts?: RequestOptions,
  ): Promise<BudgetRecord> {
    const raw: unknown = await this.request<unknown>(
      '/v1/admin/budgets',
      {
        method: 'POST',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(body),
      },
      opts,
    )
    return valueOrThrow(budgetRowSchema, raw, 'budgets-create', 'Budget creation')
  }

  /**
   * Reads spend-vs-cap for one budget subject. Absent caps read as zero
   * (indistinguishable from zero-spend by design); Redis outage answers
   * 503.
   *
   * @param level - KEY, TEAM, or ORG.
   * @param subject - Budget subject id.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Limits plus live spend.
   */
  async getBudgetBalance(
    level: string,
    subject: string,
    opts?: RequestOptions,
  ): Promise<BudgetBalance> {
    const raw: unknown = await this.request<unknown>(
      `/v1/admin/budgets/${encodeURIComponent(level)}/${encodeURIComponent(subject)}/balance`,
      { headers: this.headers() },
      opts,
    )
    return valueOrThrow(budgetBalanceSchema, raw, 'budgets-balance', 'Budget balance')
  }

  /**
   * Reads one budget hold. Expired or unknown ids answer 404, which
   * reads as null — never a crash, never fabricated spend.
   *
   * @remarks Live holds read `HOLD`, never the docs-only `ACTIVE`.
   *
   * @param requestId - Hold correlation id.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The hold, or null when expired/unknown.
   */
  async getBudgetHold(requestId: string, opts?: RequestOptions): Promise<BudgetHold | null> {
    try {
      const raw: unknown = await this.request<unknown>(
        `/v1/admin/budgets/holds/${encodeURIComponent(requestId)}`,
        { headers: this.headers() },
        opts,
      )
      const parsed = budgetHoldSchema.safeParse(raw)
      if (!parsed.success) {
        noteDrift('budgets-hold')
        return null
      }
      return parsed.data
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) return null
      throw error
    }
  }

  /**
   * Replaces both caps (and webhook) of a budget. Full-replace
   * semantics: omitted caps read as zero (no cap), not preserved.
   * Level and subject are immutable post-create. Concurrent races
   * answer 409 `budget changed concurrently, retry`.
   *
   * @param id - Budget UUID.
   * @param body - Replacement caps plus optional webhook (null clears it).
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The updated record.
   */
  async updateBudget(
    id: string,
    body: { minuteMicros: number; monthMicros: number; webhookUrl?: string | null },
    opts?: RequestOptions,
  ): Promise<BudgetRecord> {
    const raw: unknown = await this.request<unknown>(
      `/v1/admin/budgets/${encodeURIComponent(id)}`,
      {
        method: 'PUT',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(body),
      },
      opts,
    )
    return valueOrThrow(budgetRowSchema, raw, 'budgets-update', 'Budget update')
  }

  /**
   * Deletes a budget. Live spend snapshots into audit — chargeback
   * survives the cap. Unknown ids answer 404.
   *
   * @param id - Budget UUID.
   * @param opts - Optional request options (abort signal, headers listener).
   */
  deleteBudget(id: string, opts?: RequestOptions): Promise<void> {
    return this.requestEmpty(
      `/v1/admin/budgets/${encodeURIComponent(id)}`,
      { method: 'DELETE', headers: this.headers() },
      opts,
    )
  }

  /**
   * Lists pending HITL approvals (newest first, capped server-side).
   *
   * @remarks Backend truth (`AdminMcpApprovalController`): the envelope
   * `{ approvals }` carries `PendingApprovalSummary` rows with tool args
   * stripped. There is no `approvalId` — identity is `tokenId`.
   *
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Pending approval queue.
   */
  async hitlPending(opts?: RequestOptions): Promise<{ approvals: HitlApproval[] }> {
    const body: unknown = await this.request<unknown>(
      '/v1/admin/mcp/approvals/pending',
      { headers: this.headers() },
      opts,
    )
    // Older doubles answered a bare array; the contract promises an
    // envelope. Both validate row by row, never crash.
    const envelope = Array.isArray(body)
      ? { approvals: body }
      : valueOr(hitlEnvelopeSchema, body, 'hitl-pending', { approvals: [] })
    return { approvals: rowsOrEmpty(hitlRowSchema, envelope.approvals, 'hitl-pending') }
  }

  /**
   * Hydrates one pending approval with decrypted tool args for review.
   *
   * @remarks Backend truth: `GET
   * /v1/admin/mcp/approvals/{tokenId}` returns the full metadata JSON
   * with sealed args decrypted; undecryptable values render as a
   * placeholder. Malformed ids answer 400; missing/expired answer 404.
   *
   * @param tokenId - 32-char lowercase hex approval token.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The metadata JSON (shape varies by tool).
   * @throws Error synchronously on malformed token ids (never sent).
   */
  async hitlDetail(tokenId: string, opts?: RequestOptions): Promise<unknown> {
    if (!/^[0-9a-f]{32}$/.test(tokenId)) {
      throw new Error('Approval token must be 32 lowercase hex chars.')
    }
    return this.request<unknown>(
      `/v1/admin/mcp/approvals/${encodeURIComponent(tokenId)}`,
      { headers: this.headers() },
      opts,
    )
  }

  /**
   * Decides a HITL approval with an optional human rationale.
   *
   * @remarks Backend truth: identity is path-only; the body carries an
   * optional `reason` (max 500). `decidedBy` is accepted but ignored —
   * the server records the authenticated identity. Approve holds 300s;
   * reject tombstones 24h. The `{ status, tokenId, message }` receipt
   * drives the toast.
   *
   * @param tokenId - Approval token id.
   * @param approved - True to approve, false to reject.
   * @param meta - Optional reason plus operator identity for the trail.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The decision receipt.
   */
  async decideHitl(
    tokenId: string,
    approved: boolean,
    meta: { reason?: string; decidedBy?: string } = {},
    opts?: RequestOptions,
  ): Promise<HitlDecision> {
    const action = approved ? 'approve' : 'reject'
    const raw: unknown = await this.request<unknown>(
      `/v1/admin/mcp/approvals/${encodeURIComponent(tokenId)}/${action}`,
      {
        method: 'POST',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({
          ...(meta.reason === undefined ? {} : { reason: meta.reason.slice(0, 500) }),
          ...(meta.decidedBy === undefined ? {} : { decidedBy: meta.decidedBy.slice(0, 128) }),
        }),
      },
      opts,
    )
    return valueOrThrow(hitlDecisionSchema, raw, 'hitl-decide', 'Approval decision')
  }

  /**
   * Lists MCP tools via JSON-RPC `tools/list`.
   *
   * @remarks Backend truth: `POST /v1/mcp` with a JSON-RPC envelope;
   * the result carries `tools[]`. A 403 means the catalog is suspended
   * upstream (surfaced by callers, never fabricated). Tool fields are
   * read defensively — malformed entries are skipped, never crash.
   *
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Tools plus the suspended signal on 403.
   */
  async mcpTools(opts?: RequestOptions): Promise<{ tools: McpTool[] } | McpSuspended> {
    const init: RequestInit = {
      method: 'POST',
      headers: this.headers(
        {
          'Content-Type': 'application/json',
          'MCP-Protocol-Version': MCP_PROTOCOL_VERSION,
          'Mcp-Method': 'tools/list',
        },
        opts?.actAsKey,
        opts?.ignoreSession,
      ),
      body: JSON.stringify({
        jsonrpc: '2.0',
        id: 'tools-list',
        method: 'tools/list',
        params: { _meta: mcpMeta() },
      }),
    }
    if (opts?.signal !== undefined) init.signal = opts.signal
    let res: Response
    try {
      res = await fetch(`${this.base}/v1/mcp`, init)
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') throw error
      throw new Error('Network unreachable. Check the gateway URL and connection, then retry.', {
        cause: error,
      })
    }
    if (res.status === 403) {
      ;(opts?.onHeaders ?? headersReporter)?.(res.headers, null)
      return { suspended: true as const, status: res.status }
    }
    if (res.status === 202) {
      // Notification-style ack: accepted with no content. No pipeline ran,
      // so there is no catalog to render and no error to report.
      ;(opts?.onHeaders ?? headersReporter)?.(res.headers, null)
      return { tools: [] }
    }
    if (!res.ok) {
      const text = await res.text().catch(() => '')
      if (res.status === 404) {
        const envelope = rpcEnvelopeError(text)
        if (envelope !== null) {
          throw new Error(mcpErrorMessage(envelope.code, envelope.message))
        }
      }
      throw new ApiError({
        message: safeErrorMessage(res.status, text),
        status: res.status,
        requestId: res.headers.get('X-Request-Id'),
        rateLimit: parseRateLimit(res.headers),
        budget: parseBudget(res.headers),
        cacheStatus: cacheStatusOf(res.headers),
        debugId: res.headers.get('X-Request-Debug'),
        code: parseGatewayErrorCode(text),
      })
    }
    const body: unknown = await res.json().catch(() => null)
    ;(opts?.onHeaders ?? headersReporter)?.(res.headers, null)
    if (typeof body !== 'object' || body === null) return { tools: [] }
    const envelope = body as Record<string, unknown>
    if (typeof envelope.error === 'object' && envelope.error !== null) {
      const errRecord = envelope.error as Record<string, unknown>
      const code = typeof errRecord.code === 'number' ? errRecord.code : -32603
      const message = typeof errRecord.message === 'string' ? errRecord.message : null
      throw new Error(mcpErrorMessage(code, message))
    }
    const result = envelope.result
    if (typeof result !== 'object' || result === null) return { tools: [] }
    const raw = (result as Record<string, unknown>).tools
    if (!Array.isArray(raw)) return { tools: [] }
    const tools: McpTool[] = []
    for (const entry of raw) {
      if (typeof entry !== 'object' || entry === null) continue
      const record = entry as Record<string, unknown>
      if (typeof record.name !== 'string' || record.name.length === 0) continue
      const annotations =
        typeof record.annotations === 'object' && record.annotations !== null
          ? (record.annotations as McpToolAnnotations)
          : null
      tools.push({
        name: record.name,
        description: typeof record.description === 'string' ? record.description : null,
        inputSchema: 'inputSchema' in record ? record.inputSchema : null,
        annotations,
      })
    }
    return { tools }
  }

  /**
   * Invokes one MCP tool via JSON-RPC `tools/call`.
   *
   * @remarks Backend truth: `POST /v1/mcp` with `{ jsonrpc, id,
   * method: 'tools/call', params: { name, arguments } }`. RPC errors
   * map through {@link mcpErrorMessage} so `METHOD_NOT_FOUND` and
   * `INVALID_PARAMS` name the contract fault instead of generic text.
   *
   * @param toolName - Tool name (`server__tool`).
   * @param args - Tool arguments (must be JSON-serializable).
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The result payload (shape varies by tool).
   */
  async mcpCall(
    toolName: string,
    args: Record<string, unknown>,
    opts?: RequestOptions,
  ): Promise<unknown> {
    const init: RequestInit = {
      method: 'POST',
      headers: this.headers(
        {
          'Content-Type': 'application/json',
          'MCP-Protocol-Version': MCP_PROTOCOL_VERSION,
          'Mcp-Method': 'tools/call',
        },
        opts?.actAsKey,
        opts?.ignoreSession,
      ),
      body: JSON.stringify({
        jsonrpc: '2.0',
        id: `call-${String(Date.now())}`,
        method: 'tools/call',
        params: { name: toolName, arguments: args, _meta: mcpMeta() },
      }),
    }
    if (opts?.signal !== undefined) init.signal = opts.signal
    let res: Response
    try {
      res = await fetch(`${this.base}/v1/mcp`, init)
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') throw error
      throw new Error('Network unreachable. Check the gateway URL and connection, then retry.', {
        cause: error,
      })
    }
    if (res.status === 202) {
      // Notification-style ack: accepted with no content. The call ran no
      // pipeline, so there is no result to render and no error to report.
      ;(opts?.onHeaders ?? headersReporter)?.(res.headers, null)
      return null
    }
    if (!res.ok) {
      const text = await res.text().catch(() => '')
      if (res.status === 404) {
        const envelope = rpcEnvelopeError(text)
        if (envelope !== null) {
          throw new Error(mcpErrorMessage(envelope.code, envelope.message))
        }
      }
      throw new ApiError({
        message: safeErrorMessage(res.status, text),
        status: res.status,
        requestId: res.headers.get('X-Request-Id'),
        rateLimit: parseRateLimit(res.headers),
        budget: parseBudget(res.headers),
        cacheStatus: cacheStatusOf(res.headers),
        debugId: res.headers.get('X-Request-Debug'),
        code: parseGatewayErrorCode(text),
      })
    }
    const body: unknown = await res.json().catch(() => null)
    ;(opts?.onHeaders ?? headersReporter)?.(res.headers, null)
    if (typeof body !== 'object' || body === null) throw new Error('Tool returned no payload.')
    const envelope = body as Record<string, unknown>
    if (typeof envelope.error === 'object' && envelope.error !== null) {
      const errRecord = envelope.error as Record<string, unknown>
      const code = typeof errRecord.code === 'number' ? errRecord.code : -32603
      const message = typeof errRecord.message === 'string' ? errRecord.message : null
      throw new Error(mcpErrorMessage(code, message))
    }
    return envelope.result ?? null
  }

  /**
   * Reads one A2A agent card with gateway-rewritten URLs.
   *
   * @remarks Backend truth (`A2aProxyController`): `GET
   * /v1/a2a/{agent}/card` is key-gated with RBAC; unknown, disabled, or
   * denied agents answer indistinguishable 404. Card `url` fields are
   * rewritten to the public base.
   *
   * @param agent - Agent identifier.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The agent card.
   */
  async a2aCard(agent: string, opts?: RequestOptions): Promise<A2aAgentCard> {
    const raw: unknown = await this.request<unknown>(
      `/v1/a2a/${encodeURIComponent(agent)}/card`,
      {
        headers: this.headers(undefined, opts?.actAsKey, opts?.ignoreSession),
      },
      opts,
    )
    return valueOrThrow(a2aCardSchema, raw, 'a2a-card', 'Agent card')
  }

  /**
   * Relays one JSON-RPC call to an A2A agent.
   *
   * @remarks Backend truth: `POST /v1/a2a/{agent}` accepts exactly
   * `message/send`, `message/stream`, `tasks/get`, `tasks/cancel`;
   * unknown methods answer 200 + `-32601`. RPC faults map through
   * {@link mcpErrorMessage}.
   *
   * @param agent - Agent identifier.
   * @param method - One of the four supported methods.
   * @param params - Method params (must be JSON-serializable).
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The result payload (shape varies by method).
   */
  async a2aInvoke(
    agent: string,
    method: string,
    params: Record<string, unknown>,
    opts?: RequestOptions,
  ): Promise<unknown> {
    if (
      method !== 'message/send' &&
      method !== 'message/stream' &&
      method !== 'tasks/get' &&
      method !== 'tasks/cancel'
    ) {
      throw new Error(
        `Unknown A2A method: ${method}. Use message/send, message/stream, tasks/get, or tasks/cancel.`,
      )
    }
    const raw: unknown = await this.request<unknown>(
      `/v1/a2a/${encodeURIComponent(agent)}`,
      {
        method: 'POST',
        headers: this.headers(
          {
            'Content-Type': 'application/json',
            ...(opts?.a2aVersion === undefined || opts.a2aVersion.length === 0
              ? {}
              : { 'A2A-Version': opts.a2aVersion }),
          },
          opts?.actAsKey,
          opts?.ignoreSession,
        ),
        body: JSON.stringify({ jsonrpc: '2.0', id: `a2a-${String(Date.now())}`, method, params }),
      },
      opts,
    )
    if (typeof raw !== 'object' || raw === null) throw new Error('Agent returned no payload.')
    const envelope = raw as Record<string, unknown>
    if (typeof envelope.error === 'object' && envelope.error !== null) {
      const errRecord = envelope.error as Record<string, unknown>
      const code = typeof errRecord.code === 'number' ? errRecord.code : -32603
      const message = typeof errRecord.message === 'string' ? errRecord.message : null
      throw new Error(mcpErrorMessage(code, message))
    }
    return envelope.result ?? null
  }

  /**
   * Builds a streaming `message/stream` relay request for SSE delivery.
   *
   * @remarks Backend truth (`A2aProxyController`): `message/stream`
   * relays upstream SSE byte-for-byte (`text/event-stream`, each `data:`
   * frame a complete JSON-RPC response). No terminal marker is ever
   * sent: a clean close means complete, a mid-stream failure means
   * truncation. The page drives `openSseStream` with this shape so URLs,
   * versions, and auth stay in one place.
   *
   * @param agent - Agent identifier.
   * @param params - Method params (must be JSON-serializable).
   * @param version - Optional `A2A-Version` pin (`Major.Minor`); omitted
   * lets the gateway fall back to the agent pin.
   * @returns URL, headers, and body for the SSE POST.
   */
  a2aStreamRequest(
    agent: string,
    params: Record<string, unknown>,
    version?: string,
  ): { url: string; headers: Record<string, string>; body: unknown } {
    return {
      url: `${this.base}/v1/a2a/${encodeURIComponent(agent)}`,
      headers: this.headers(
        {
          'Content-Type': 'application/json',
          ...(version === undefined || version.length === 0 ? {} : { 'A2A-Version': version }),
        },
        undefined,
        true,
      ),
      body: {
        jsonrpc: '2.0',
        id: `a2a-${String(Date.now())}`,
        method: 'message/stream',
        params,
      },
    }
  }

  /**
   * Probes the admin alert webhook with a batch of alerts.
   *
   * @remarks Backend truth (`AdminAlertWebhookController`): `POST
   * /v1/admin/alerts/webhook` accepts a non-empty JSON array of at most
   * 100 alerts and answers `{ received }`. Counted and logged only —
   * no human delivery. Client-validated before sending (never a 400
   * surprise).
   *
   * @param alerts - Non-empty array of at most 100 alert payloads.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The receipt count.
   */
  async sendAlertProbe(alerts: unknown[], opts?: RequestOptions): Promise<{ received: number }> {
    if (alerts.length === 0) throw new Error('Alert batch must be a non-empty array.')
    if (alerts.length > 100) throw new Error('Alert batch holds at most 100 alerts.')
    const raw: unknown = await this.request<unknown>(
      '/v1/admin/alerts/webhook',
      {
        method: 'POST',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(alerts),
      },
      opts,
    )
    return valueOrThrow(alertProbeReceiptSchema, raw, 'alerts-probe', 'Alert probe')
  }

  /**
   * Reads the caller's personal usage dashboard (owned keys only).
   *
   * @remarks Backend truth (`MeDashboardController`): owner scope derives
   * server-side from the session — there is no user-id parameter by design.
   * Trailing 7d when `from`/`to` are absent; 90d max window.
   *
   * @param from - Window start (ISO-8601), or undefined for the default.
   * @param to - Window end (ISO-8601), or undefined for now.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Summary with freshness coordinates (nulls when absent).
   */
  async myUsage(from?: string, to?: string, opts?: RequestOptions): Promise<DashboardView> {
    const q = new URLSearchParams()
    if (from !== undefined) q.set('from', from)
    if (to !== undefined) q.set('to', to)
    const suffix = q.size === 0 ? '' : `?${q.toString()}`
    const res = await sendGatewayRequest(
      this.base,
      `/v1/me/usage${suffix}`,
      { headers: this.headers() },
      opts,
    )
    return {
      summary: valueOr(ledgerSummarySchema, await res.json(), 'my-usage', EMPTY_LEDGER_SUMMARY),
      generatedAt: res.headers.get('X-Dashboard-Generated-At'),
      watermark: res.headers.get('X-Dashboard-Watermark'),
    }
  }

  /**
   * Reads one account's usage dashboard as an admin (audit-logged).
   *
   * @remarks Backend truth (`AdminLedgerController.getUserSummary`): same
   * shape and freshness headers as the personal view. A stealth 404 means
   * no access or no route — callers render admin-unavailable, never retry.
   *
   * @param userId - Viewed account id.
   * @param from - Window start (ISO-8601), or undefined for the default.
   * @param to - Window end (ISO-8601), or undefined for now.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Summary with freshness coordinates.
   */
  async userUsage(
    userId: string,
    from?: string,
    to?: string,
    opts?: RequestOptions,
  ): Promise<DashboardView> {
    const q = new URLSearchParams()
    if (from !== undefined) q.set('from', from)
    if (to !== undefined) q.set('to', to)
    const suffix = q.size === 0 ? '' : `?${q.toString()}`
    const res = await sendGatewayRequest(
      this.base,
      `/v1/admin/ledger/user/${encodeURIComponent(userId)}/summary${suffix}`,
      { headers: this.headers() },
      opts,
    )
    return {
      summary: valueOr(ledgerSummarySchema, await res.json(), 'user-usage', EMPTY_LEDGER_SUMMARY),
      generatedAt: res.headers.get('X-Dashboard-Generated-At'),
      watermark: res.headers.get('X-Dashboard-Watermark'),
    }
  }

  /**
   * Lists the caller's active team memberships (possibly empty).
   *
   * @remarks Backend truth (`MeTeamController`): ACTIVE only; empty is
   * normal for new SSO users in the holding team.
   *
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Owned memberships.
   */
  async myTeams(opts?: RequestOptions): Promise<TeamMembership[]> {
    const body: unknown = await this.request<unknown>(
      '/v1/me/teams',
      { headers: this.headers() },
      opts,
    )
    return rowsOrEmpty(teamRowSchema, body, 'my-teams')
  }

  /**
   * Lists every team in one org with live active-member counts.
   *
   * @remarks Backend truth (`AdminTeamController`): `org` is required
   * (400 when missing) and unknown slugs answer 404.
   *
   * @param org - Owning org slug.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Org teams for pickers and inventory.
   */
  async orgTeams(org: string, opts?: RequestOptions): Promise<OrgTeam[]> {
    const q = new URLSearchParams({ org })
    const body: unknown = await this.request<unknown>(
      `/v1/admin/teams?${q.toString()}`,
      { headers: this.headers() },
      opts,
    )
    return rowsOrEmpty(orgTeamRowSchema, body, 'org-teams')
  }

  /**
   * Lists local orgs (unpaged; fine under hundreds of rows).
   *
   * @remarks Backend truth: `GET /v1/admin/orgs` answers 200
   * `OrgResponse[]`. Drifted rows drop with a notice, never throw.
   *
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Org rows.
   */
  async listOrgs(opts?: RequestOptions): Promise<OrgResponse[]> {
    const body: unknown = await this.request<unknown>(
      '/v1/admin/orgs',
      { headers: this.headers() },
      opts,
    )
    return rowsOrEmpty(orgResponseSchema, body, 'orgs')
  }

  /**
   * Creates a local org. Slugs normalize server-side (trim + lowercase).
   *
   * @param body - Slug plus display name.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The created org.
   * @throws Error synchronously when the slug or name is malformed (never sent).
   */
  async createOrg(
    body: { slug: string; displayName: string },
    opts?: RequestOptions,
  ): Promise<OrgResponse> {
    const slug = body.slug.trim()
    if (slug.length === 0) throw new Error('Org slug is required.')
    if (slug.length > 64) throw new Error('Org slug must be 64 characters or fewer.')
    if (!/^[a-z0-9-]+$/i.test(slug))
      throw new Error('Org slug must use letters, digits, and hyphens only.')
    const displayName = body.displayName.trim()
    if (displayName.length === 0) throw new Error('Org display name is required.')
    if (displayName.length > 128)
      throw new Error('Org display name must be 128 characters or fewer.')
    const raw: unknown = await this.request<unknown>(
      '/v1/admin/orgs',
      {
        method: 'POST',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ slug, displayName }),
      },
      opts,
    )
    return valueOrThrow(orgResponseSchema, raw, 'orgs-create', 'Org creation')
  }

  /**
   * Renames a local org. The slug is immutable — only the display name
   * travels.
   *
   * @param id - Org id.
   * @param body - New display name.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The updated org.
   * @throws Error synchronously when the id or name is malformed (never sent).
   */
  async renameOrg(
    id: string,
    body: { displayName: string },
    opts?: RequestOptions,
  ): Promise<OrgResponse> {
    if (id.trim().length === 0) throw new Error('Org id is required.')
    const displayName = body.displayName.trim()
    if (displayName.length === 0) throw new Error('Org display name is required.')
    if (displayName.length > 128)
      throw new Error('Org display name must be 128 characters or fewer.')
    const raw: unknown = await this.request<unknown>(
      `/v1/admin/orgs/${encodeURIComponent(id.trim())}`,
      {
        method: 'PATCH',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ displayName }),
      },
      opts,
    )
    return valueOrThrow(orgResponseSchema, raw, 'orgs-rename', 'Org rename')
  }

  /**
   * Deletes a local org. Requires an empty org: teams remaining answer
   * 409 with the problem body surfaced through `ApiError`.
   *
   * @param id - Org id.
   * @param opts - Optional request options (abort signal, headers listener).
   */
  deleteOrg(id: string, opts?: RequestOptions): Promise<void> {
    if (id.trim().length === 0) throw new Error('Org id is required.')
    return this.requestEmpty(
      `/v1/admin/orgs/${encodeURIComponent(id.trim())}`,
      { method: 'DELETE', headers: this.headers() },
      opts,
    )
  }

  /**
   * Creates a local team inside an org. New teams start with zero
   * active members.
   *
   * @param orgId - Owning org id.
   * @param body - Team name.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The created team.
   * @throws Error synchronously when the name is malformed (never sent).
   */
  async createTeam(orgId: string, body: { name: string }, opts?: RequestOptions): Promise<OrgTeam> {
    if (orgId.trim().length === 0) throw new Error('Org id is required.')
    const name = body.name.trim()
    if (name.length === 0) throw new Error('Team name is required.')
    if (name.length > 128) throw new Error('Team name must be 128 characters or fewer.')
    const raw: unknown = await this.request<unknown>(
      `/v1/admin/orgs/${encodeURIComponent(orgId.trim())}/teams`,
      {
        method: 'POST',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ name }),
      },
      opts,
    )
    return valueOrThrow(orgTeamRowSchema, raw, 'teams-create', 'Team creation')
  }

  /**
   * Renames a local team. IdP-mapped teams reject the write with 409
   * (sync owns them) — the problem body surfaces through `ApiError`.
   *
   * @param teamId - Team id.
   * @param body - New display name.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The updated team.
   * @throws Error synchronously when the name is malformed (never sent).
   */
  async renameTeam(
    teamId: string,
    body: { displayName: string },
    opts?: RequestOptions,
  ): Promise<OrgTeam> {
    if (teamId.trim().length === 0) throw new Error('Team id is required.')
    const displayName = body.displayName.trim()
    if (displayName.length === 0) throw new Error('Team display name is required.')
    if (displayName.length > 128)
      throw new Error('Team display name must be 128 characters or fewer.')
    const raw: unknown = await this.request<unknown>(
      `/v1/admin/teams/${encodeURIComponent(teamId.trim())}`,
      {
        method: 'PATCH',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ displayName }),
      },
      opts,
    )
    return valueOrThrow(orgTeamRowSchema, raw, 'teams-rename', 'Team rename')
  }

  /**
   * Deletes a local team. Requires no active members and no pending
   * invites; mapped, holding, referenced, or Unassigned teams answer
   * 409 with the problem body surfaced through `ApiError`.
   *
   * @param teamId - Team id.
   * @param opts - Optional request options (abort signal, headers listener).
   */
  deleteTeam(teamId: string, opts?: RequestOptions): Promise<void> {
    if (teamId.trim().length === 0) throw new Error('Team id is required.')
    return this.requestEmpty(
      `/v1/admin/teams/${encodeURIComponent(teamId.trim())}`,
      { method: 'DELETE', headers: this.headers() },
      opts,
    )
  }

  /**
   * Assigns an account to a team. Idempotent — safe to retry.
   *
   * @param teamId - Team id.
   * @param userId - Account UUID.
   * @param body - Role (`MEMBER` or `LEAD`).
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The membership row.
   * @throws Error synchronously when ids or role are malformed (never sent).
   */
  async assignMember(
    teamId: string,
    userId: string,
    body: { role: string },
    opts?: RequestOptions,
  ): Promise<MemberResponse> {
    if (teamId.trim().length === 0) throw new Error('Team id is required.')
    if (!UUID_RE.test(userId.trim())) throw new Error('Member account must be a valid UUID.')
    if (body.role !== 'MEMBER' && body.role !== 'LEAD')
      throw new Error('Member role must be MEMBER|LEAD.')
    const raw: unknown = await this.request<unknown>(
      `/v1/admin/teams/${encodeURIComponent(teamId.trim())}/members/${encodeURIComponent(userId.trim())}`,
      {
        method: 'PUT',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ role: body.role }),
      },
      opts,
    )
    return valueOrThrow(memberResponseSchema, raw, 'team-members-assign', 'Member assignment')
  }

  /**
   * Revokes an account from a team. Answers the same row with
   * `INACTIVE` status; idempotent — safe to retry.
   *
   * @param teamId - Team id.
   * @param userId - Account UUID.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The revoked membership row.
   * @throws Error synchronously when ids are malformed (never sent).
   */
  async revokeMember(
    teamId: string,
    userId: string,
    opts?: RequestOptions,
  ): Promise<MemberResponse> {
    if (teamId.trim().length === 0) throw new Error('Team id is required.')
    if (!UUID_RE.test(userId.trim())) throw new Error('Member account must be a valid UUID.')
    const raw: unknown = await this.request<unknown>(
      `/v1/admin/teams/${encodeURIComponent(teamId.trim())}/members/${encodeURIComponent(userId.trim())}`,
      { method: 'DELETE', headers: this.headers() },
      opts,
    )
    return valueOrThrow(memberResponseSchema, raw, 'team-members-revoke', 'Member revocation')
  }

  /**
   * Lists user accounts for operator pickers and administration.
   *
   * @remarks Backend truth (`AdminUserController.listUsers`): `GET
   * /v1/admin/users` answers a `PageResponse<UserSummary>` (default
   * size 20, newest first). Rows carry identity and status only —
   * never hashes. Drifted envelopes degrade to an empty page.
   *
   * @param query - Page index and size.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The user rows plus page metadata.
   */
  async listUsers(
    query: { page?: number; size?: number } = {},
    opts?: RequestOptions,
  ): Promise<UserPage> {
    const params = new URLSearchParams()
    if (query.page !== undefined) params.set('page', String(Math.max(0, Math.floor(query.page))))
    if (query.size !== undefined)
      params.set('size', String(Math.min(100, Math.max(1, Math.floor(query.size)))))
    const qs = params.toString()
    const body: unknown = await this.request<unknown>(
      qs.length === 0 ? '/v1/admin/users' : `/v1/admin/users?${qs}`,
      { headers: this.headers() },
      opts,
    )
    if (typeof body !== 'object' || body === null || !('content' in body)) {
      noteDrift('users')
      return { users: [], page: 0, size: 20, totalElements: 0, totalPages: 0, hasNext: false }
    }
    const envelope = body as Record<string, unknown>
    const users = rowsOrEmpty(userSummarySchema, envelope.content, 'users')
    const num = (v: unknown, fallback: number): number =>
      typeof v === 'number' && Number.isFinite(v) ? v : fallback
    const bool = (v: unknown): boolean => v === true
    return {
      users,
      page: num(envelope.page, 0),
      size: num(envelope.size, 20),
      totalElements: num(envelope.totalElements, users.length),
      totalPages: num(envelope.totalPages, 0),
      hasNext: bool(envelope.hasNext),
    }
  }

  /**
   * Disables or re-enables an account. The body is explicit by design:
   * an absent flag answers 400 instead of silently re-enabling.
   * Re-enabling never resurrects tombstoned keys.
   *
   * @param id - Account UUID.
   * @param disabled - True to disable, false to re-enable.
   * @param opts - Optional request options (abort signal, headers listener).
   */
  setUserDisabled(id: string, disabled: boolean, opts?: RequestOptions): Promise<void> {
    return this.requestEmpty(
      `/v1/admin/users/${encodeURIComponent(id)}/disabled`,
      {
        method: 'PUT',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ disabled }),
      },
      opts,
    )
  }

  /**
   * Deletes an account. Terminally revokes every attached key and clears
   * the default key first — the cascade is irreversible.
   *
   * @param id - Account UUID.
   * @param opts - Optional request options (abort signal, headers listener).
   */
  deleteUser(id: string, opts?: RequestOptions): Promise<void> {
    return this.requestEmpty(
      `/v1/admin/users/${encodeURIComponent(id)}`,
      { method: 'DELETE', headers: this.headers() },
      opts,
    )
  }

  /**
   * Mints an invite. The link is always returned, even when mailed.
   * An optional team placement lands the account ACTIVE on redeem;
   * dangling placements redeem as 404 without consuming.
   *
   * @remarks Backend truth (`AdminInviteController`): `POST
   * /v1/admin/invites` answers 201 `{ link, emailed }`; an invalid
   * email answers 400; auth denial is stealth 404, never 401.
   *
   * @param body - Optional email (null = link-only), admin flag, and
   * optional team placement (UUID, null = unplaced).
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The redeem link plus whether mail was sent.
   * @throws Error synchronously when the email or team id is malformed (never sent).
   */
  async createInvite(
    body: { email?: string | null; admin?: boolean; teamId?: string | null },
    opts?: RequestOptions,
  ): Promise<InviteReceipt> {
    if (body.email !== undefined && body.email !== null && !/^\S+@\S+\.\S+$/.test(body.email)) {
      throw new Error('Invite email must be valid.')
    }
    let teamId: string | null = null
    if (body.teamId !== undefined && body.teamId !== null) {
      const trimmed = body.teamId.trim()
      if (trimmed.length > 0) {
        if (!UUID_RE.test(trimmed)) throw new Error('Invite team must be a valid UUID.')
        teamId = trimmed
      }
    }
    const raw: unknown = await this.request<unknown>(
      '/v1/admin/invites',
      {
        method: 'POST',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ email: body.email ?? null, admin: body.admin ?? false, teamId }),
      },
      opts,
    )
    return valueOrThrow(inviteReceiptSchema, raw, 'invites-create', 'Invite creation')
  }

  /**
   * Lists alert subscriptions for one scope. Scope is required:
   * omitting it answers 400, never "list all".
   *
   * @param scope - Alert scope to list.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Subscriptions for the scope.
   * @throws Error synchronously when scope is blank (never sent).
   */
  async listNotifications(
    scope: string,
    opts?: RequestOptions,
  ): Promise<{ notifications: NotificationPreference[] }> {
    if (scope.trim().length === 0) throw new Error('Notification scope is required.')
    const q = new URLSearchParams({ scope: scope.trim() })
    const body: unknown = await this.request<unknown>(
      `/v1/admin/notifications?${q.toString()}`,
      { headers: this.headers() },
      opts,
    )
    if (!Array.isArray(body)) {
      noteDrift('notifications')
      return { notifications: [] }
    }
    return {
      notifications: rowsOrEmpty(notificationRowSchema, body, 'notifications'),
    }
  }

  /**
   * Creates an alert subscription. Secrets travel by reference only:
   * `secretRef` names an environment variable, never a secret value.
   *
   * @remarks Backend truth: channel is email/teams/slack/webhook;
   * `minSeverity` defaults to warning; duplicates answer 409.
   *
   * @param body - Scope, channel, target, plus optional secret ref/severity.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The stored subscription.
   */
  async createNotification(
    body: {
      scope: string
      channel: string
      target: string
      secretRef?: string | null
      minSeverity?: string | null
    },
    opts?: RequestOptions,
  ): Promise<NotificationPreference> {
    if (body.scope.trim().length === 0 || body.scope.length > 160) {
      throw new Error('Notification scope must be 1-160 chars.')
    }
    if (!['email', 'teams', 'slack', 'webhook'].includes(body.channel)) {
      throw new Error('Channel must be email, teams, slack, or webhook.')
    }
    if (body.target.trim().length === 0 || body.target.length > 512) {
      throw new Error('Notification target must be 1-512 chars.')
    }
    if (
      body.secretRef !== undefined &&
      body.secretRef !== null &&
      !/^[A-Z][A-Z0-9_]{0,127}$/.test(body.secretRef)
    ) {
      throw new Error('Secret ref must name an environment variable ([A-Z][A-Z0-9_]*).')
    }
    if (
      body.minSeverity !== undefined &&
      body.minSeverity !== null &&
      body.minSeverity !== 'warning' &&
      body.minSeverity !== 'critical'
    ) {
      throw new Error('Minimum severity must be warning or critical.')
    }
    const raw: unknown = await this.request<unknown>(
      '/v1/admin/notifications',
      {
        method: 'POST',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({
          scope: body.scope,
          channel: body.channel,
          target: body.target,
          secretRef: body.secretRef ?? null,
          minSeverity: body.minSeverity ?? null,
        }),
      },
      opts,
    )
    return valueOrThrow(notificationRowSchema, raw, 'notifications-create', 'Notification creation')
  }

  /**
   * Deletes an alert subscription. Unknown ids are a silent no-op
   * (204), never 404 — safe to retry.
   *
   * @param id - Subscription UUID.
   * @param opts - Optional request options (abort signal, headers listener).
   */
  deleteNotification(id: string, opts?: RequestOptions): Promise<void> {
    return this.requestEmpty(
      `/v1/admin/notifications/${encodeURIComponent(id)}`,
      { method: 'DELETE', headers: this.headers() },
      opts,
    )
  }

  /**
   * Reads the session identity for the current bearer token.
   *
   * @remarks Used to complete SSO fragment logins: the redirect carries
   * only the access token plus the admin flag, so the login name comes
   * from here before the session enters memory.
   *
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The session identity.
   */
  async authMe(opts?: RequestOptions): Promise<SessionIdentity> {
    const body: unknown = await this.request<unknown>(
      '/v1/auth/me',
      { headers: this.headers() },
      opts,
    )
    const parsed = identitySchema.safeParse(body)
    if (parsed.success) return parsed.data
    noteDrift('auth-me')
    throw new Error('Session identity changed shape. Try again.')
  }
}

/**
 * Reads a JSON-RPC error from a response body without throwing.
 *
 * @param text - Raw response text (may be empty or non-JSON).
 * @returns Code plus message, or null when no envelope error is present.
 */
export function rpcEnvelopeError(text: string): { code: number; message: string | null } | null {
  if (text.length === 0) return null
  try {
    const parsed: unknown = JSON.parse(text)
    if (typeof parsed !== 'object' || parsed === null) return null
    const error = (parsed as Record<string, unknown>).error
    if (typeof error !== 'object' || error === null) return null
    const record = error as Record<string, unknown>
    return {
      code: typeof record.code === 'number' ? record.code : -32603,
      message: typeof record.message === 'string' ? record.message : null,
    }
  } catch {
    return null
  }
}

/**
 * Maps a JSON-RPC error code to a human diagnosis. Unknown codes render
 * with their numeric value, never a generic blob.
 *
 * @param code - Numeric RPC error code.
 * @param message - Server message, or null when absent.
 * @returns A user-safe diagnosis naming the contract fault.
 */
export function mcpErrorMessage(code: number, message: string | null): string {
  const detail = message !== null && message.length > 0 ? `: ${message}` : ''
  switch (code) {
    case -32700:
      return `Parse error${detail}. The request was not valid JSON.`
    case -32600:
      return `Invalid request${detail}. Check the envelope shape.`
    case -32601:
      return `Method not found${detail}. The tool or method is unknown.`
    case -32602:
      return `Invalid params${detail}. Check the tool arguments.`
    case -32603:
      return `Tool failed${detail}. Retry, or inspect the tool logs.`
    case -32020:
      return `Header mismatch${detail}. Check protocol headers.`
    case -32021:
      return `Missing capability${detail}. The server lacks a required feature.`
    case -32022:
      return `Unsupported protocol version${detail}. Negotiate a listed version.`
    case -32009:
      return `Version not supported${detail}. Renegotiate the agent version.`
    case -32001:
      return `Unknown SSE session${detail}. Re-establish the stream.`
    default:
      return `Tool error (${String(code)})${detail}.`
  }
}

/**
 * Pending human approval parked by the gateway for a privileged tool.
 */
export interface HitlSuspension {
  /** Opaque resumption token to present on retry (never logged). */
  resumptionToken: string
  /** Approval token id parsed from the approval URL, or null. */
  tokenId: string | null
  /** Approval URL for the admin queue, or null when absent. */
  approvalUrl: string | null
  /** Human message from the suspension payload. */
  message: string
}

/**
 * Detects a HITL suspension inside a `tools/call` result. Suspensions
 * carry `resultType: "input_required"` with a resumption token — they
 * are pending approvals, never successful tool output.
 *
 * @param result - Decoded `result` of the JSON-RPC envelope.
 * @returns Suspension facts, or null for ordinary results.
 */
export function hitlSuspensionOf(result: unknown): HitlSuspension | null {
  if (typeof result !== 'object' || result === null) return null
  const record = result as Record<string, unknown>
  if (record.resultType !== 'input_required') return null
  const token = record.requestState
  if (typeof token !== 'string' || token.length === 0) return null
  const inputRequests = record.inputRequests as Record<string, unknown> | undefined
  const approval = inputRequests?.human_approval as Record<string, unknown> | undefined
  const params = approval?.params as Record<string, unknown> | undefined
  const url = typeof params?.url === 'string' ? params.url : null
  const message =
    typeof params?.message === 'string' && params.message.length > 0
      ? params.message
      : 'Execution of this tool requires administrator approval.'
  const match = url === null ? null : /([0-9a-f]{32})\s*$/.exec(url)
  return {
    resumptionToken: token,
    tokenId: match?.[1] ?? null,
    approvalUrl: url,
    message,
  }
}

/**
 * Decides whether a failed dashboard-family query retries.
 *
 * @remarks Client-side backoff for headerless 429s: only rate-limit
 * rejections retry (once); 400/401/404 surface immediately so
 * narrow-the-window, session, and stealth states reach the UI instead of
 * spinning behind the user's back.
 *
 * @param failureCount - Consecutive failures so far (starts at 0).
 * @param error - Thrown value.
 * @returns True to retry with {@link dashboardRetryDelay}.
 */
export function dashboardRetry(failureCount: number, error: unknown): boolean {
  return error instanceof ApiError && error.status === 429 && failureCount < 1
}

/**
 * Computes the dashboard retry delay with an exponential cap.
 *
 * @param attempt - Retry attempt index (starts at 0).
 * @returns Milliseconds to wait (1s, 2s, 4s … capped at 8s).
 */
export function dashboardRetryDelay(attempt: number): number {
  return Math.min(1000 * 2 ** attempt, 8000)
}

/**
 * Reads the configured SSO providers from public client config.
 *
 * @remarks Non-secret allow-list (`VITE_SSO_PROVIDERS`, comma-separated
 * Spring registration ids such as `google,github`). Empty when SSO is not
 * configured — the UI greys SSO out instead of offering a dead button.
 *
 * @returns Configured registration ids, trimmed and non-empty.
 */
export function resolveSsoProviders(): string[] {
  const raw: unknown = import.meta.env.VITE_SSO_PROVIDERS
  if (typeof raw !== 'string') return []
  return raw
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
}

/**
 * Builds the SSO entry URL for one provider.
 *
 * @remarks Backend truth (`SecurityConfig`): `GET
 * /oauth2/authorization/{registrationId}` starts the Authorization Code +
 * PKCE dance; success lands on `/?sso=1#access_token=…&admin=…`.
 *
 * @param registrationId - Spring registration id (for example `google`).
 * @returns Entry URL against the API base.
 */
export function ssoAuthorizationUrl(registrationId: string): string {
  return `${resolveApiBase()}/oauth2/authorization/${encodeURIComponent(registrationId)}`
}
