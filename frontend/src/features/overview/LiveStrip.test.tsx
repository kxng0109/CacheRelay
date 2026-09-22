import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { LiveStrip } from './LiveStrip.js'
import type { LedgerSummary } from '../../shared/api/types.js'

const summary: LedgerSummary = {
  totalRequests: 42,
  totalPromptTokens: 1000,
  totalCompletionTokens: 500,
  totalTokens: 1500,
  totalCostUsdMicros: 9000,
  totalCostUsd: '0.009000',
  averageDurationMs: 12.5,
  byOwner: [],
  byModel: [],
  byProvider: [],
}

function entriesPage(
  content: { requestId: string; model: string; costUsdMicros: number; createdAt: string }[],
) {
  return HttpResponse.json({
    content,
    page: 0,
    size: 5,
    totalElements: content.length,
    totalPages: 1,
    hasNext: false,
  })
}

function cacheStats(overrides: Record<string, unknown> = {}) {
  return HttpResponse.json({
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
    ...overrides,
  })
}

describe('LiveStrip', () => {
  it('renders the ticking eyebrow, latest rows, and cache config', async () => {
    server.use(
      http.get('*/v1/admin/ledger/entries', () =>
        entriesPage([
          { requestId: 'req-1', model: 'gpt-56-luna', costUsdMicros: 120, createdAt: '2026-09-21' },
          { requestId: 'req-2', model: 'claude-x', costUsdMicros: 80, createdAt: '2026-09-21' },
        ]),
      ),
      http.get('*/v1/admin/cache/stats', () => cacheStats()),
    )
    renderApp(<LiveStrip summary={summary} liveRps={3.2} />, { adminSession: true })
    expect(screen.getByText(/live · 42 requests · 3\.2 rps/)).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByText('req-1')).toBeInTheDocument()
    })
    expect(screen.getByText('gpt-56-luna')).toBeInTheDocument()
    expect(screen.getByText('120')).toBeInTheDocument()
    expect(screen.getByText('on · TENANT')).toBeInTheDocument()
    expect(screen.getByText('1048576 B · TTL 300s')).toBeInTheDocument()
    expect(screen.getByText(/l1 off · l2 on · guards on\/off/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /explore ledger/i })).toHaveAttribute('href', '/ledger')
    expect(screen.getByRole('link', { name: /explore cache/i })).toHaveAttribute('href', '/cache')
  })

  it('waits for the live rate instead of printing a zero', () => {
    server.use(
      http.get('*/v1/admin/ledger/entries', () => entriesPage([])),
      http.get('*/v1/admin/cache/stats', () => cacheStats()),
    )
    renderApp(<LiveStrip summary={summary} liveRps={null} />, { adminSession: true })
    expect(screen.getByText(/waiting for live rate/)).toBeInTheDocument()
  })

  it('shows the empty-as-prompt trio when no requests exist yet', async () => {
    server.use(
      http.get('*/v1/admin/ledger/entries', () => entriesPage([])),
      http.get('*/v1/admin/cache/stats', () => cacheStats()),
    )
    renderApp(<LiveStrip summary={summary} liveRps={null} />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText('No requests yet')).toBeInTheDocument()
    })
    expect(screen.getByText(/newest receipts land here/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /open playground/i })).toHaveAttribute(
      'href',
      '/playground',
    )
  })

  it('reports tail failures as alerts with a retry', async () => {
    const user = userEvent.setup()
    let calls = 0
    server.use(
      http.get('*/v1/admin/ledger/entries', () => {
        calls += 1
        return calls === 1 ? new HttpResponse('x', { status: 500 }) : entriesPage([])
      }),
      http.get('*/v1/admin/cache/stats', () => cacheStats()),
    )
    renderApp(<LiveStrip summary={summary} liveRps={null} />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText(/latest requests unavailable/i)).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: /retry/i }))
    await waitFor(() => {
      expect(screen.getByText('No requests yet')).toBeInTheDocument()
    })
  })

  it('reports cache failures as alerts with a retry', async () => {
    const user = userEvent.setup()
    let calls = 0
    server.use(
      http.get('*/v1/admin/ledger/entries', () => entriesPage([])),
      http.get('*/v1/admin/cache/stats', () => {
        calls += 1
        return calls === 1 ? new HttpResponse('x', { status: 503 }) : cacheStats()
      }),
    )
    renderApp(<LiveStrip summary={summary} liveRps={null} />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText(/cache config unavailable/i)).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: /retry/i }))
    await waitFor(() => {
      expect(screen.getByText('on · TENANT')).toBeInTheDocument()
    })
  })

  it('renders nothing for the cache panel on an empty 204', async () => {
    server.use(
      http.get('*/v1/admin/ledger/entries', () => entriesPage([])),
      http.get('*/v1/admin/cache/stats', () => new HttpResponse(null, { status: 204 })),
    )
    renderApp(<LiveStrip summary={summary} liveRps={null} />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText('No requests yet')).toBeInTheDocument()
    })
    expect(screen.queryByText('State')).not.toBeInTheDocument()
  })
})
