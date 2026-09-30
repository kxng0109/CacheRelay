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
  max_completion_tokens?: number
  top_p?: number
  stop?: unknown
  stream?: boolean
  stream_options?: unknown
  tools?: unknown
  tool_choice?: unknown
  parallel_tool_calls?: boolean
  response_format?: unknown
  reasoning_effort?: string
  thinking?: unknown
  frequency_penalty?: number
  presence_penalty?: number
  seed?: number
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
  dimensions?: number
  encoding_format?: string
  user?: string
}

export interface EmbeddingData {
  embedding: number[]
  index: number
}

export interface EmbeddingUsage {
  prompt_tokens: number
  total_tokens: number
}

export interface EmbeddingResponse {
  data: EmbeddingData[]
  model: string
  usage?: EmbeddingUsage
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

/**
 * Spend-vs-cap for one budget subject. Absent caps read as zero —
 * indistinguishable from a zero-spend capped subject by design.
 */
export interface BudgetBalance {
  level: string
  subject: string
  minuteLimitMicros: number
  minuteSpentMicros: number
  monthLimitMicros: number
  monthSpentMicros: number
}

/**
 * One budget hold. Live holds read `HOLD`, never `ACTIVE` (the docs-only
 * synonym); settled writes are SETTLED, ABORTED, or EXPIRED.
 */
export interface BudgetHold {
  requestId: string
  subject: string
  heldMicros: number
  settledMicros: number | null
  state: string
}

/**
 * One cache tier probe. Every metric except reachability is nullable:
 * dead tiers degrade to absent metrics, never errors.
 */
export interface TierStats {
  reachable: boolean
  usedBytes: number | null
  maxBytes: number | null
  usedPercent: number | null
  maxmemoryPolicy: string | null
  evictedKeysTotal: number | null
  keyspaceHits: number | null
  keyspaceMisses: number | null
}

/**
 * Tier telemetry: memoised probe with generation time plus both tiers.
 */
export interface CacheTiers {
  generatedAt: string
  accounting: TierStats
  cache: TierStats
}

/**
 * Cache purge receipt. `evictedKeys` counts Redis SCAN-deletes only
 * (L0 and vector docs are uncounted); `evictedScope` is ALL, a tenant
 * id, or INVALID on glob-rejected purges.
 */
export interface CachePurge {
  success: boolean
  message: string
  evictedScope: string
  evictedKeys: number
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

/**
 * One owned key for act-as-self flows. Mirrors the backend `KeyResponse`
 * record narrowly: only fields the picker reads. Plaintext never crosses.
 */
export interface OwnedKey {
  /** 64-character SHA-256 hex digest identifying the key. */
  keyId: string
  /** Human-readable label. */
  name: string
  /** Permitted model aliases (empty means all allowed). */
  allowedModels: string[]
  /** Whether the key is enabled. */
  enabled: boolean
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
 * @remarks Mirrors the twelve-field server receipt (`LedgerEntryResponse`).
 * `costUsd` is the exact decimal string, displayed verbatim.
 */
export interface LedgerLogEntry {
  id: string
  requestId: string
  ownerId: string
  provider: string
  model: string
  promptTokens: number
  completionTokens: number
  totalTokens: number
  costUsdMicros: number
  /** Exact decimal string (`"0.000005"`); displayed verbatim, never divided. */
  costUsd: string
  durationMs: number
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

/**
 * One pending gated tool invocation. Identity is the path token id —
 * never a separate approval id. Mirrors the backend
 * `PendingApprovalSummary` record field for field.
 */
export interface HitlApproval {
  tokenId: string
  toolName: string
  serverName: string
  ownerId: string
  keyName: string
  createdAt: string
  expiresAt: string
}

/**
 * Decision receipt: the server echoes status, token, and message.
 * Toasts render this receipt, never a fabricated outcome.
 */
export interface HitlDecision {
  status: string
  tokenId: string
  message: string
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
 * Local team management uses the same shape: `TeamResponse` is the
 * backend name, `OrgTeam` the frontend alias. Kept as one interface so
 * the two can never drift apart.
 */
export type TeamResponse = OrgTeam

/**
 * One local org. Mirrors the backend `OrgResponse` record
 * (`id`, `slug`, `displayName`); slugs normalize server-side
 * (trim + lowercase, a-z0-9-, up to 64 chars).
 */
export interface OrgResponse {
  id: string
  slug: string
  displayName: string
}

/**
 * One team membership. Mirrors the backend `MemberResponse` record.
 * Assign is idempotent (safe to retry); revoke answers the same row
 * with `INACTIVE` status, also idempotent.
 */
export interface MemberResponse {
  userId: string
  teamId: string
  role: string
  status: string
}

/**
 * One admin model-catalog entry with pricing, windows, quality, and dims.
 * Mirrors the backend `ModelCatalogEntryResponse` record field for field;
 * five cost/window/quality/dims fields are nullable.
 */
export interface ModelCatalogEntry {
  modelId: string
  provider: string
  mode: string
  inputCostPerToken: number
  outputCostPerToken: number
  cacheReadInputTokenCost: number | null
  cacheCreationInputTokenCost: number | null
  maxInputTokens: number | null
  maxOutputTokens: number | null
  qualityTier: string | null
  benchmarkRefs: string | null
  embeddingDimensions: number | null
}

/**
 * One user account summary. Mirrors the backend `UserSummaryResponse`
 * record: identity and status only, never hashes.
 */
export interface UserSummary {
  userId: string
  username: string
  admin: boolean
  disabled: boolean
  createdAt: string
}

/**
 * One page of user summaries plus page metadata. Mirrors the backend
 * `PageResponse<UserSummary>` envelope; `users` renames `content`.
 */
export interface UserPage {
  users: UserSummary[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  hasNext: boolean
}

/**
 * One model quality rating. Mirrors the backend `QualityResponse` record.
 */
export interface ModelQuality {
  modelId: string
  tier: string
  benchmarkRefs: string | null
  updatedAt: string
}

/**
 * One alert subscription. Mirrors the backend `NotificationResponse`:
 * `secretRef` names an environment variable, never a secret value.
 */
export interface NotificationPreference {
  id: string
  scope: string
  channel: string
  target: string
  secretRef: string | null
  minSeverity: string
  createdAt: string
}

/**
 * Invite creation receipt: always a link, plus whether mail was sent.
 */
export interface InviteReceipt {
  link: string
  emailed: boolean
}

/**
 * One A2A agent card with rewritten URLs. Mirrors the gateway card:
 * unknown fields are ignored, never cast blindly.
 */
export interface A2aAgentCard {
  protocolVersion: string
  name: string
  url: string
  description: string | null
  version: string | null
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
