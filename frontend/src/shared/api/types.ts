/**
 * Backend DTOs mirroring `backend/docs/BACKEND_API_REFERENCE.md`.
 *
 * @remarks
 * Shapes are intentionally narrow: only fields the SPA reads or writes.
 * Unknown backend fields are ignored at runtime (never cast blindly).
 */

export interface ChatMessage {
  role: 'system' | 'user' | 'assistant'
  content: string
}

export interface ChatCompletionRequest {
  model: string
  messages: ChatMessage[]
  temperature?: number
  max_tokens?: number
  stream?: boolean
}

export interface ChatCompletionChoice {
  message: ChatMessage
}

export interface ChatCompletionUsage {
  prompt_tokens: number
  completion_tokens: number
  total_tokens: number
}

export interface ChatCompletionResponse {
  choices: ChatCompletionChoice[]
  model: string
}

export interface EmbeddingRequest {
  model: string
  input: string | string[]
}

export interface EmbeddingData {
  embedding: number[]
  index: number
}

export interface EmbeddingResponse {
  data: EmbeddingData[]
  model: string
}

export interface ProblemDetail {
  type: string
  title: string
  status: number
  detail: string
  instance: string
}

export interface GatewayErrorBody {
  error: {
    message: string
    type: string
    code: string | null
  }
}

/**
 * Rate-limit dimension the gateway meters independently.
 *
 * @remarks
 * Mirrors the backend `X-RateLimit-*-RPM` / `X-RateLimit-*-TPM` header
 * families and the 429 `error.code` values (`RPM_EXCEEDED`, `TPM_EXCEEDED`).
 */
export type RateLimitDimension = 'RPM' | 'TPM'

export interface RateLimitSnapshot {
  /** Binding dimension displayed, or null when no capped dimension observed. */
  dimension: RateLimitDimension | null
  limit: number | null
  remaining: number | null
  reset: number | null
  retryAfter: number | null
}

/**
 * Cache configuration flags.
 *
 * @remarks Mirrors `CacheStatsResponse`: the endpoint reports tier
 * configuration, not live counters. Unknowns render as em dashes.
 */
export interface CacheStats {
  enabled: boolean
  defaultScope: string
  similarityThreshold: number
  embeddingModel: string
  l0MaxBytes: number
  l0InMemoryTtlSeconds: number
  l1RedisEnabled: boolean
  l2SemanticEnabled: boolean
  polarityGuardEnabled: boolean
  entityGuardEnabled: boolean
}

export interface CircuitSnapshot {
  provider: string
  state: 'CLOSED' | 'OPEN' | 'HALF_OPEN'
  /** Consecutive failures counted by the breaker. */
  failures: number
  /** Milliseconds left on the open-state cooldown (live countdown source). */
  cooldownMsRemaining: number
  /** Whether a half-open trial request is currently in flight. */
  halfOpenProbe: boolean
}

export interface ApiKeyRecord {
  keyId: string
  keyPrefix: string
  ownerId: string
  name: string
  rpmLimit: number
  tpmLimit: number
  allowedModels: string[]
  allowedProviders: string[]
  enabled: boolean
  createdAt: string
  /** Owning account id; null for legacy rows predating user linkage. */
  ownerUserId: string | null
  /** Owning account login name; null when unresolvable. */
  ownerUsername: string | null
}

export interface ApiKeyCreated {
  keyId: string
  /** Single-exposure plaintext. Never persisted, never re-fetched. */
  key: string
  keyPrefix: string
  ownerId: string
  name: string
}

/** Upstream provider validation depth. Unknown strings degrade to grey. */
export type ProviderValidationStatus =
  'CONTRACT_CHECKED' | 'AUTH_REACHABLE' | 'LIVE_VERIFIED' | 'UNVERIFIED'

/**
 * One configured upstream provider with live routing health.
 *
 * @remarks Mirrors `ProviderStatusResponse`. `baseUrl` is null when unset;
 * the key value is never exposed, only the boolean.
 */
export interface ProviderStatus {
  name: string
  type: string
  baseUrl: string | null
  keyConfigured: boolean
  connectTimeoutSeconds: number
  requestTimeoutSeconds: number
  embeddingSingleAsString: boolean
  circuitState: string
  aliasReferences: number
  validationStatus: string
}

export interface BudgetRecord {
  id: string
  level: string
  subjectId: string
  minuteMicros: number
  monthMicros: number
  webhookUrl: string | null
  createdAt: string
  updatedAt: string
}

/**
 * Usage and cost aggregated for one tenant owner. Mirrors the backend
 * `OwnerUsageSummary` record field for field; the frontend never invents
 * envelope fields.
 */
export interface OwnerUsageSummary {
  ownerId: string
  totalRequests: number
  totalPromptTokens: number
  totalCompletionTokens: number
  totalTokens: number
  totalCostUsdMicros: number
  /** Exact decimal string (`"1.500000"`); displayed verbatim, never divided. */
  totalCostUsd: string
  averageDurationMs: number
}

/**
 * Usage and cost aggregated for one provider and model. Mirrors the
 * backend `ModelUsageSummary` record.
 */
export interface ModelUsageSummary {
  provider: string
  model: string
  totalRequests: number
  totalPromptTokens: number
  totalCompletionTokens: number
  totalTokens: number
  totalCostUsdMicros: number
  /** Exact decimal string; displayed verbatim, never divided. */
  totalCostUsd: string
  averageDurationMs: number
}

/**
 * Usage and cost aggregated for one upstream provider. Mirrors the
 * backend `ProviderUsageSummary` record.
 */
export interface ProviderUsageSummary {
  provider: string
  totalRequests: number
  totalPromptTokens: number
  totalCompletionTokens: number
  totalTokens: number
  totalCostUsdMicros: number
  /** Exact decimal string; displayed verbatim, never divided. */
  totalCostUsd: string
  averageDurationMs: number
}

/**
 * One provider step inside a model alias chain. Mirrors the backend
 * `ProviderRef` record: provider name plus an optional per step model
 * override (null sends the requested model as is).
 */
export interface ProviderChainStep {
  providerName: string
  modelOverride: string | null
}

/**
 * One effective model alias with its origin. Mirrors the backend
 * `ModelDefinitionResponse`: `source` is `file` for configuration-bound
 * aliases (read-only) or `database` for admin-managed ones.
 */
export interface ModelAliasRecord {
  name: string
  chain: ProviderChainStep[]
  strategy: string
  source: string
}

export interface LedgerSummary {
  totalRequests: number
  totalPromptTokens: number
  totalCompletionTokens: number
  totalTokens: number
  totalCostUsdMicros: number
  /** Exact decimal string (`"1.500000"`); displayed verbatim, never divided. */
  totalCostUsd: string
  averageDurationMs: number
  byOwner: OwnerUsageSummary[]
  byModel: ModelUsageSummary[]
  byProvider: ProviderUsageSummary[]
}

/**
 * One billed request in the audit log.
 *
 * @remarks Narrow server subset: the list view reads four identity fields.
 * The inspector hydrates the full twelve-field receipt on demand.
 */
export interface LedgerLogEntry {
  requestId: string
  model: string
  costUsdMicros: number
  createdAt: string
}

/**
 * Full receipt for one billed request.
 *
 * @remarks Mirrors the twelve-field server receipt. Optional fields stay
 * `null`-able: older rows predate token accounting.
 */
export interface LedgerReceipt {
  requestId: string
  ownerId: string
  provider: string
  model: string
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number
  costUsdMicros: number
  durationMs: number
  cached: boolean
  cacheTier: string | null
  createdAt: string
}

/**
 * Backend paginated envelope (`PageResponse`).
 *
 * @remarks Mirrors the gateway shape: `content` rows plus page metadata.
 * The frontend never invents envelope fields.
 */
export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  hasNext: boolean
}

export interface HitlApproval {
  approvalId: string
  toolName: string
  requestedAt: string
  requestedBy: string
}

export interface McpSuspended {
  /** Backend defect: `/v1/mcp/**` answers 403 until fixed. */
  suspended: true
  status: number
}

export interface McpToolAnnotations {
  readOnlyHint?: boolean
  destructiveHint?: boolean
  idempotentHint?: boolean
  openWorldHint?: boolean
}

export interface McpTool {
  name: string
  description: string | null
  inputSchema: unknown
  annotations: McpToolAnnotations | null
}

/**
 * One team membership of the session account. Mirrors the backend
 * `TeamMembershipResponse` record field for field.
 */
export interface TeamMembership {
  teamId: string
  teamName: string
  orgSlug: string
  role: string
  status: string
}

/**
 * One SSO-provisioned team in an org with its live active-member count.
 * Mirrors the backend `TeamResponse` record.
 */
export interface OrgTeam {
  teamId: string
  orgSlug: string
  name: string
  idpGroupId: string
  activeMembers: number
}

/**
 * Session identity from `GET /v1/auth/me`.
 */
export interface SessionIdentity {
  userId: string
  username: string
  admin: boolean
}

/**
 * Dashboard payload: the summary plus freshness coordinates from the
 * `X-Dashboard-Generated-At` / `X-Dashboard-Watermark` response headers.
 */
export interface DashboardView {
  summary: LedgerSummary
  generatedAt: string | null
  watermark: string | null
}
