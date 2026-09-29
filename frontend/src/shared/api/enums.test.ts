import { describe, expect, it } from 'vitest'
import {
  BUDGET_HOLD_STATES,
  CACHE_SCOPES,
  CACHE_STATUSES,
  CIRCUIT_STATES,
  FAILOVER_STRATEGIES,
  MCP_PROTOCOL_VERSIONS,
  MCP_TRANSPORTS,
  MODEL_QUALITY_TIERS,
  PROVIDER_TYPES,
} from './enums.js'

describe('backend-verified enums', () => {
  it('pins the provider, strategy, cache, and quality vocabularies', () => {
    expect(PROVIDER_TYPES).toContain('VERTEX_AI')
    expect(PROVIDER_TYPES).toContain('OLLAMA')
    expect(FAILOVER_STRATEGIES).toEqual(['SEQUENTIAL', 'RACE'])
    expect(CACHE_SCOPES).toContain('GLOBAL')
    expect(CACHE_STATUSES).toContain('BYPASS')
    expect(MODEL_QUALITY_TIERS).toEqual(['FRONTIER', 'STANDARD', 'BUDGET'])
    expect(CIRCUIT_STATES).toEqual(['CLOSED', 'OPEN', 'HALF_OPEN'])
  })

  it('pins the hold lifecycle to HOLD-live (never ACTIVE)', () => {
    expect(BUDGET_HOLD_STATES).toContain('HOLD')
    expect(BUDGET_HOLD_STATES).not.toContain('ACTIVE')
  })

  it('pins MCP transports and versions newest-first', () => {
    expect(MCP_TRANSPORTS).toContain('STDIO')
    expect(MCP_PROTOCOL_VERSIONS[0]).toBe('2026-07-28')
  })
})
