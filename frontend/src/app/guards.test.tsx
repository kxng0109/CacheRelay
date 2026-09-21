import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { Route, Routes } from 'react-router'
import { renderApp } from '../test/utils.js'
import { NotFound } from './NotFound.js'
import { RequireAdmin } from './RequireAdmin.js'
import { RequireAuth } from './RequireAuth.js'
import { RequireGuest } from './RequireGuest.js'

describe('RequireAdmin stealth', () => {
  it('renders children for admin sessions', () => {
    renderApp(
      <RequireAdmin>
        <p>secret board</p>
      </RequireAdmin>,
      { adminSession: true },
    )
    expect(screen.getByText('secret board')).toBeInTheDocument()
  })

  it('bounces logged-out visitors to login preserving the destination', () => {
    renderApp(
      <Routes>
        <Route path="/login" element={<p>login screen</p>} />
        <Route
          path="*"
          element={
            <RequireAdmin>
              <p>secret board</p>
            </RequireAdmin>
          }
        />
      </Routes>,
      { route: '/circuits' },
    )
    expect(screen.getByText('login screen')).toBeInTheDocument()
    expect(screen.queryByText('secret board')).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /page not found/i })).not.toBeInTheDocument()
  })

  it('shows the missing page to logged-in non-admins', () => {
    renderApp(
      <RequireAdmin>
        <p>secret board</p>
      </RequireAdmin>,
      { nonAdminSession: true },
    )
    expect(screen.getByRole('heading', { name: /page not found/i })).toBeInTheDocument()
    expect(screen.queryByText('secret board')).not.toBeInTheDocument()
  })

  it('renders the same missing page as unknown routes', () => {
    const { unmount: unmountGuarded } = renderApp(
      <RequireAdmin>
        <p>secret board</p>
      </RequireAdmin>,
      { nonAdminSession: true },
    )
    const guardedHtml = document.body.innerHTML
    unmountGuarded()
    renderApp(<NotFound />)
    // Same heading, same copy, same actions — no distinguishing signal.
    expect(document.body.innerHTML).toContain(
      'That address does not lead anywhere in this console.',
    )
    expect(guardedHtml).toContain('That address does not lead anywhere in this console.')
    expect(screen.getByRole('link', { name: /back to overview/i })).toBeInTheDocument()
  })

  it('keeps the illustration and recovery actions on the missing page', () => {
    renderApp(<NotFound />)
    expect(
      screen.getByRole('img', { name: /drifted away from the relay node/i }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /back to overview/i })).toHaveAttribute('href', '/')
    expect(screen.getByRole('link', { name: /open playground/i })).toHaveAttribute(
      'href',
      '/playground',
    )
  })
})

describe('RequireAuth session gate', () => {
  it('renders children for admin and non-admin sessions alike', () => {
    const { unmount } = renderApp(
      <RequireAuth>
        <p>session board</p>
      </RequireAuth>,
      { nonAdminSession: true },
    )
    expect(screen.getByText('session board')).toBeInTheDocument()
    unmount()
    renderApp(
      <RequireAuth>
        <p>session board</p>
      </RequireAuth>,
      { adminSession: true },
    )
    expect(screen.getByText('session board')).toBeInTheDocument()
  })

  it('bounces guests to login preserving the destination', () => {
    renderApp(
      <Routes>
        <Route path="/login" element={<p>login screen</p>} />
        <Route
          path="*"
          element={
            <RequireAuth>
              <p>session board</p>
            </RequireAuth>
          }
        />
      </Routes>,
      { route: '/observability' },
    )
    expect(screen.getByText('login screen')).toBeInTheDocument()
    expect(screen.queryByText('session board')).not.toBeInTheDocument()
  })
})

describe('RequireGuest logged-out gate', () => {
  it('renders children for logged-out visitors', () => {
    renderApp(
      <RequireGuest>
        <p>login form</p>
      </RequireGuest>,
      { route: '/login' },
    )
    expect(screen.getByText('login form')).toBeInTheDocument()
  })

  it('sends live sessions home instead of showing login again', () => {
    renderApp(
      <Routes>
        <Route path="/" element={<p>home screen</p>} />
        <Route
          path="*"
          element={
            <RequireGuest>
              <p>login form</p>
            </RequireGuest>
          }
        />
      </Routes>,
      { route: '/login', adminSession: true },
    )
    expect(screen.getByText('home screen')).toBeInTheDocument()
    expect(screen.queryByText('login form')).not.toBeInTheDocument()
  })
})
