import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { LedgerPage } from './page.js'

describe('LedgerPage', () => {
  it('requires the admin key first', () => {
    renderApp(<LedgerPage />)
    expect(screen.getByText(/unlock the admin key/i)).toBeInTheDocument()
  })

  it('renders summary totals and paginated entries', async () => {
    server.use(
      http.get('*/v1/admin/ledger/summary', () =>
        HttpResponse.json({ totalRequests: 42, totalCostMicros: 9000, cacheHitRate: 0.5 }),
      ),
      http.get('*/v1/admin/ledger/logs', () =>
        HttpResponse.json({
          entries: [
            {
              requestId: 'r1',
              keyId: 'k1',
              model: 'gpt-4o-mini',
              costMicros: 12,
              createdAt: '2026-09-17T00:00:00Z',
            },
          ],
        }),
      ),
    )
    renderApp(<LedgerPage />, { adminKey: 'master-test' })
    await waitFor(() => {
      expect(screen.getByText('r1')).toBeInTheDocument()
    })
    expect(screen.getByText('42')).toBeInTheDocument()
    expect(screen.getByText('50.0%')).toBeInTheDocument()
  })

  it('shows the empty state without traffic', async () => {
    server.use(
      http.get('*/v1/admin/ledger/summary', () =>
        HttpResponse.json({ totalRequests: 0, totalCostMicros: 0, cacheHitRate: 0 }),
      ),
      http.get('*/v1/admin/ledger/logs', () => HttpResponse.json({ entries: [] })),
    )
    renderApp(<LedgerPage />, { adminKey: 'master-test' })
    await waitFor(() => {
      expect(screen.getByText(/no ledger entries yet/i)).toBeInTheDocument()
    })
  })

  it('pages forward and back', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/ledger/summary', () =>
        HttpResponse.json({ totalRequests: 30, totalCostMicros: 1, cacheHitRate: 0 }),
      ),
      http.get('*/v1/admin/ledger/logs', ({ request }) => {
        const url = new URL(request.url)
        const page = url.searchParams.get('page')
        const size = page === '1' ? 3 : 25
        const entries = Array.from({ length: size }, (_, i) => ({
          requestId: `p${page ?? '0'}-r${String(i)}`,
          keyId: 'k',
          model: 'm',
          costMicros: 1,
          createdAt: '2026-09-17T00:00:00Z',
        }))
        return HttpResponse.json({ entries })
      }),
    )
    renderApp(<LedgerPage />, { adminKey: 'master-test' })
    await user.click(await screen.findByRole('button', { name: /next/i }))
    await waitFor(() => {
      expect(screen.getByText(/page 2/i)).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: /previous/i }))
    await waitFor(() => {
      expect(screen.getByText(/page 1/i)).toBeInTheDocument()
    })
  })

  it('reports summary failures as alerts', async () => {
    server.use(
      http.get('*/v1/admin/ledger/summary', () => new HttpResponse('x', { status: 500 })),
      http.get('*/v1/admin/ledger/logs', () => HttpResponse.json({ entries: [] })),
    )
    renderApp(<LedgerPage />, { adminKey: 'master-test' })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/HTTP 500/)
    })
  })

  it('reports log failures as alerts', async () => {
    server.use(
      http.get('*/v1/admin/ledger/summary', () =>
        HttpResponse.json({ totalRequests: 1, totalCostMicros: 1, cacheHitRate: 0 }),
      ),
      http.get('*/v1/admin/ledger/logs', () => new HttpResponse('x', { status: 503 })),
    )
    renderApp(<LedgerPage />, { adminKey: 'master-test' })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/temporarily unavailable/i)
    })
  })
})
