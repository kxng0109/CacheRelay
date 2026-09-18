import { screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { OverviewPage } from './page.js'

describe('OverviewPage', () => {
  it('renders tiles, chart fallback, and section links', async () => {
    server.use(
      http.get('*/v1/admin/ledger/summary', () =>
        HttpResponse.json({ totalRequests: 42, totalCostUsdMicros: 9000, averageDurationMs: 12.5 }),
      ),
      http.get(
        '*/actuator/prometheus',
        () => new HttpResponse('', { headers: { 'Content-Type': 'text/plain' } }),
      ),
    )
    renderApp(<OverviewPage />, { adminKey: 'master-test' })
    expect(screen.getByRole('heading', { name: /overview/i })).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByText('42')).toBeInTheDocument()
    })
    for (const label of ['Playground', 'Circuits', 'Ledger', 'Observability']) {
      expect(screen.getByRole('link', { name: new RegExp(label) })).toBeInTheDocument()
    }
  })

  it('stays locked without an admin key', () => {
    renderApp(<OverviewPage />)
    expect(screen.getByText(/unlock the admin key/i)).toBeInTheDocument()
    expect(screen.queryByText('42')).not.toBeInTheDocument()
  })

  it('reports summary failures as alerts', async () => {
    server.use(
      http.get('*/v1/admin/ledger/summary', () => new HttpResponse('x', { status: 500 })),
      http.get(
        '*/actuator/prometheus',
        () => new HttpResponse('', { headers: { 'Content-Type': 'text/plain' } }),
      ),
    )
    renderApp(<OverviewPage />, { adminKey: 'master-test' })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/HTTP 500/)
    })
  })
})
