import type {
  ApiKeyCreated,
  ApiKeyRecord,
  BudgetRecord,
  CacheStats,
  ChatCompletionRequest,
  ChatCompletionResponse,
  CircuitSnapshot,
  DashboardView,
  EmbeddingRequest,
  EmbeddingResponse,
  HitlApproval,
  LedgerLogEntry,
  LedgerReceipt,
  LedgerSummary,
  McpSuspended,
  McpTool,
  McpToolAnnotations,
  ModelAliasRecord,
  OrgTeam,
  OwnedKey,
  ProviderStatus,
  PageResponse,
  ProviderChainStep,
  RateLimitDimension,
  RateLimitSnapshot,
  SessionIdentity,
  TeamMembership,
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
  readonly cacheStatus: string | null
  readonly debugId: string | null
  /** Gateway `error.code` (for example `RPM_EXCEEDED`), or null when absent. */
  readonly code: string | null

  constructor(args: {
    message: string
    status: number
    requestId: string | null
    rateLimit: RateLimitSnapshot
    cacheStatus: string | null
    debugId: string | null
    code: string | null
  }) {
    super(args.message)
    this.name = 'ApiError'
    this.status = args.status
    this.requestId = args.requestId
    this.rateLimit = args.rateLimit
    this.cacheStatus = args.cacheStatus
    this.debugId = args.debugId
    this.code = args.code
  }
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
  const num = (v: string | null): number | null => {
    if (v === null || v === 'unlimited') return null
    const n = Number(v)
    return Number.isFinite(n) ? n : null
  }
  return {
    limit: num(headers.get(`X-RateLimit-Limit-${dimension}`)),
    remaining: num(headers.get(`X-RateLimit-Remaining-${dimension}`)),
    reset: num(headers.get(`X-RateLimit-Reset-${dimension}`)),
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
  const num = (v: string | null): number | null => {
    if (v === null || v === 'unlimited') return null
    const n = Number(v)
    return Number.isFinite(n) ? n : null
  }
  const rpm = parseDimension(headers, 'RPM')
  const tpm = parseDimension(headers, 'TPM')
  const dimension = selectPrimaryDimension(rpm, tpm, code)
  const primary = dimension === 'TPM' ? tpm : rpm
  return {
    dimension,
    limit: primary.limit,
    remaining: primary.remaining,
    reset: primary.reset,
    retryAfter: num(headers.get('Retry-After')),
  }
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
 * Reads a generic gateway error message without leaking internals.
 *
 * @param status - HTTP status code.
 * @param body - Raw response text (may be empty or non-JSON).
 * @returns A user-safe message describing what happened and what to do next.
 */
export function safeErrorMessage(status: number, body: string): string {
  if (body.length > 0) {
    try {
      const parsed: unknown = JSON.parse(body)
      if (typeof parsed === 'object' && parsed !== null) {
        const record = parsed as Record<string, unknown>
        const detail = record.detail
        if (typeof detail === 'string' && detail.length > 0) {
          if (status === 403)
            return 'Request refused by gateway policy. Check the route and key scope, then retry.'
          return detail
        }
        const err = record.error
        if (typeof err === 'object' && err !== null) {
          const message = (err as Record<string, unknown>).message
          if (typeof message === 'string' && message.length > 0) {
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
    case 429:
      return 'Rate limit reached. Wait for the reset window, then retry.'
    case 503:
      return 'Gateway is temporarily unavailable. Retry shortly.'
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
 * Session-owned paths: 401s here mean the access token died (expiry or
 * revocation), never a bad gateway key. Public paths keep their errors
 * untouched so gateway-key problems are never misread as session loss.
 *
 * @param path - Gateway path starting with `/`.
 * @returns True for session-owned routes (excluding the login exchange).
 */
function isSessionRoute(path: string): boolean {
  if (path.startsWith('/v1/admin/')) return true
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
    throw new ApiError({
      message: safeErrorMessage(res.status, text),
      status: res.status,
      requestId: res.headers.get('X-Request-Id'),
      rateLimit: parseRateLimit(res.headers, code),
      cacheStatus: res.headers.get('X-Cache-Status'),
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
  requestId: z.string(),
  model: z.string(),
  costUsdMicros: z.number(),
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
  requestId: z.string(),
  ownerId: z.string(),
  provider: z.string(),
  model: z.string(),
  promptTokens: z.number().nullable(),
  completionTokens: z.number().nullable(),
  totalTokens: z.number(),
  costUsdMicros: z.number(),
  durationMs: z.number(),
  cached: z.boolean(),
  cacheTier: z.string().nullable(),
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
  approvalId: z.string(),
  toolName: z.string(),
  requestedAt: z.string(),
  requestedBy: z.string(),
})

const hitlEnvelopeSchema = z.object({ approvals: z.array(z.unknown()) })

const modelIdRowSchema = z.object({ id: z.string() })

const modelEnvelopeSchema = z.object({ data: z.array(z.unknown()) })

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

const resetCircuitSchema = z.object({
  provider: z.string(),
  state: z.string(),
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
  evictedScope: z.string(),
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
          { 'Content-Type': 'application/json' },
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
          { 'Content-Type': 'application/json' },
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
    return { models: Array.isArray(body.models) ? body.models : [] }
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
   * Force-resets a provider circuit. Live-verified against the gateway.
   *
   * @remarks The reset receipt is validated before the toast reads it
   * (DEF-09).
   *
   * @param provider - Provider name (for example `openai`).
   * @param opts - Optional request options (abort signal, headers listener).
   */
  async resetCircuit(
    provider: string,
    opts?: RequestOptions,
  ): Promise<{ provider: string; state: string }> {
    const raw: unknown = await this.request<unknown>(
      `/v1/admin/circuits/${encodeURIComponent(provider)}/reset`,
      {
        method: 'POST',
        headers: this.headers(),
      },
      opts,
    )
    return valueOrThrow(resetCircuitSchema, raw, 'circuits-reset', 'Circuit reset')
  }

  /**
   * Lists virtual API keys (metadata only, never plaintext).
   *
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Key metadata records.
   */
  async listKeys(opts?: RequestOptions): Promise<{ keys: ApiKeyRecord[] }> {
    const body: unknown = await this.request<unknown>(
      '/v1/admin/keys',
      {
        headers: this.headers(),
      },
      opts,
    )
    return { keys: rowsOrEmpty(apiKeyRowSchema, body, 'keys') }
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
    },
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
   * never `/logs`, never a bare `{ entries }` array.
   *
   * @param page - Zero-based page index.
   * @param size - Page size (backend clamps to its maximum).
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The page envelope with entry rows.
   */
  async ledgerLogs(
    page: number,
    size: number,
    opts?: RequestOptions,
  ): Promise<PageResponse<LedgerLogEntry>> {
    const q = new URLSearchParams({ page: String(page), size: String(size) })
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
   * Purges cache entries, optionally scoped to one owner.
   *
   * @remarks Backend truth (live-verified): `DELETE /v1/admin/cache`
   * with optional `ownerId` scope; the response names the evicted scope.
   *
   * @param ownerId - Optional tenant scope; omitted purges globally.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Purge outcome with the evicted scope.
   */
  async purgeCache(
    ownerId?: string,
    opts?: RequestOptions,
  ): Promise<{ success: boolean; evictedScope: string }> {
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
   * Lists pending HITL approvals.
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
    // The live gateway answers a bare array; the contract promises an
    // envelope. Both validate row by row, never crash.
    const envelope = Array.isArray(body)
      ? { approvals: body }
      : valueOr(hitlEnvelopeSchema, body, 'hitl-pending', { approvals: [] })
    return { approvals: rowsOrEmpty(hitlRowSchema, envelope.approvals, 'hitl-pending') }
  }

  /**
   * Decides a HITL approval.
   *
   * @param approvalId - Approval identifier.
   * @param approved - True to approve, false to reject.
   * @param decidedBy - Operator identity for the audit trail.
   * @param opts - Optional request options (abort signal, headers listener).
   */
  decideHitl(
    approvalId: string,
    approved: boolean,
    decidedBy: string,
    opts?: RequestOptions,
  ): Promise<void> {
    const action = approved ? 'approve' : 'reject'
    return this.requestEmpty(
      `/v1/admin/mcp/approvals/${encodeURIComponent(approvalId)}/${action}`,
      {
        method: 'POST',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ approvalId, decidedBy }),
      },
      opts,
    )
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
        { 'Content-Type': 'application/json' },
        opts?.actAsKey,
        opts?.ignoreSession,
      ),
      body: JSON.stringify({ jsonrpc: '2.0', id: 'tools-list', method: 'tools/list' }),
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
    if (!res.ok) {
      const text = await res.text().catch(() => '')
      throw new ApiError({
        message: safeErrorMessage(res.status, text),
        status: res.status,
        requestId: res.headers.get('X-Request-Id'),
        rateLimit: parseRateLimit(res.headers),
        cacheStatus: res.headers.get('X-Cache-Status'),
        debugId: res.headers.get('X-Request-Debug'),
        code: parseGatewayErrorCode(text),
      })
    }
    const body: unknown = await res.json().catch(() => null)
    ;(opts?.onHeaders ?? headersReporter)?.(res.headers, null)
    if (typeof body !== 'object' || body === null) return { tools: [] }
    const envelope = body as Record<string, unknown>
    if (typeof envelope.error === 'object' && envelope.error !== null) {
      const message = (envelope.error as Record<string, unknown>).message
      throw new Error(
        typeof message === 'string' && message.length > 0
          ? `MCP catalog error: ${message}`
          : 'MCP catalog error.',
      )
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
 * Decides whether a failed dashboard-family query retries.
 *
 * @remarks Client-side backoff for headerless 429s: only rate-limit
 * rejections retry (at most twice); 400/401/404 surface immediately so
 * narrow-the-window, session, and stealth states reach the UI instead of
 * spinning behind the user's back.
 *
 * @param failureCount - Consecutive failures so far (starts at 0).
 * @param error - Thrown value.
 * @returns True to retry with {@link dashboardRetryDelay}.
 */
export function dashboardRetry(failureCount: number, error: unknown): boolean {
  return error instanceof ApiError && error.status === 429 && failureCount < 2
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
