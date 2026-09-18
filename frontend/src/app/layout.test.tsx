import { act, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { useAuthStore } from '../shared/auth/store.js'
import { useRateLimitStore } from '../shared/ratelimit/store.js'
import { renderApp } from '../test/utils.js'
import { Layout } from './layout.js'

describe('Layout', () => {
  beforeEach(() => {
    useRateLimitStore.getState().clear()
  })
  it('renders the product header, nav, and skip link', () => {
    renderApp(<Layout />)
    expect(screen.getByText('CacheRelay')).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: /primary/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /skip to content/i })).toHaveAttribute('href', '#main')
  })

  it('toggles the dark theme on the document root', async () => {
    const user = userEvent.setup()
    renderApp(<Layout />)
    const toggle = screen.getByRole('button', { name: /dark theme/i })
    await user.click(toggle)
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    await user.click(screen.getByRole('button', { name: /light theme/i }))
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })

  it('hides the rate-limit strip before any gateway response', () => {
    renderApp(<Layout />, { gatewayKey: 'gw-test' })
    expect(screen.queryByRole('status', { name: /rate limit status/i })).not.toBeInTheDocument()
  })

  it('hides the rate-limit strip when signed out even with a snapshot', () => {
    renderApp(<Layout />)
    act(() => {
      useRateLimitStore
        .getState()
        .setSnapshot({ dimension: 'RPM', limit: 60, remaining: 41, reset: 12, retryAfter: null })
    })
    expect(screen.queryByRole('status', { name: /rate limit status/i })).not.toBeInTheDocument()
  })

  it('shows the last observed snapshot once authenticated', () => {
    renderApp(<Layout />, { gatewayKey: 'gw-test' })
    act(() => {
      useRateLimitStore
        .getState()
        .setSnapshot({ dimension: 'RPM', limit: 60, remaining: 41, reset: 12, retryAfter: null })
    })
    const strip = screen.getByRole('status', { name: /rate limit status/i })
    expect(strip).toHaveTextContent('Remaining: 41')
  })

  it('clears the snapshot when the credential identity changes', () => {
    renderApp(<Layout />, { gatewayKey: 'gw-first' })
    act(() => {
      useRateLimitStore
        .getState()
        .setSnapshot({ dimension: 'RPM', limit: 60, remaining: 41, reset: 12, retryAfter: null })
    })
    expect(screen.getByRole('status', { name: /rate limit status/i })).toBeInTheDocument()
    act(() => {
      useAuthStore.getState().setGatewayKey('gw-second')
    })
    expect(screen.queryByRole('status', { name: /rate limit status/i })).not.toBeInTheDocument()
    expect(useRateLimitStore.getState().snapshot).toBeNull()
  })

  it('clears the snapshot on sign-out', () => {
    renderApp(<Layout />, { gatewayKey: 'gw-test' })
    act(() => {
      useRateLimitStore
        .getState()
        .setSnapshot({ dimension: 'RPM', limit: 60, remaining: 41, reset: 12, retryAfter: null })
    })
    expect(screen.getByRole('status', { name: /rate limit status/i })).toBeInTheDocument()
    act(() => {
      useAuthStore.getState().clear()
    })
    expect(screen.queryByRole('status', { name: /rate limit status/i })).not.toBeInTheDocument()
  })
})
