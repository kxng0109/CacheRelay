/**
 * Backend-verified enum constants. Mirrors `findings/29-frontend-spec.md`
 * §4 as confirmed against `backend/src/main/java`: unknown future values
 * render via explicit Unknown fallbacks, never mislabeled.
 */

/** Upstream provider dialects the gateway can route to. */
export const PROVIDER_TYPES = [
  'OPENAI',
  'ANTHROPIC',
  'GEMINI',
  'VERTEX_AI',
  'DEEPSEEK',
  'OLLAMA',
] as const

/** Model routing strategies for alias chains. */
export const FAILOVER_STRATEGIES = ['SEQUENTIAL', 'RACE'] as const

/** Cache sharing scopes. `GLOBAL` requires the server-side operator flag. */
export const CACHE_SCOPES = ['TENANT', 'USER', 'GLOBAL'] as const

/** Cache lookup outcomes, including bypassed responses. */
export const CACHE_STATUSES = ['HIT_L0', 'HIT_L1', 'HIT_L2', 'MISS', 'BYPASS'] as const

/** Model quality tiers for the catalog. */
export const MODEL_QUALITY_TIERS = ['FRONTIER', 'STANDARD', 'BUDGET'] as const

/** Circuit breaker states. */
export const CIRCUIT_STATES = ['CLOSED', 'OPEN', 'HALF_OPEN'] as const

/** Budget hold lifecycle states. Live holds read `HOLD`, never `ACTIVE`. */
export const BUDGET_HOLD_STATES = ['HOLD', 'SETTLED', 'ABORTED', 'EXPIRED'] as const

/** MCP upstream transports. */
export const MCP_TRANSPORTS = ['STREAMABLE_HTTP', 'HTTP_SSE', 'STDIO'] as const

/** MCP protocol versions, newest first. */
export const MCP_PROTOCOL_VERSIONS = ['2026-07-28', '2025-11-25', '2024-11-05'] as const

/** Budget levels. */
export const BUDGET_LEVELS = ['KEY', 'TEAM', 'ORG'] as const
