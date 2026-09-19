import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { LedgerPage } from './page.js'

function summary(body: Record<string, number>) {
  return http.get('*/v1/admin/ledger/summary', () => HttpResponse.json(body))
}

function entry(requestId: string) {
  return {
    requestId,
    model: 'gpt-4o-mini',
    costUsdMicros: 12,
    createdAt: '2026-09-17T00:00:00Z',
  }
}

function pageOf(requestIds: string[], hasNext: boolean, page = 0) {
  return {
    content: requestIds.map((id) => entry(id)),
    page,
    size: 25,
    totalElements: requestIds.length,
    totalPages: hasNext ? 2 : 1,
    hasNext,
  }
}

describe('LedgerPage', () => {
  it('mounts the board without a session (router guards access)', () => {
    renderApp(<LedgerPage />)
    expect(screen.getByText(/loading summary/i)).toBeInTheDocument()
  })

  it('renders summary totals and paginated entries', async () => {
    server.use(
      summary({ totalRequests: 42, totalCostUsdMicros: 9000, averageDurationMs: 123.45 }),
      http.get('*/v1/admin/ledger/entries', () => HttpResponse.json(pageOf(['r1'], false))),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText('r1')).toBeInTheDocument()
    })
    expect(screen.getByText('42')).toBeInTheDocument()
    expect(screen.getByText('9000')).toBeInTheDocument()
    expect(screen.getByText('123.5')).toBeInTheDocument()
  })

  it('shows the empty state without traffic', async () => {
    server.use(
      summary({ totalRequests: 0, totalCostUsdMicros: 0, averageDurationMs: 0 }),
      http.get('*/v1/admin/ledger/entries', () => HttpResponse.json(pageOf([], false))),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText(/no ledger entries yet/i)).toBeInTheDocument()
    })
  })

  it('pages forward and back on the backend hasNext signal', async () => {
    const user = userEvent.setup()
    server.use(
      summary({ totalRequests: 30, totalCostUsdMicros: 1, averageDurationMs: 2 }),
      http.get('*/v1/admin/ledger/entries', ({ request }) => {
        const page = new URL(request.url).searchParams.get('page')
        return HttpResponse.json(
          page === '1' ? pageOf(['p1-r0'], false, 1) : pageOf(['p0-r0'], true, 0),
        )
      }),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    await user.click(await screen.findByRole('button', { name: /next/i }))
    await waitFor(() => {
      expect(screen.getByText(/page 2/i)).toBeInTheDocument()
    })
    expect(screen.getByText('p1-r0')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /previous/i }))
    await waitFor(() => {
      expect(screen.getByText(/page 1/i)).toBeInTheDocument()
    })
  })

  it('disables next when the backend reports no further page', async () => {
    server.use(
      summary({ totalRequests: 1, totalCostUsdMicros: 1, averageDurationMs: 1 }),
      http.get('*/v1/admin/ledger/entries', () => HttpResponse.json(pageOf(['r1'], false))),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    expect(await screen.findByRole('button', { name: /next/i })).toBeDisabled()
  })

  it('inspects a row receipt without leaving the table', async () => {
    const user = userEvent.setup()
    server.use(
      summary({ totalRequests: 1, totalCostUsdMicros: 12, averageDurationMs: 3 }),
      http.get('*/v1/admin/ledger/entries', () => HttpResponse.json(pageOf(['r9'], false))),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByText('r9'))
    const inspector = screen.getByRole('complementary', { name: /receipt inspector/i })
    expect(inspector).toHaveTextContent('gpt-4o-mini')
    expect(inspector).toHaveTextContent('12')
    await user.click(within(table).getByText('r9'))
    expect(screen.getByRole('complementary', { name: /receipt inspector/i })).toHaveTextContent(
      /select a row to inspect/i,
    )
  })

  it('omits the page total when the backend reports none', async () => {
    server.use(
      summary({ totalRequests: 1, totalCostUsdMicros: 1, averageDurationMs: 1 }),
      http.get('*/v1/admin/ledger/entries', () =>
        HttpResponse.json({ ...pageOf(['r1'], false), totalPages: 0 }),
      ),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    const pager = await screen.findByText(/page 1/i)
    expect(pager).toHaveTextContent('Page 1')
    expect(pager).not.toHaveTextContent('of')
  })

  it('reports summary failures as alerts', async () => {
    server.use(
      http.get('*/v1/admin/ledger/summary', () => new HttpResponse('x', { status: 500 })),
      http.get('*/v1/admin/ledger/entries', () => HttpResponse.json(pageOf([], false))),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/HTTP 500/)
    })
  })

  it('reports log failures as alerts', async () => {
    server.use(
      summary({ totalRequests: 1, totalCostUsdMicros: 1, averageDurationMs: 1 }),
      http.get('*/v1/admin/ledger/entries', () => new HttpResponse('x', { status: 503 })),
    )
    renderApp(<LedgerPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/temporarily unavailable/i)
    })
  })
})
