import type {
  ApiKeyCreated,
  ApiKeyRecord,
  BudgetRecord,
  CacheStats,
  ChatCompletionRequest,
  ChatCompletionResponse,
  CircuitSnapshot,
  EmbeddingRequest,
  EmbeddingResponse,
  HitlApproval,
  LedgerLogEntry,
  LedgerSummary,
  PageResponse,
  RateLimitDimension,
  RateLimitSnapshot,
} from './types.js'

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
 * Sends one gateway request with network-error and status guards.
 *
 * @remarks
 * Single choke point for the transport: aborts propagate untouched so
 * callers can distinguish cancellation from failure, unreachable networks
 * map to an actionable message with the original error as `cause`, and
 * non-2xx responses throw {@link ApiError} carrying the request id,
 * rate-limit snapshot, cache status, and debug id. Bodies are read as text
 * (never blind `res.json()`) so empty or non-JSON errors stay safe.
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
 * Minimal typed gateway client over `fetch`.
 *
 * @remarks
 * Auth is phase-agnostic: callers pass either a `gw-` virtual key (public
 * surface) or the master admin key (admin surface) as a Bearer token. Tokens
 * live in memory only (zustand store) and are never written to storage by
 * this client. Admin callers may alternatively pass `adminKey` to send the
 * `X-Admin-Key` header form.
 */
export class GatewayClient {
  private readonly base: string
  private readonly token: string
  private readonly adminKey: string | null

  constructor(args: { base?: string; token: string; adminKey?: string | null }) {
    this.base = args.base ?? resolveApiBase()
    this.token = args.token
    this.adminKey = args.adminKey ?? null
  }

  private headers(extra?: Record<string, string>): Record<string, string> {
    const h: Record<string, string> = {
      Accept: 'application/json',
      ...(extra ?? {}),
    }
    if (this.adminKey !== null) {
      h['X-Admin-Key'] = this.adminKey
    } else {
      h.Authorization = `Bearer ${this.token}`
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
   * @param body - Chat request (stream is forced to false here; use the SSE client for streams).
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The completion payload.
   */
  chat(body: ChatCompletionRequest, opts?: RequestOptions): Promise<ChatCompletionResponse> {
    return this.request<ChatCompletionResponse>(
      '/v1/chat/completions',
      {
        method: 'POST',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ ...body, stream: false }),
      },
      opts,
    )
  }

  /**
   * Creates embeddings for the given input.
   *
   * @param body - Embedding request.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Embedding vectors with index positions preserved.
   */
  embeddings(body: EmbeddingRequest, opts?: RequestOptions): Promise<EmbeddingResponse> {
    return this.request<EmbeddingResponse>(
      '/v1/embeddings',
      {
        method: 'POST',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(body),
      },
      opts,
    )
  }

  /**
   * Lists public models.
   *
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Model identifiers the gateway accepts.
   */
  models(opts?: RequestOptions): Promise<{ data: { id: string }[] }> {
    return this.request<{ data: { id: string }[] }>('/v1/models', { headers: this.headers() }, opts)
  }

  /**
   * Reads aggregated circuit state for every known provider.
   *
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns One snapshot per provider.
   */
  circuitState(opts?: RequestOptions): Promise<{ circuits: CircuitSnapshot[] }> {
    return this.request<{ circuits: CircuitSnapshot[] }>(
      '/v1/admin/circuits/state',
      { headers: this.headers() },
      opts,
    )
  }

  /**
   * Force-resets a provider circuit (observed-state passthrough).
   *
   * @param provider - Provider name (for example `openai`).
   * @param opts - Optional request options (abort signal, headers listener).
   */
  resetCircuit(
    provider: string,
    opts?: RequestOptions,
  ): Promise<{ provider: string; state: string }> {
    return this.request<{ provider: string; state: string }>(
      '/v1/admin/circuits/reset',
      {
        method: 'POST',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ provider }),
      },
      opts,
    )
  }

  /**
   * Lists virtual API keys (metadata only, never plaintext).
   *
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Key metadata records.
   */
  listKeys(opts?: RequestOptions): Promise<{ keys: ApiKeyRecord[] }> {
    return this.request<{ keys: ApiKeyRecord[] }>(
      '/v1/admin/keys',
      { headers: this.headers() },
      opts,
    )
  }

  /**
   * Creates a virtual key. Plaintext is exposed exactly once.
   *
   * @param body - Key parameters.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Metadata plus the single-exposure plaintext.
   */
  createKey(
    body: { name: string; rpmLimit: number; dailyQuota: number; models: string[] },
    opts?: RequestOptions,
  ): Promise<ApiKeyCreated> {
    return this.request<ApiKeyCreated>(
      '/v1/admin/keys',
      {
        method: 'POST',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(body),
      },
      opts,
    )
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
  ledgerSummary(opts?: RequestOptions): Promise<LedgerSummary> {
    return this.request<LedgerSummary>(
      '/v1/admin/ledger/summary',
      { headers: this.headers() },
      opts,
    )
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
  ledgerLogs(
    page: number,
    size: number,
    opts?: RequestOptions,
  ): Promise<PageResponse<LedgerLogEntry>> {
    const q = new URLSearchParams({ page: String(page), size: String(size) })
    return this.request<PageResponse<LedgerLogEntry>>(
      `/v1/admin/ledger/entries?${q.toString()}`,
      { headers: this.headers() },
      opts,
    )
  }

  /**
   * Reads cache statistics.
   *
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns L0/L1/L2 counters.
   */
  cacheStats(opts?: RequestOptions): Promise<CacheStats> {
    return this.request<CacheStats>('/v1/admin/cache/stats', { headers: this.headers() }, opts)
  }

  /**
   * Purges cache entries, optionally scoped to one model.
   *
   * @param model - Optional model scope.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Purge outcome.
   */
  purgeCache(model?: string, opts?: RequestOptions): Promise<{ purged: boolean }> {
    const q = model === undefined ? '' : `?${new URLSearchParams({ model }).toString()}`
    return this.request<{ purged: boolean }>(
      `/v1/admin/cache/purge${q}`,
      { method: 'POST', headers: this.headers() },
      opts,
    )
  }

  /**
   * Lists budgets.
   *
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Budget records.
   */
  listBudgets(opts?: RequestOptions): Promise<{ budgets: BudgetRecord[] }> {
    return this.request<{ budgets: BudgetRecord[] }>(
      '/v1/admin/budgets',
      { headers: this.headers() },
      opts,
    )
  }

  /**
   * Creates a budget.
   *
   * @param body - Budget parameters.
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns The created record.
   */
  createBudget(
    body: { name: string; limitMicros: number },
    opts?: RequestOptions,
  ): Promise<BudgetRecord> {
    return this.request<BudgetRecord>(
      '/v1/admin/budgets',
      {
        method: 'POST',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(body),
      },
      opts,
    )
  }

  /**
   * Lists pending HITL approvals.
   *
   * @param opts - Optional request options (abort signal, headers listener).
   * @returns Pending approval queue.
   */
  hitlPending(opts?: RequestOptions): Promise<{ approvals: HitlApproval[] }> {
    return this.request<{ approvals: HitlApproval[] }>(
      '/v1/admin/mcp/approvals/pending',
      { headers: this.headers() },
      opts,
    )
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
}
