import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { Route, Routes, useLocation } from 'react-router'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { useAuthStore } from '../../shared/auth/store.js'
import { LoginPage } from './LoginPage.js'

/**
 * Echoes the post-login location so navigation targets are asserted, not
 * just the stored session.
 *
 * @returns The current path and query as text.
 */
function LocationProbe(): React.JSX.Element {
  const { pathname, search } = useLocation()
  return <p>{`at:${pathname}${search}`}</p>
}

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

  it('focuses the username field on arrival', () => {
    renderApp(<LoginPage />)
    expect(screen.getByLabelText(/username/i)).toHaveFocus()
  })

  it('warns about caps lock while typing the password', () => {
    renderApp(<LoginPage />)
    const password = screen.getByLabelText(/^password$/i)
    const down = new KeyboardEvent('keydown', { key: 'a', bubbles: true })
    Object.defineProperty(down, 'getModifierState', { value: () => true })
    fireEvent(password, down)
    expect(screen.getByText(/caps lock is on/i)).toBeInTheDocument()
    const up = new KeyboardEvent('keyup', { key: 'a', bubbles: true })
    Object.defineProperty(up, 'getModifierState', { value: () => false })
    fireEvent(password, up)
    expect(screen.queryByText(/caps lock is on/i)).not.toBeInTheDocument()
  })

  it('stays silent when the browser hides modifier state', () => {
    renderApp(<LoginPage />)
    const password = screen.getByLabelText(/^password$/i)
    const down = new KeyboardEvent('keydown', { key: 'a', bubbles: true })
    Object.defineProperty(down, 'getModifierState', { value: undefined })
    fireEvent(password, down)
    expect(screen.queryByText(/caps lock is on/i)).not.toBeInTheDocument()
  })

  it('links first accounts to the invite screen', async () => {
    const user = userEvent.setup()
    renderApp(
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/redeem" element={<LocationProbe />} />
      </Routes>,
      { route: '/login' },
    )
    await user.click(screen.getByRole('link', { name: /redeem an invite instead/i }))
    await waitFor(() => {
      expect(screen.getByText('at:/redeem')).toBeInTheDocument()
    })
  })

  it('toggles password visibility without submitting', async () => {
    const user = userEvent.setup()
    renderApp(<LoginPage />)
    const password = screen.getByLabelText(/^password$/i)
    expect(password).toHaveAttribute('type', 'password')
    await user.click(screen.getByRole('button', { name: /show password/i }))
    expect(password).toHaveAttribute('type', 'text')
    expect(screen.getByRole('button', { name: /hide password/i })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
  })

  it('returns to the validated ?next= destination after login', async () => {
    const user = userEvent.setup()
    server.use(
      http.post('*/v1/auth/login', () =>
        HttpResponse.json({ accessToken: 'jwt-next', expiresInSeconds: 300, admin: false }),
      ),
    )
    renderApp(
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="*" element={<LocationProbe />} />
      </Routes>,
      { route: '/login?next=/observability' },
    )
    await user.type(screen.getByLabelText(/username/i), 'op')
    await user.type(screen.getByLabelText(/^password$/i), 'correct horse battery staple')
    await user.click(screen.getByRole('button', { name: /^log in$/i }))
    await waitFor(() => {
      expect(screen.getByText('at:/observability')).toBeInTheDocument()
    })
  })

  it('falls back home for a hostile ?next= destination', async () => {
    const user = userEvent.setup()
    server.use(
      http.post('*/v1/auth/login', () =>
        HttpResponse.json({ accessToken: 'jwt-next', expiresInSeconds: 300, admin: false }),
      ),
    )
    renderApp(
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="*" element={<LocationProbe />} />
      </Routes>,
      { route: '/login?next=https://evil.example/steal' },
    )
    await user.type(screen.getByLabelText(/username/i), 'op')
    await user.type(screen.getByLabelText(/^password$/i), 'correct horse battery staple')
    await user.click(screen.getByRole('button', { name: /^log in$/i }))
    await waitFor(() => {
      expect(screen.getByText('at:/')).toBeInTheDocument()
    })
  })
})
