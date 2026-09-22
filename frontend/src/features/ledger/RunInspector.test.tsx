import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { LedgerPage } from './page.js'

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
    const cell = within(table).getAllByText('gpt-4o-mini')[0]
    expect(cell).toBeDefined()
    if (cell !== undefined) {
      await user.click(cell)
    }
    const inspector = await screen.findByRole('complementary')
    expect(within(inspector).getByText('tenant-corp')).toBeInTheDocument()
    expect(within(inspector).getByText('openai')).toBeInTheDocument()
    await waitFor(() => {
      expect(within(inspector).getByText(/41ms/)).toBeInTheDocument()
    })
  })

  it('closes on Escape', async () => {
    const user = userEvent.setup()
    server.use(summary(), entries(), receipt())
    renderApp(<LedgerPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    const row = within(table).getAllByText('gpt-4o-mini')[0]?.closest('tr')
    expect(row).not.toBeNull()
    if (row !== null && row !== undefined) {
      await user.click(row)
    }
    await screen.findByRole('complementary')
    await user.keyboard('{Escape}')
    expect(screen.getByRole('complementary')).toHaveTextContent(/select a row to inspect/i)
  })

  it('closes from the inspector close button', async () => {
    const user = userEvent.setup()
    server.use(summary(), entries(), receipt())
    renderApp(<LedgerPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    const cell = within(table).getAllByText('gpt-4o-mini')[0]
    expect(cell).toBeDefined()
    if (cell !== undefined) {
      await user.click(cell)
    }
    await screen.findByRole('complementary')
    await user.click(screen.getByRole('button', { name: /close inspector/i }))
    expect(screen.getByRole('complementary')).toHaveTextContent(/select a row to inspect/i)
  })

  it('walks rows with prev and next', async () => {
    const user = userEvent.setup()
    server.use(summary(), entries(), receipt())
    renderApp(<LedgerPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    const first = within(table).getAllByText('gpt-4o-mini')[0]
    expect(first).toBeDefined()
    if (first !== undefined) {
      await user.click(first)
    }
    await user.click(screen.getByRole('button', { name: /next receipt/i }))
    expect(screen.getByRole('complementary')).toHaveTextContent('r10')
    await user.click(screen.getByRole('button', { name: /previous receipt/i }))
    expect(screen.getByRole('complementary')).toHaveTextContent('r9')
    await user.click(screen.getByRole('button', { name: /previous receipt/i }))
    expect(screen.getByRole('complementary')).toHaveTextContent('r10')
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
    const gone = within(table).getAllByText('gpt-4o-mini')[0]
    expect(gone).toBeDefined()
    if (gone !== undefined) {
      await user.click(gone)
    }
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
    await user.click(within(table).getByText('qwen'))
    const inspector = await screen.findByRole('complementary')
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
    await user.click(within(table).getByText('qwen'))
    const inspector = await screen.findByRole('complementary')
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
    await user.click(within(table).getByText('gpt-4o-mini'))
    const inspector = await screen.findByRole('complementary')
    expect(within(inspector).getByText('50µ$')).toBeInTheDocument()
    expect(within(inspector).getByText('no')).toBeInTheDocument()
  })

  it('opens the raw JSON transcript on demand', async () => {
    const user = userEvent.setup()
    server.use(summary(), entries(), receipt())
    renderApp(<LedgerPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    const cell = within(table).getAllByText('gpt-4o-mini')[0]
    expect(cell).toBeDefined()
    if (cell !== undefined) {
      await user.click(cell)
    }
    const inspector = await screen.findByRole('complementary')
    await user.click(within(inspector).getByText('Raw JSON'))
    expect(within(inspector).getAllByText(/tenant-corp/).length).toBeGreaterThan(1)
  })
})
