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

export interface CacheStats {
  l0Size: number
  l0Capacity: number
  redisConfigured: boolean
  exactEntries: number
  semanticVectors: number
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
}

export interface ApiKeyCreated {
  keyId: string
  /** Single-exposure plaintext. Never persisted, never re-fetched. */
  key: string
  keyPrefix: string
  ownerId: string
  name: string
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

export interface LedgerSummary {
  totalRequests: number
  totalCostUsdMicros: number
  averageDurationMs: number
}

export interface LedgerLogEntry {
  requestId: string
  model: string
  costUsdMicros: number
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
