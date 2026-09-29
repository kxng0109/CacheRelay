import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { UsersPage } from './page.js'

const ID = '123e4567-e89b-12d3-a456-426614174000'

describe('UsersPage', () => {
  it('disables an account by id and announces the outcome', async () => {
    const user = userEvent.setup()
    server.use(
      http.put('*/v1/admin/users/:id/disabled', () => new HttpResponse(null, { status: 204 })),
    )
    renderApp(<UsersPage />, { adminSession: true })
    await user.type(screen.getByLabelText(/account id/i), ID)
    await user.click(screen.getByRole('button', { name: /disable account/i }))
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(/account disabled/i)
    })
  })

  it('requires confirm before deleting and names the key cascade', async () => {
    const user = userEvent.setup()
    let deletes = 0
    server.use(
      http.delete('*/v1/admin/users/:id', () => {
        deletes += 1
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderApp(<UsersPage />, { adminSession: true })
    await user.type(screen.getByLabelText(/account id/i), ID)
    await user.click(screen.getByRole('button', { name: /^delete account$/i }))
    expect(screen.getByText(/revokes every attached key/i)).toBeInTheDocument()
    expect(deletes).toBe(0)
    await user.click(screen.getByRole('button', { name: /^no$/i }))
    expect(deletes).toBe(0)
    await user.click(screen.getByRole('button', { name: /^delete account$/i }))
    await user.click(screen.getByRole('button', { name: /^yes, delete$/i }))
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(/account deleted/i)
    })
    expect(deletes).toBe(1)
  })

  it('states there is no inventory endpoint', async () => {
    renderApp(<UsersPage />, { adminSession: true })
    expect(await screen.findByText(/no user inventory endpoint/i)).toBeInTheDocument()
  })

  it('rejects non-UUID account ids before calling the gateway', async () => {
    const user = userEvent.setup()
    let calls = 0
    server.use(
      http.put('*/v1/admin/users/:id/disabled', () => {
        calls += 1
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderApp(<UsersPage />, { adminSession: true })
    await user.type(screen.getByLabelText(/account id/i), 'not-a-uuid')
    expect(await screen.findByText(/must be a valid uuid/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /disable account/i })).toBeDisabled()
    expect(calls).toBe(0)
  })

  it('re-enables an account and reports failures honestly', async () => {
    const user = userEvent.setup()
    let body: unknown = null
    server.use(
      http.put('*/v1/admin/users/:id/disabled', async ({ request }) => {
        body = await request.json()
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderApp(<UsersPage />, { adminSession: true })
    await user.type(screen.getByLabelText(/account id/i), ID)
    await user.click(screen.getByRole('button', { name: /re-enable account/i }))
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(/re-enabled/i)
    })
    expect(body).toMatchObject({ disabled: false })
  })

  it('reports disable failures honestly', async () => {
    const user = userEvent.setup()
    server.use(
      http.put('*/v1/admin/users/:id/disabled', () => new HttpResponse('x', { status: 500 })),
    )
    renderApp(<UsersPage />, { adminSession: true })
    await user.type(screen.getByLabelText(/account id/i), ID)
    await user.click(screen.getByRole('button', { name: /disable account/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/HTTP 500/)
  })

  it('reports deletion failures honestly', async () => {
    const user = userEvent.setup()
    server.use(http.delete('*/v1/admin/users/:id', () => new HttpResponse('x', { status: 404 })))
    renderApp(<UsersPage />, { adminSession: true })
    await user.type(screen.getByLabelText(/account id/i), ID)
    await user.click(screen.getByRole('button', { name: /^delete account$/i }))
    await user.click(screen.getByRole('button', { name: /^yes, delete$/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/not found/i)
  })
})
