import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp, selectOption } from '../../test/utils.js'
import { NotificationsPage } from './page.js'

const ROWS = [
  {
    id: '11111111-1111-1111-1111-111111111111',
    scope: 'budgets',
    channel: 'webhook',
    target: 'https://ops.example.com/hook',
    secretRef: null,
    minSeverity: 'warning',
    createdAt: '2026-09-01T12:00:00Z',
  },
]

function listOk() {
  return http.get('*/v1/admin/notifications', () => HttpResponse.json(ROWS))
}

describe('NotificationsPage', () => {
  it('lists subscriptions for a scope and deletes with confirm', async () => {
    const user = userEvent.setup()
    server.use(
      listOk(),
      http.delete('*/v1/admin/notifications/:id', () => new HttpResponse(null, { status: 204 })),
    )
    renderApp(<NotificationsPage />, { adminSession: true })
    await user.type(screen.getByLabelText(/alert scope/i), 'budgets')
    await user.click(screen.getByRole('button', { name: /^list$/i }))
    const table = await screen.findByRole('table')
    expect(within(table).getByText('https://ops.example.com/hook')).toBeInTheDocument()
    await user.click(within(table).getByRole('button', { name: /^delete$/i }))
    await user.click(screen.getByRole('button', { name: /^yes$/i }))
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(/subscription deleted/i)
    })
  })

  it('creates a subscription and announces the outcome', async () => {
    const user = userEvent.setup()
    server.use(
      listOk(),
      http.post('*/v1/admin/notifications', () =>
        HttpResponse.json(
          { ...ROWS[0], id: '22222222-2222-2222-2222-222222222222' },
          { status: 201 },
        ),
      ),
    )
    renderApp(<NotificationsPage />, { adminSession: true })
    await user.type(screen.getByLabelText(/alert scope/i), 'budgets')
    await user.click(screen.getByRole('button', { name: /^list$/i }))
    await screen.findByRole('table')
    await user.click(screen.getByRole('button', { name: /new subscription/i }))
    await user.type(screen.getByLabelText(/target/i), 'https://ops.example.com/hook')
    await user.click(screen.getByRole('button', { name: /^create$/i }))
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(/subscription created/i)
    })
  })

  it('drives the full create form and cancels cleanly', async () => {
    const user = userEvent.setup()
    let body: unknown = null
    let calls = 0
    server.use(
      listOk(),
      http.post('*/v1/admin/notifications', async ({ request }) => {
        calls += 1
        body = await request.json()
        return HttpResponse.json(
          { ...ROWS[0], id: '22222222-2222-2222-2222-222222222222' },
          { status: 201 },
        )
      }),
      http.delete('*/v1/admin/notifications/:id', () => {
        calls += 1
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderApp(<NotificationsPage />, { adminSession: true })
    await user.type(screen.getByLabelText(/alert scope/i), 'budgets')
    await user.click(screen.getByRole('button', { name: /^list$/i }))
    await screen.findByRole('table')
    await user.click(screen.getByRole('button', { name: /new subscription/i }))
    await user.type(screen.getByLabelText(/target/i), 'ops@example.com')
    await selectOption(user, /channel/i, 'email')
    await selectOption(user, /min severity/i, 'critical')
    await user.type(screen.getByLabelText(/secret ref/i), 'OPS_HOOK_SECRET')
    await user.click(screen.getByRole('button', { name: /^create$/i }))
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(/subscription created/i)
    })
    expect(body).toMatchObject({ channel: 'email', minSeverity: 'critical' })
    await user.click(screen.getByRole('button', { name: /new subscription/i }))
    await user.click(screen.getByRole('button', { name: /^cancel$/i }))
    await user.click(within(screen.getByRole('table')).getByRole('button', { name: /^delete$/i }))
    await user.click(screen.getByRole('button', { name: /^no$/i }))
    expect(calls).toBe(1)
  })

  it('reports create and delete failures honestly', async () => {
    const user = userEvent.setup()
    server.use(
      listOk(),
      http.post('*/v1/admin/notifications', () => new HttpResponse('x', { status: 409 })),
      http.delete('*/v1/admin/notifications/:id', () => new HttpResponse('x', { status: 500 })),
    )
    renderApp(<NotificationsPage />, { adminSession: true })
    await user.type(screen.getByLabelText(/alert scope/i), 'budgets')
    await user.click(screen.getByRole('button', { name: /^list$/i }))
    await screen.findByRole('table')
    await user.click(screen.getByRole('button', { name: /new subscription/i }))
    await user.type(screen.getByLabelText(/target/i), 'https://ops.example.com/hook')
    await user.click(screen.getByRole('button', { name: /^create$/i }))
    expect(await screen.findByText(/conflict/i)).toBeInTheDocument()
    await user.click(within(screen.getByRole('table')).getByRole('button', { name: /^delete$/i }))
    await user.click(screen.getByRole('button', { name: /^yes$/i }))
    expect(await screen.findByText(/HTTP 500/)).toBeInTheDocument()
  })

  it('requires a scope before listing', async () => {
    renderApp(<NotificationsPage />, { adminSession: true })
    expect(await screen.findByText(/enter a scope to list subscriptions/i)).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('names an empty scope honestly', async () => {
    const user = userEvent.setup()
    server.use(http.get('*/v1/admin/notifications', () => HttpResponse.json([])))
    renderApp(<NotificationsPage />, { adminSession: true })
    await user.type(screen.getByLabelText(/alert scope/i), 'quiet')
    await user.click(screen.getByRole('button', { name: /^list$/i }))
    expect(await screen.findByText(/no subscriptions for scope/i)).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('probes the alert webhook and reports the receipt count', async () => {
    const user = userEvent.setup()
    server.use(http.post('*/v1/admin/alerts/webhook', () => HttpResponse.json({ received: 1 })))
    renderApp(<NotificationsPage />, { adminSession: true })
    await user.click(screen.getByRole('button', { name: /send test alert/i }))
    await waitFor(() => {
      expect(screen.getByText(/webhook received 1 alert/i)).toBeInTheDocument()
    })
  })

  it('pluralizes multi-alert receipts', async () => {
    const user = userEvent.setup()
    server.use(http.post('*/v1/admin/alerts/webhook', () => HttpResponse.json({ received: 3 })))
    renderApp(<NotificationsPage />, { adminSession: true })
    await user.click(screen.getByRole('button', { name: /send test alert/i }))
    await waitFor(() => {
      expect(screen.getByText(/webhook received 3 alerts/i)).toBeInTheDocument()
    })
  })

  it('reports probe and list failures honestly', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/notifications', () => new HttpResponse('x', { status: 500 })),
      http.post('*/v1/admin/alerts/webhook', () => new HttpResponse('x', { status: 500 })),
    )
    renderApp(<NotificationsPage />, { adminSession: true })
    await user.type(screen.getByLabelText(/alert scope/i), 'budgets')
    await user.click(screen.getByRole('button', { name: /^list$/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/HTTP 500/)
    await user.click(screen.getByRole('button', { name: /send test alert/i }))
    const alerts = await screen.findAllByRole('alert')
    expect(alerts.length).toBeGreaterThanOrEqual(2)
  })
})
