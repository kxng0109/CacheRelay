import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderApp } from '../test/utils.js'
import { NotFound } from './NotFound.js'
import { RequireAdmin } from './RequireAdmin.js'

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
