import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { OverviewPage } from './page.js'

const { mockInit } = vi.hoisted(() => {
  const mockChart = {
    setOption: vi.fn(),
    setTheme: vi.fn(),
    resize: vi.fn(),
    dispose: vi.fn(),
  }
  return { mockChart, mockInit: vi.fn(() => mockChart) }
})

vi.mock('../../shared/echarts/setup.js', () => ({
  echarts: { init: mockInit },
}))

beforeEach(() => {
  vi.clearAllMocks()
  // Every overview render mounts the pulse strip and the latency chart;
  // both scrape prometheus. Default-stub it so no test leaks an
  // unhandled request. Tests may override with richer bodies.
  server.use(
    http.get(
      '*/actuator/prometheus',
      () => new HttpResponse('', { headers: { 'Content-Type': 'text/plain' } }),
    ),
    http.get('*/v1/admin/ledger/entries', () =>
      HttpResponse.json({
        content: [],
        page: 0,
        size: 5,
        totalElements: 0,
        totalPages: 0,
        hasNext: false,
      }),
    ),
    http.get('*/v1/admin/cache/stats', () =>
      HttpResponse.json({
        enabled: true,
        defaultScope: 'TENANT',
        similarityThreshold: 0.92,
        embeddingModel: 'test-embed',
        l0MaxBytes: 1048576,
        l0InMemoryTtlSeconds: 300,
        l1RedisEnabled: false,
        l2SemanticEnabled: true,
        polarityGuardEnabled: true,
        entityGuardEnabled: false,
      }),
    ),
  )
})

function fullSummary(overrides: Record<string, unknown> = {}) {
  return HttpResponse.json({
    totalRequests: 42,
    totalPromptTokens: 1000,
    totalCompletionTokens: 500,
    totalTokens: 1500,
    totalCostUsdMicros: 9000,
    totalCostUsd: '0.009000',
    averageDurationMs: 12.5,
    byOwner: [],
    byModel: [
      {
        provider: 'openai',
        model: 'gpt-56-luna',
        totalRequests: 30,
        totalPromptTokens: 800,
        totalCompletionTokens: 400,
        totalTokens: 1200,
        totalCostUsdMicros: 7000,
        totalCostUsd: '0.007000',
        averageDurationMs: 11.0,
      },
      {
        provider: 'anthropic',
        model: 'claude-x',
        totalRequests: 12,
        totalPromptTokens: 200,
        totalCompletionTokens: 100,
        totalTokens: 300,
        totalCostUsdMicros: 2000,
        totalCostUsd: '0.002000',
        averageDurationMs: 14.0,
      },
    ],
    byProvider: [
      {
        provider: 'openai',
        totalRequests: 30,
        totalPromptTokens: 800,
        totalCompletionTokens: 400,
        totalTokens: 1200,
        totalCostUsdMicros: 7000,
        totalCostUsd: '0.007000',
        averageDurationMs: 11.0,
      },
    ],
    ...overrides,
  })
}

describe('OverviewPage', () => {
  it('renders tiles, chart fallback, and section links', async () => {
    server.use(
      http.get('*/v1/admin/ledger/summary', () => fullSummary()),
      http.get(
        '*/actuator/prometheus',
        () => new HttpResponse('', { headers: { 'Content-Type': 'text/plain' } }),
      ),
    )
    renderApp(<OverviewPage />, { adminSession: true })
    expect(screen.getByRole('heading', { name: /overview/i })).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByText('42')).toBeInTheDocument()
    })
    expect(screen.getByText('$0.009')).toBeInTheDocument()
    expect(screen.getByText('1.5K')).toBeInTheDocument()
    expect(screen.getAllByText('gpt-56-luna').length).toBeGreaterThanOrEqual(2)
    expect(screen.getByText(/across 2 models/i)).toBeInTheDocument()
    // Live strip, stat strip, and top models each link out: one Explore ledger each.
    const ledgerLinks = screen.getAllByRole('link', { name: /explore ledger/i })
    expect(ledgerLinks).toHaveLength(3)
    for (const link of ledgerLinks) {
      expect(link).toHaveAttribute('href', '/ledger')
    }
    for (const label of ['Playground', 'Circuits', 'Ledger', 'Observability']) {
      expect(screen.getByRole('link', { name: new RegExp(label) })).toBeInTheDocument()
    }
  })

  it('shows no spend totals and no hint without an admin session', () => {
    renderApp(<OverviewPage />)
    expect(screen.queryByText(/unlock the admin key/i)).not.toBeInTheDocument()
    expect(screen.queryByText('42')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /overview/i })).toBeInTheDocument()
    expect(screen.queryByLabelText(/live gateway activity/i)).not.toBeInTheDocument()
  })

  it('hides tiles for non-admin sessions without a hint', () => {
    renderApp(<OverviewPage />, { nonAdminSession: true })
    expect(screen.queryByText(/unlock the admin key/i)).not.toBeInTheDocument()
    expect(screen.queryByText('42')).not.toBeInTheDocument()
    // Operator pulse and latency stay admin-only.
    expect(screen.queryByLabelText(/gateway pulse/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/request latency/i)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/live gateway activity/i)).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /overview/i })).toBeInTheDocument()
  })

  it('reports summary failures as alerts', async () => {
    server.use(
      http.get('*/v1/admin/ledger/summary', () => new HttpResponse('x', { status: 500 })),
      http.get(
        '*/actuator/prometheus',
        () => new HttpResponse('', { headers: { 'Content-Type': 'text/plain' } }),
      ),
    )
    renderApp(<OverviewPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/HTTP 500/)
    })
  })

  it('shows a waiting live-RPS cell before two scrapes exist', async () => {
    server.use(
      http.get('*/v1/admin/ledger/summary', () =>
        fullSummary({
          totalRequests: 7,
          totalCostUsdMicros: 100,
          totalCostUsd: '0.000100',
          averageDurationMs: 3.2,
          totalTokens: 70,
          byModel: [],
          byProvider: [],
        }),
      ),
      http.get(
        '*/actuator/prometheus',
        () => new HttpResponse('', { headers: { 'Content-Type': 'text/plain' } }),
      ),
    )
    renderApp(<OverviewPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText('7')).toBeInTheDocument()
    })
    expect(screen.getByText(/live rps/i)).toBeInTheDocument()
    expect(screen.getByText(/top model/i)).toBeInTheDocument()
    expect(screen.getByText('none')).toBeInTheDocument()
    const liveRpsCell = screen.getByText(/live rps/i).closest('div')
    expect(liveRpsCell).not.toBeNull()
    if (liveRpsCell !== null) {
      expect(within(liveRpsCell).getByText('n/a')).toBeInTheDocument()
    }
    expect(screen.getByText(/across 0 models/i)).toBeInTheDocument()
  })

  it('announces a live RPS reading once scrapes arrive', async () => {
    const user = userEvent.setup()
    let calls = 0
    server.use(
      http.get('*/v1/admin/ledger/summary', () => {
        calls += 1
        return fullSummary()
      }),
      http.get(
        '*/actuator/prometheus',
        () => new HttpResponse('', { headers: { 'Content-Type': 'text/plain' } }),
      ),
    )
    renderApp(<OverviewPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText('42')).toBeInTheDocument()
    })
    expect(screen.getByText(/waiting for live rate/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /retry totals/i }))
    await waitFor(() => {
      expect(calls).toBeGreaterThan(1)
    })
  })
})
