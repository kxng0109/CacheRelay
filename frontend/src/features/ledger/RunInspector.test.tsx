import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { LedgerPage } from './page.js'
import { derivedThroughput } from './RunInspector.js'

describe('derivedThroughput', () => {
  it('divides tokens by wall seconds without measuring anything', () => {
    expect(derivedThroughput(1540, 92)).toBe('16.7K tok/s')
    expect(derivedThroughput(59, 2000)).toBe('30 tok/s')
  })

  it('reads degenerate durations as unknown, never infinity', () => {
    expect(derivedThroughput(100, 0)).toBeNull()
    expect(derivedThroughput(100, -5)).toBeNull()
  })
})

function summary() {
  return http.get('*/v1/admin/ledger/summary', () =>
    HttpResponse.json({
      totalRequests: 1,
      totalPromptTokens: 10,
      totalCompletionTokens: 5,
      totalTokens: 15,
      totalCostUsdMicros: 12,
      totalCostUsd: '0.000012',
      averageDurationMs: 3,
      byOwner: [],
      byModel: [],
      byProvider: [],
    }),
  )
}

function entries() {
  return http.get('*/v1/admin/ledger/entries', () =>
    HttpResponse.json({
      content: [
        {
          requestId: 'r9',
          model: 'gpt-4o-mini',
          costUsdMicros: 12,
          createdAt: '2026-09-21T00:00:00Z',
        },
        {
          requestId: 'r10',
          model: 'gpt-4o-mini',
          costUsdMicros: 0,
          createdAt: '2026-09-21T00:01:00Z',
        },
      ],
      page: 0,
      size: 25,
      totalElements: 2,
      totalPages: 1,
      hasNext: false,
    }),
  )
}

function receipt() {
  return http.get('*/v1/admin/ledger/entries/:id', () =>
    HttpResponse.json({
      requestId: 'r9',
      ownerId: 'tenant-corp',
      provider: 'openai',
      model: 'gpt-4o-mini',
      promptTokens: 8,
      completionTokens: 4,
      totalTokens: 12,
      costUsdMicros: 12,
      durationMs: 41,
      cached: true,
      cacheTier: 'L0-Memory',
      createdAt: '2026-09-21T00:00:00Z',
    }),
  )
}

describe('RunInspector', () => {
  it('hydrates the twelve-field receipt on selection', async () => {
    const user = userEvent.setup()
    server.use(summary(), entries(), receipt())
    renderApp(<LedgerPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect receipt r9/i }))
    const inspector = await screen.findByRole('dialog')
    expect(within(inspector).getByText('tenant-corp')).toBeInTheDocument()
    expect(within(inspector).getAllByText('openai').length).toBeGreaterThanOrEqual(2)
    await waitFor(() => {
      expect(within(inspector).getByText(/41ms/)).toBeInTheDocument()
    })
  })

  it('closes on Escape', async () => {
    const user = userEvent.setup()
    server.use(summary(), entries(), receipt())
    renderApp(<LedgerPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect receipt r9/i }))
    await screen.findByRole('dialog')
    await user.keyboard('{Escape}')
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(screen.getByText(/select a row to inspect/i)).toBeInTheDocument()
  })

  it('closes from the inspector close button', async () => {
    const user = userEvent.setup()
    server.use(summary(), entries(), receipt())
    renderApp(<LedgerPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect receipt r9/i }))
    await screen.findByRole('dialog')
    const inspector = screen.getByRole('dialog')
    await user.click(within(inspector).getByRole('button', { name: /close inspector/i }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(screen.getByText(/select a row to inspect/i)).toBeInTheDocument()
  })

  it('dismisses from the backdrop without touching the table', async () => {
    const user = userEvent.setup()
    server.use(summary(), entries(), receipt())
    renderApp(<LedgerPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect receipt r9/i }))
    await screen.findByRole('dialog')
    await user.click(screen.getByRole('button', { name: /dismiss inspector/i }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(screen.getByText(/select a row to inspect/i)).toBeInTheDocument()
  })

  it('states the selection when it leaves the visible page', async () => {
    const user = userEvent.setup()
    server.use(summary(), entries(), receipt())
    renderApp(<LedgerPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect receipt r9/i }))
    await screen.findByRole('dialog')
    await user.type(screen.getByLabelText(/filter audit log/i), 'zzz-no-match')
    await waitFor(() => {
      expect(screen.getByText(/left the visible page/i)).toBeInTheDocument()
    })
  })

  it('walks rows with prev and next', async () => {
    const user = userEvent.setup()
    server.use(summary(), entries(), receipt())
    renderApp(<LedgerPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect receipt r9/i }))
    await user.click(screen.getByRole('button', { name: /next receipt/i }))
    expect(screen.getByRole('dialog')).toHaveTextContent('r10')
    await user.click(screen.getByRole('button', { name: /previous receipt/i }))
    expect(screen.getByRole('dialog')).toHaveTextContent('r9')
    await user.click(screen.getByRole('button', { name: /previous receipt/i }))
    expect(screen.getByRole('dialog')).toHaveTextContent('r10')
  })

  it('reads a gone receipt without crashing', async () => {
    const user = userEvent.setup()
    server.use(
      summary(),
      entries(),
      http.get('*/v1/admin/ledger/entries/:id', () => new HttpResponse(null, { status: 404 })),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect receipt r9/i }))
    await waitFor(() => {
      expect(screen.getByText(/receipt unavailable/i)).toBeInTheDocument()
    })
  })

  it('reads a free cached receipt with tier and date facts', async () => {
    const user = userEvent.setup()
    server.use(
      summary(),
      http.get('*/v1/admin/ledger/entries', () =>
        HttpResponse.json({
          content: [
            {
              requestId: 'r-free',
              model: 'qwen',
              costUsdMicros: 0,
              createdAt: '2026-09-21T00:01:00Z',
            },
          ],
          page: 0,
          size: 25,
          totalElements: 1,
          totalPages: 1,
          hasNext: false,
        }),
      ),
      http.get('*/v1/admin/ledger/entries/:id', () =>
        HttpResponse.json({
          requestId: 'r-free',
          ownerId: 'tenant-corp',
          provider: 'local',
          model: 'qwen',
          promptTokens: null,
          completionTokens: null,
          totalTokens: 9,
          costUsdMicros: 0,
          durationMs: 12,
          cached: true,
          cacheTier: 'L0-Memory',
          createdAt: '2026-09-21T00:01:00Z',
        }),
      ),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect receipt r-free/i }))
    const inspector = await screen.findByRole('dialog')
    expect(within(inspector).getByText('free')).toBeInTheDocument()
    expect(within(inspector).getAllByText(/l0-memory/i).length).toBeGreaterThan(0)
  })

  it('reads an unparseable receipt date verbatim', async () => {
    const user = userEvent.setup()
    server.use(
      summary(),
      http.get('*/v1/admin/ledger/entries', () =>
        HttpResponse.json({
          content: [
            {
              requestId: 'r-odd',
              model: 'qwen',
              costUsdMicros: 0,
              createdAt: 'not-a-date',
            },
          ],
          page: 0,
          size: 25,
          totalElements: 1,
          totalPages: 1,
          hasNext: false,
        }),
      ),
      http.get('*/v1/admin/ledger/entries/:id', () =>
        HttpResponse.json({
          requestId: 'r-odd',
          ownerId: 'tenant-corp',
          provider: 'local',
          model: 'qwen',
          promptTokens: 1,
          completionTokens: 2,
          totalTokens: 3,
          costUsdMicros: 0,
          durationMs: 5,
          cached: false,
          cacheTier: null,
          createdAt: 'not-a-date',
        }),
      ),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect receipt r-odd/i }))
    const inspector = await screen.findByRole('dialog')
    expect(within(inspector).getByText('not-a-date')).toBeInTheDocument()
  })

  it('reads an uncached receipt without a tier', async () => {
    const user = userEvent.setup()
    server.use(
      summary(),
      http.get('*/v1/admin/ledger/entries', () =>
        HttpResponse.json({
          content: [
            {
              requestId: 'r-live',
              model: 'gpt-4o-mini',
              costUsdMicros: 50,
              createdAt: '2026-09-21T00:02:00Z',
            },
          ],
          page: 0,
          size: 25,
          totalElements: 1,
          totalPages: 1,
          hasNext: false,
        }),
      ),
      http.get('*/v1/admin/ledger/entries/:id', () =>
        HttpResponse.json({
          requestId: 'r-live',
          ownerId: 'tenant-corp',
          provider: 'openai',
          model: 'gpt-4o-mini',
          promptTokens: 20,
          completionTokens: 10,
          totalTokens: 30,
          costUsdMicros: 50,
          durationMs: 200,
          cached: false,
          cacheTier: 'L0-Memory',
          createdAt: '2026-09-21T00:02:00Z',
        }),
      ),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect receipt r-live/i }))
    const inspector = await screen.findByRole('dialog')
    expect(within(inspector).getByText('50µ$')).toBeInTheDocument()
    expect(within(inspector).getByText('no')).toBeInTheDocument()
  })

  it('opens the raw JSON transcript on demand', async () => {
    const user = userEvent.setup()
    server.use(summary(), entries(), receipt())
    renderApp(<LedgerPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect receipt r9/i }))
    const inspector = await screen.findByRole('dialog')
    await user.click(within(inspector).getByText('Raw JSON'))
    expect(within(inspector).getAllByText(/tenant-corp/).length).toBeGreaterThan(1)
  })

  it('renders tierless cached badges without a separator', async () => {
    const user = userEvent.setup()
    server.use(
      summary(),
      entries(),
      http.get('*/v1/admin/ledger/entries/:id', () =>
        HttpResponse.json({
          requestId: 'r9',
          ownerId: 'tenant-corp',
          provider: 'openai',
          model: 'gpt-4o-mini',
          promptTokens: 8,
          completionTokens: 4,
          totalTokens: 12,
          costUsdMicros: 12,
          durationMs: 41,
          cached: true,
          cacheTier: null,
          createdAt: '2026-09-21T00:00:00Z',
        }),
      ),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect receipt r9/i }))
    const inspector = await screen.findByRole('dialog')
    await waitFor(() => {
      const badges = within(inspector).getAllByText('Cached', { exact: true })
      const badge = badges.find((el) => el.tagName === 'SPAN')
      expect(badge).toBeDefined()
      if (badge !== undefined) {
        expect(badge).not.toHaveTextContent('·')
      }
    })
  })

  it('degrades null receipt bodies to an empty inspector instead of crashing', async () => {
    const user = userEvent.setup()
    server.use(
      summary(),
      entries(),
      http.get('*/v1/admin/ledger/entries/:id', () => HttpResponse.json(null)),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect receipt r9/i }))
    await screen.findByRole('dialog')
    await waitFor(() => {
      expect(screen.queryByLabelText(/receipt overview/i)).not.toBeInTheDocument()
    })
  })
})
