import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { UserLedgerPage } from './UserPage.js'

const UID = '123e4567-e89b-12d3-a456-426614174000'

function summary() {
  return {
    totalRequests: 3,
    totalPromptTokens: 300,
    totalCompletionTokens: 100,
    totalTokens: 400,
    totalCostUsdMicros: 600,
    totalCostUsd: '0.000600',
    averageDurationMs: 11.1,
    byOwner: [],
    byModel: [],
    byProvider: [],
  }
}

describe('UserLedgerPage', () => {
  it('asks for an account before fetching', () => {
    renderApp(<UserLedgerPage />, { route: '/ledger/user', adminSession: true })
    expect(screen.getByText(/enter an account id above/i)).toBeInTheDocument()
  })

  it('drill-downs into the typed account', async () => {
    server.use(http.get('*/v1/admin/ledger/user/:id/summary', () => HttpResponse.json(summary())))
    renderApp(<UserLedgerPage />, { route: '/ledger/user', adminSession: true })
    const user = userEvent.setup()
    fireEvent.change(screen.getByLabelText(/account id/i), { target: { value: UID } })
    await user.click(screen.getByRole('button', { name: /^inspect$/i }))
    await waitFor(() => {
      expect(screen.getByText('3')).toBeInTheDocument()
    })
  })

  it('renders admin-unavailable on stealth 404 without distinguishing', async () => {
    server.use(
      http.get('*/v1/admin/ledger/user/:id/summary', () => new HttpResponse('x', { status: 404 })),
    )
    renderApp(<UserLedgerPage />, { route: '/ledger/user', adminSession: true })
    const user = userEvent.setup()
    fireEvent.change(screen.getByLabelText(/account id/i), { target: { value: UID } })
    await user.click(screen.getByRole('button', { name: /^inspect$/i }))
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/admin unavailable/i)
    })
    expect(screen.getByRole('alert')).toHaveTextContent(/no access or no route/i)
  })

  it('requires an account id before inspecting', async () => {
    renderApp(<UserLedgerPage />, { route: '/ledger/user', adminSession: true })
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /^inspect$/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/enter an account id/i)
  })

  it('rejects reversed windows before fetching', async () => {
    renderApp(<UserLedgerPage />, { route: '/ledger/user', adminSession: true })
    const user = userEvent.setup()
    fireEvent.change(screen.getByLabelText(/account id/i), { target: { value: UID } })
    fireEvent.change(screen.getByLabelText(/^from$/i), { target: { value: '2026-09-24' } })
    fireEvent.change(screen.getByLabelText(/^to$/i), { target: { value: '2026-09-01' } })
    await user.click(screen.getByRole('button', { name: /^inspect$/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/cannot be after/i)
  })

  it('applies account plus window to the drill-down', async () => {
    let seenUrl = ''
    server.use(
      http.get('*/v1/admin/ledger/user/:id/summary', ({ request }) => {
        seenUrl = request.url
        return HttpResponse.json(summary())
      }),
    )
    renderApp(<UserLedgerPage />, { route: '/ledger/user', adminSession: true })
    const user = userEvent.setup()
    fireEvent.change(screen.getByLabelText(/account id/i), { target: { value: UID } })
    fireEvent.change(screen.getByLabelText(/^from$/i), { target: { value: '2026-09-20' } })
    fireEvent.change(screen.getByLabelText(/^to$/i), { target: { value: '2026-09-24' } })
    await user.click(screen.getByRole('button', { name: /^inspect$/i }))
    await waitFor(() => {
      expect(seenUrl).toContain(UID)
    })
    expect(seenUrl).toContain('from=2026-09-20')
    expect(screen.getByText('3')).toBeInTheDocument()
  })

  it('surfaces drill-down failures with retry', async () => {
    server.use(
      http.get('*/v1/admin/ledger/user/:id/summary', () => new HttpResponse('x', { status: 500 })),
    )
    renderApp(<UserLedgerPage />, { route: '/ledger/user', adminSession: true })
    const user = userEvent.setup()
    fireEvent.change(screen.getByLabelText(/account id/i), { target: { value: UID } })
    await user.click(screen.getByRole('button', { name: /^inspect$/i }))
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/HTTP 500/)
    })
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument()
  })
})
