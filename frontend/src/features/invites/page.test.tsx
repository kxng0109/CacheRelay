import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { InvitesPage } from './page.js'

describe('InvitesPage', () => {
  it('labels the admin toggle without clipping', () => {
    renderApp(<InvitesPage />, { adminSession: true })
    expect(screen.getByRole('checkbox', { name: /admin invite/i })).toBeInTheDocument()
  })

  it('mints a link-only invite and reveals the link once', async () => {
    const user = userEvent.setup()
    server.use(
      http.post('*/v1/admin/invites', () =>
        HttpResponse.json(
          { link: 'http://localhost:8080/redeem?token=abc', emailed: false },
          { status: 201 },
        ),
      ),
    )
    renderApp(<InvitesPage />, { adminSession: true })
    await user.click(screen.getByRole('button', { name: /mint invite/i }))
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(/invite created/i)
    })
    expect(screen.getByText('http://localhost:8080/redeem?token=abc')).toBeInTheDocument()
    expect(screen.getByText(/link only — no mail sent/i)).toBeInTheDocument()
  })

  it('rejects a malformed email before sending', async () => {
    const user = userEvent.setup()
    let posts = 0
    server.use(
      http.post('*/v1/admin/invites', () => {
        posts += 1
        return HttpResponse.json({ link: 'x', emailed: true }, { status: 201 })
      }),
    )
    renderApp(<InvitesPage />, { adminSession: true })
    await user.type(screen.getByLabelText(/email/i), 'not-an-email')
    await user.click(screen.getByRole('button', { name: /mint invite/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/must be valid/i)
    expect(posts).toBe(0)
  })

  it('names mailed invites and dismisses the receipt', async () => {
    const user = userEvent.setup()
    server.use(
      http.post('*/v1/admin/invites', () =>
        HttpResponse.json({ link: 'http://x/redeem?token=mailed', emailed: true }, { status: 201 }),
      ),
    )
    renderApp(<InvitesPage />, { adminSession: true })
    await user.type(screen.getByLabelText(/email/i), 'ops@example.com')
    await user.click(screen.getByRole('checkbox', { name: /admin/i }))
    await user.click(screen.getByRole('button', { name: /mint invite/i }))
    await waitFor(() => {
      expect(screen.getByText(/mailed to the address/i)).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: /dismiss/i }))
    expect(screen.queryByText('http://x/redeem?token=mailed')).not.toBeInTheDocument()
  })

  it('reports mint failures honestly', async () => {
    const user = userEvent.setup()
    server.use(http.post('*/v1/admin/invites', () => new HttpResponse('x', { status: 409 })))
    renderApp(<InvitesPage />, { adminSession: true })
    await user.click(screen.getByRole('button', { name: /mint invite/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/conflict/i)
  })
})
