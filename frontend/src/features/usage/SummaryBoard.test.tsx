import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderApp } from '../../test/utils.js'
import type { DashboardView } from '../../shared/api/types.js'
import { SummaryBoard, formatRelativeTime } from './SummaryBoard.js'

function viewOf(overrides: Partial<DashboardView['summary']> = {}): DashboardView {
  return {
    summary: {
      totalRequests: 7,
      totalPromptTokens: 700,
      totalCompletionTokens: 300,
      totalTokens: 1000,
      totalCostUsdMicros: 1500,
      totalCostUsd: '0.001500',
      averageDurationMs: 42.5,
      byOwner: [],
      byModel: [
        {
          provider: 'openai',
          model: 'gpt-56-luna',
          totalRequests: 7,
          totalPromptTokens: 700,
          totalCompletionTokens: 300,
          totalTokens: 1000,
          totalCostUsdMicros: 1500,
          totalCostUsd: '0.001500',
          averageDurationMs: 42.5,
        },
      ],
      byProvider: [
        {
          provider: 'openai',
          totalRequests: 7,
          totalPromptTokens: 700,
          totalCompletionTokens: 300,
          totalTokens: 1000,
          totalCostUsdMicros: 1500,
          totalCostUsd: '0.001500',
          averageDurationMs: 42.5,
        },
      ],
      ...overrides,
    },
    generatedAt: '2026-09-24T10:00:00Z',
    watermark: '2026-09-24T09:59:00Z',
  }
}

describe('formatRelativeTime', () => {
  it('renders seconds, minutes, hours, and days', () => {
    const now = new Date('2026-09-24T10:01:30Z').getTime()
    expect(formatRelativeTime('2026-09-24T10:01:00Z', now)).toBe('30s ago')
    expect(formatRelativeTime('2026-09-24T09:50:00Z', now)).toBe('11m ago')
    expect(formatRelativeTime('2026-09-24T05:00:00Z', now)).toBe('5h ago')
    expect(formatRelativeTime('2026-09-20T10:00:00Z', now)).toBe('4d ago')
  })

  it('nulls null and malformed instants', () => {
    expect(formatRelativeTime(null)).toBeNull()
    expect(formatRelativeTime('not-a-date')).toBeNull()
  })
})

describe('SummaryBoard', () => {
  it('renders freshness, tiles, and breakdowns with tabular figures', () => {
    renderApp(<SummaryBoard view={viewOf()} />)
    expect(screen.getByRole('status')).toHaveTextContent(/updated .* ago/i)
    expect(screen.getAllByText('7').length).toBeGreaterThan(0)
    expect(screen.getByText('openai/gpt-56-luna')).toBeInTheDocument()
    expect(screen.getAllByText('openai').length).toBeGreaterThan(0)
  })

  it('renders the empty trio instead of an error on empty windows', () => {
    renderApp(<SummaryBoard view={viewOf({ totalRequests: 0, averageDurationMs: 0 })} />)
    expect(screen.getByText(/no usage in range/i)).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('omits the freshness line without headers', () => {
    renderApp(<SummaryBoard view={{ ...viewOf(), generatedAt: null, watermark: null }} />)
    expect(screen.queryByText(/updated/i)).not.toBeInTheDocument()
  })

  it('falls back to recent for malformed instants', () => {
    renderApp(<SummaryBoard view={{ ...viewOf(), generatedAt: 'not-a-date' }} />)
    expect(screen.getByRole('status')).toHaveTextContent(/updated recently/i)
  })
})
