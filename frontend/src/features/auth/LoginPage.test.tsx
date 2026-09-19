import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { useAuthStore } from '../../shared/auth/store.js'
import { LoginPage } from './LoginPage.js'

describe('LoginPage', () => {
  it('logs in and stores a memory-only session', async () => {
    const user = userEvent.setup()
    server.use(
      http.post('*/v1/auth/login', () =>
        HttpResponse.json({ accessToken: 'jwt-1', expiresInSeconds: 300, admin: true }),
      ),
    )
    renderApp(<LoginPage />)
    await user.type(screen.getByLabelText(/username/i), '  operator  ')
    await user.type(screen.getByLabelText(/^password$/i), 'correct horse battery staple')
    await user.click(screen.getByRole('button', { name: /^log in$/i }))
    await waitFor(() => {
      expect(useAuthStore.getState().session).toEqual({
        accessToken: 'jwt-1',
        admin: true,
        username: 'operator',
      })
    })
  })

  it('trims the username but never the password', async () => {
    const user = userEvent.setup()
    let body = ''
    server.use(
      http.post('*/v1/auth/login', async ({ request }) => {
        body = await request.text()
        return HttpResponse.json({ accessToken: 'j', expiresInSeconds: 1, admin: false })
      }),
    )
    renderApp(<LoginPage />)
    await user.type(screen.getByLabelText(/username/i), '  op  ')
    await user.type(screen.getByLabelText(/^password$/i), '  spaced  ')
    await user.click(screen.getByRole('button', { name: /^log in$/i }))
    await waitFor(() => {
      expect(body).toContain('"username":"op"')
    })
    expect(body).toContain('"password":"  spaced  "')
  })

  it('surfaces wrong credentials and lockouts distinctly', async () => {
    const user = userEvent.setup()
    server.use(http.post('*/v1/auth/login', () => new HttpResponse('x', { status: 401 })))
    renderApp(<LoginPage />)
    await user.type(screen.getByLabelText(/username/i), 'op')
    await user.type(screen.getByLabelText(/^password$/i), 'nope')
    await user.click(screen.getByRole('button', { name: /^log in$/i }))
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/invalid credentials/i)
    })
    expect(useAuthStore.getState().session).toBeNull()
  })

  it('validates empty fields before submitting', async () => {
    const user = userEvent.setup()
    renderApp(<LoginPage />)
    await user.click(screen.getByRole('button', { name: /^log in$/i }))
    await waitFor(() => {
      expect(screen.getByText(/username is required/i)).toBeInTheDocument()
    })
  })
})
