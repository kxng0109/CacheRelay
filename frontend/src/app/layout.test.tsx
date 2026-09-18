import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { GatewayClient } from '../shared/api/client.js'
import { useAuthStore } from '../shared/auth/store.js'
import { useRateLimitStore } from '../shared/ratelimit/store.js'
import { server } from '../test/setup.js'
import { renderApp } from '../test/utils.js'
import { Layout, computeNavTabIndex } from './layout.js'

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

  it('keeps the nav out of the tab order when nothing overflows', () => {
    renderApp(<Layout />)
    expect(screen.getByRole('navigation', { name: /primary/i })).toHaveAttribute('tabindex', '-1')
  })

  it('makes the nav keyboard-scrollable only while overflowing', () => {
    renderApp(<Layout />)
    const nav = screen.getByRole('navigation', { name: /primary/i })
    Object.defineProperty(nav, 'scrollWidth', { value: 1200, configurable: true })
    Object.defineProperty(nav, 'clientWidth', { value: 800, configurable: true })
    act(() => {
      window.dispatchEvent(new Event('resize'))
    })
    expect(nav).toHaveAttribute('tabindex', '0')
  })

  it('mirrors live gateway headers into the strip', async () => {
    server.use(
      http.get('*/v1/models', () =>
        HttpResponse.json(
          { data: [] },
          { headers: { 'X-RateLimit-Limit-RPM': '60', 'X-RateLimit-Remaining-RPM': '41' } },
        ),
      ),
    )
    renderApp(<Layout />, { gatewayKey: 'gw-test' })
    expect(screen.queryByRole('status', { name: /rate limit status/i })).not.toBeInTheDocument()
    await new GatewayClient({ base: '', token: 'gw-test' }).models()
    await waitFor(() => {
      expect(screen.getByRole('status', { name: /rate limit status/i })).toHaveTextContent(
        'Remaining: 41',
      )
    })
  })
})

describe('computeNavTabIndex', () => {
  it('focuses only on real overflow', () => {
    expect(computeNavTabIndex(1200, 800)).toBe(0)
    expect(computeNavTabIndex(800, 800)).toBe(-1)
    expect(computeNavTabIndex(400, 800)).toBe(-1)
  })
})
