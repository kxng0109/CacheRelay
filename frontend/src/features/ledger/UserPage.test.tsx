import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactElement } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { afterEach, describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp, selectOption } from '../../test/utils.js'
import { useAuthStore } from '../../shared/auth/store.js'
import { UserLedgerPage } from './UserPage.js'

/**
 * Renders with a real `:userId` route param (the shared helper only
 * matches wildcards, so deep-link prefill needs its own router).
 *
 * @param ui - Element under test.
 * @param route - Initial entry carrying the param.
 */
function renderWithParam(ui: ReactElement, route: string) {
  useAuthStore.getState().clear()
  useAuthStore
    .getState()
    .setSession({ accessToken: 'test-admin-jwt', admin: true, username: 'test-admin' })
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[route]}>
        <Routes>
          <Route path="/ledger/user/:userId" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  useAuthStore.getState().clear()
})

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
    await selectOption(user, /range/i, 'Custom range')
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
    await selectOption(user, /range/i, 'Custom range')
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

  it('inspects from the keyboard without the button', async () => {
    const user = userEvent.setup()
    server.use(http.get('*/v1/admin/ledger/user/:id/summary', () => HttpResponse.json(summary())))
    renderApp(<UserLedgerPage />, { route: '/ledger/user', adminSession: true })
    fireEvent.change(screen.getByLabelText(/account id/i), { target: { value: UID } })
    await user.click(screen.getByLabelText(/account id/i))
    await user.keyboard('{Enter}')
    await waitFor(() => {
      expect(screen.getByText('3')).toBeInTheDocument()
    })
  })

  it('prefills and auto-fetches deep links with the account id', async () => {
    const seen: string[] = []
    server.use(
      http.get('*/v1/admin/ledger/user/:id/summary', ({ request }) => {
        seen.push(request.url)
        return HttpResponse.json(summary())
      }),
    )
    renderWithParam(<UserLedgerPage />, `/ledger/user/${UID}`)
    await waitFor(() => {
      expect(screen.getByText('3')).toBeInTheDocument()
    })
    expect(seen.some((u) => u.includes(UID))).toBe(true)
    expect(screen.getByLabelText(/account id/i)).toHaveValue(UID)
  })
})
