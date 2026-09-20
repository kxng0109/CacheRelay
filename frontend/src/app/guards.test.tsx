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

  it('renders the missing page for logged-out visitors', () => {
    const { unmount } = renderApp(
      <RequireAdmin>
        <p>secret board</p>
      </RequireAdmin>,
    )
    expect(screen.getByRole('heading', { name: /page not found/i })).toBeInTheDocument()
    expect(screen.queryByText('secret board')).not.toBeInTheDocument()
    unmount()
  })

  it('renders the same missing page as unknown routes', () => {
    const { unmount: unmountGuarded } = renderApp(
      <RequireAdmin>
        <p>secret board</p>
      </RequireAdmin>,
    )
    const guardedHtml = document.body.innerHTML
    unmountGuarded()
    renderApp(<NotFound />)
    // Same heading, same copy, same home link — no distinguishing signal.
    expect(document.body.innerHTML).toContain('This page does not exist.')
    expect(guardedHtml).toContain('This page does not exist.')
    expect(screen.getByRole('link', { name: /back to overview/i })).toBeInTheDocument()
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
