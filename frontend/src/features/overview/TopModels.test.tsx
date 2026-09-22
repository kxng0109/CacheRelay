import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderApp } from '../../test/utils.js'
import type { LedgerSummary } from '../../shared/api/types.js'
import { TopModels } from './TopModels.js'

function summary(models: LedgerSummary['byModel']): LedgerSummary {
  return {
    totalRequests: 10,
    totalPromptTokens: 100,
    totalCompletionTokens: 50,
    totalTokens: 150,
    totalCostUsdMicros: 100,
    totalCostUsd: '0.000100',
    averageDurationMs: 5,
    byOwner: [],
    byModel: models,
    byProvider: [],
  }
}

function model(name: string, totalRequests: number, overrides: Record<string, unknown> = {}) {
  return {
    provider: 'openai',
    model: name,
    totalRequests,
    totalPromptTokens: 1,
    totalCompletionTokens: 1,
    totalTokens: 2,
    totalCostUsdMicros: 10,
    totalCostUsd: '0.000010',
    averageDurationMs: 1,
    ...overrides,
  }
}

describe('TopModels', () => {
  it('ranks by requests and caps at five with formatted cells', () => {
    renderApp(
      <TopModels
        summary={summary([
          model('b', 2),
          model('a', 6691, { totalCostUsdMicros: 0 }),
          model('c', 3),
          model('d', 4),
          model('e', 5),
          model('f', 6),
        ])}
      />,
      { adminSession: true },
    )
    const section = screen.getByRole('region', { name: /top models/i })
    const items = section.querySelectorAll('li')
    expect(items.length).toBe(5)
    expect(items[0]?.textContent).toContain('a')
    expect(items[0]?.textContent).toContain('6.7K')
    expect(items[0]?.textContent).toContain('$0.00')
  })

  it('renders nothing without model rows', () => {
    renderApp(<TopModels summary={summary([])} />, { adminSession: true })
    expect(screen.queryByRole('region', { name: /top models/i })).not.toBeInTheDocument()
  })
})
