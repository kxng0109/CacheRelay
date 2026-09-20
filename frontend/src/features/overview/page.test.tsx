import { screen, waitFor } from '@testing-library/react'
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
})

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
    renderApp(<OverviewPage />, { adminSession: true })
    expect(screen.getByRole('heading', { name: /overview/i })).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByText('42')).toBeInTheDocument()
    })
    for (const label of ['Playground', 'Circuits', 'Ledger', 'Observability']) {
      expect(screen.getByRole('link', { name: new RegExp(label) })).toBeInTheDocument()
    }
  })

  it('shows no spend totals and no hint without an admin session', () => {
    renderApp(<OverviewPage />)
    expect(screen.queryByText(/unlock the admin key/i)).not.toBeInTheDocument()
    expect(screen.queryByText('42')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /overview/i })).toBeInTheDocument()
  })

  it('hides tiles for non-admin sessions without a hint', () => {
    renderApp(<OverviewPage />, { nonAdminSession: true })
    expect(screen.queryByText(/unlock the admin key/i)).not.toBeInTheDocument()
    expect(screen.queryByText('42')).not.toBeInTheDocument()
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
        HttpResponse.json({ totalRequests: 7, totalCostUsdMicros: 100, averageDurationMs: 3.2 }),
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
    expect(screen.getByText('—')).toBeInTheDocument()
  })
})
