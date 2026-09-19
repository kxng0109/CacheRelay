import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { GatewayClient } from '../shared/api/client.js'
import { useAuthStore } from '../shared/auth/store.js'
import { useRateLimitStore } from '../shared/ratelimit/store.js'
import { server } from '../test/setup.js'
import { renderApp } from '../test/utils.js'
import { Layout, parseSidebar } from './layout.js'

describe('Layout', () => {
  beforeEach(() => {
    useRateLimitStore.getState().clear()
    window.localStorage.removeItem('cacherelay.sidebar')
    server.use(
      http.get('*/v1/admin/mcp/approvals/pending', () => HttpResponse.json({ approvals: [] })),
    )
  })
  it('renders the product header, nav, and skip link', () => {
    renderApp(<Layout />)
    expect(screen.getByText('CacheRelay')).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: /primary/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /skip to content/i })).toHaveAttribute('href', '#main')
  })

  it('toggles the theme on the document root', async () => {
    const user = userEvent.setup()
    renderApp(<Layout />)
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    await user.click(screen.getByRole('button', { name: /light theme/i }))
    expect(document.documentElement.classList.contains('dark')).toBe(false)
    await user.click(screen.getByRole('button', { name: /dark theme/i }))
    expect(document.documentElement.classList.contains('dark')).toBe(true)
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

  it('shows session identity and route in the shell bars', () => {
    renderApp(<Layout />, { gatewayKey: 'gw-test', adminSession: true })
    expect(screen.getByText(/cacherelay ·/i)).toBeInTheDocument()
    expect(screen.getByText('auth: gateway + admin')).toBeInTheDocument()
    expect(screen.getByText('route: Overview')).toBeInTheDocument()
    expect(screen.getByText('theme: dark')).toBeInTheDocument()
  })

  it('shows locked auth when signed out', () => {
    renderApp(<Layout />)
    expect(screen.getByText('auth: locked')).toBeInTheDocument()
  })

  it('falls back to the raw path on unknown routes', () => {
    renderApp(<Layout />, { route: '/nope' })
    expect(screen.getByText('route: /nope')).toBeInTheDocument()
  })

  it('shows a configured base instead of same-origin', () => {
    vi.stubEnv('VITE_API_BASE_URL', 'http://localhost:8080')
    renderApp(<Layout />)
    expect(screen.getByText(/cacherelay · http:\/\/localhost:8080/i)).toBeInTheDocument()
    vi.unstubAllEnvs()
  })

  it('names partial credentials honestly', () => {
    const { unmount } = renderApp(<Layout />, { gatewayKey: 'gw-test' })
    expect(screen.getByText('auth: gateway')).toBeInTheDocument()
    unmount()
    renderApp(<Layout />, { adminSession: true })
    expect(screen.getByText('auth: admin')).toBeInTheDocument()
  })

  it('reflects the light theme in the footer', async () => {
    const user = userEvent.setup()
    renderApp(<Layout />)
    await user.click(screen.getByRole('button', { name: /light theme/i }))
    expect(screen.getByText('theme: light')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /dark theme/i }))
    expect(screen.getByText('theme: dark')).toBeInTheDocument()
  })

  it('stops mirroring headers after unmount', async () => {
    server.use(
      http.get('*/v1/models', () =>
        HttpResponse.json(
          { data: [] },
          { headers: { 'X-RateLimit-Limit-RPM': '60', 'X-RateLimit-Remaining-RPM': '41' } },
        ),
      ),
    )
    const { unmount } = renderApp(<Layout />, { gatewayKey: 'gw-test' })
    unmount()
    await new GatewayClient({ base: '', token: 'gw-test' }).models()
    expect(useRateLimitStore.getState().snapshot).toBeNull()
  })

  it('groups all ten routes with Overview pinned first', () => {
    renderApp(<Layout />, { adminSession: true })
    expect(screen.getByText('Run')).toBeInTheDocument()
    expect(screen.getByText('Guard')).toBeInTheDocument()
    expect(screen.getByText('Inspect')).toBeInTheDocument()
    for (const label of [
      'Overview',
      'Playground',
      'Embeddings',
      'Circuits',
      'Approvals',
      'Cache & budgets',
      'Keys',
      'Ledger',
      'MCP',
      'Observability',
    ]) {
      expect(screen.getByRole('link', { name: new RegExp(`^${label}$`) })).toBeInTheDocument()
    }
  })

  it('hides admin routes from non-admins without a hint', () => {
    renderApp(<Layout />)
    for (const label of ['Circuits', 'Approvals', 'Cache & budgets', 'Keys', 'Ledger']) {
      expect(screen.queryByRole('link', { name: new RegExp(`^${label}$`) })).not.toBeInTheDocument()
    }
    expect(screen.getByRole('link', { name: /^Playground$/ })).toBeInTheDocument()
    expect(screen.queryByText('Guard')).not.toBeInTheDocument()
  })

  it('collapses to icons and persists the preference', async () => {
    const user = userEvent.setup()
    window.localStorage.removeItem('cacherelay.sidebar')
    renderApp(<Layout />)
    expect(screen.getByText('Playground')).toBeVisible()
    await user.click(screen.getByRole('button', { name: /collapse sidebar/i }))
    expect(screen.queryByText('Playground')).not.toBeInTheDocument()
    expect(window.localStorage.getItem('cacherelay.sidebar')).toBe('closed')
    expect(screen.getByRole('button', { name: /expand sidebar/i })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /expand sidebar/i }))
    expect(screen.getByText('Playground')).toBeVisible()
    expect(window.localStorage.getItem('cacherelay.sidebar')).toBe('open')
  })

  it('boots collapsed from a stored preference', () => {
    window.localStorage.setItem('cacherelay.sidebar', 'closed')
    renderApp(<Layout />)
    expect(screen.queryByText('Playground')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /expand sidebar/i })).toBeInTheDocument()
  })

  it('opens and closes the mobile drawer', async () => {
    const user = userEvent.setup()
    renderApp(<Layout />)
    await user.click(screen.getByRole('button', { name: /open navigation/i }))
    expect(screen.getByRole('button', { name: /close navigation/i })).toBeInTheDocument()
    await user.click(screen.getByRole('link', { name: /^Playground$/ }))
    expect(screen.queryByRole('button', { name: /close navigation/i })).not.toBeInTheDocument()
  })

  it('navigates to login from the sidebar', async () => {
    const user = userEvent.setup()
    renderApp(<Layout />)
    await user.click(screen.getByRole('link', { name: /log in/i }))
    expect(screen.getByRole('link', { name: /log in/i })).toHaveAttribute('href', '/login')
  })

  it('locks from the collapsed sidebar', async () => {
    const user = userEvent.setup()
    window.localStorage.setItem('cacherelay.sidebar', 'closed')
    server.use(http.post('*/v1/auth/logout', () => new HttpResponse(null, { status: 204 })))
    renderApp(<Layout />, { adminSession: true })
    await user.click(screen.getByRole('button', { name: /lock console/i }))
    await waitFor(() => {
      expect(useAuthStore.getState().session).toBeNull()
    })
    expect(screen.getByRole('link', { name: /log in/i })).toBeInTheDocument()
  })

  it('closes the drawer from its overlay without navigating', async () => {
    const user = userEvent.setup()
    renderApp(<Layout />)
    await user.click(screen.getByRole('button', { name: /open navigation/i }))
    await user.click(screen.getByRole('button', { name: /close navigation/i }))
    expect(screen.queryByRole('button', { name: /close navigation/i })).not.toBeInTheDocument()
  })

  it('keeps a session-only sidebar when writes are denied', async () => {
    const real = window.localStorage
    let reads = 0
    vi.stubGlobal('localStorage', {
      getItem: (key: string): string | null => {
        reads += 1
        if (reads === 1) throw new Error('denied')
        return real.getItem(key)
      },
      setItem: (): void => {
        throw new Error('denied')
      },
      removeItem: vi.fn(),
      clear: vi.fn(),
      key: (): null => null,
      length: 0,
    })
    try {
      renderApp(<Layout />)
      expect(screen.getByText('Playground')).toBeVisible()
      const user = userEvent.setup()
      await user.click(screen.getByRole('button', { name: /collapse sidebar/i }))
      expect(screen.queryByText('Playground')).not.toBeInTheDocument()
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('badges live pending approvals for admins', async () => {
    server.use(
      http.get('*/v1/admin/mcp/approvals/pending', () =>
        HttpResponse.json({ approvals: [{ id: 'a' }, { id: 'b' }] }),
      ),
    )
    renderApp(<Layout />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByLabelText('2 pending approvals')).toBeInTheDocument()
    })
  })

  it('shows no badge without an admin key', () => {
    renderApp(<Layout />)
    expect(screen.queryByLabelText(/pending approvals/i)).not.toBeInTheDocument()
  })

  it('offers login when logged out and lock when in session', () => {
    renderApp(<Layout />)
    expect(screen.getByRole('link', { name: /log in/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /lock/i })).not.toBeInTheDocument()
  })

  it('locks the console on demand', async () => {
    const user = userEvent.setup()
    server.use(http.post('*/v1/auth/logout', () => new HttpResponse(null, { status: 204 })))
    renderApp(<Layout />, { adminSession: true })
    await user.click(screen.getByRole('button', { name: /lock/i }))
    await waitFor(() => {
      expect(screen.getByRole('link', { name: /log in/i })).toBeInTheDocument()
    })
    expect(screen.queryByText('Guard')).not.toBeInTheDocument()
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

describe('parseSidebar', () => {
  it('passes the two allow-listed literals through', () => {
    expect(parseSidebar('open')).toBe('open')
    expect(parseSidebar('closed')).toBe('closed')
  })

  it('rejects everything else', () => {
    expect(parseSidebar(null)).toBeNull()
    expect(parseSidebar('')).toBeNull()
    expect(parseSidebar('OPEN')).toBeNull()
    expect(parseSidebar('__proto__')).toBeNull()
    expect(parseSidebar('<script>alert(1)</script>')).toBeNull()
    expect(parseSidebar(1)).toBeNull()
    expect(parseSidebar({ state: 'open' })).toBeNull()
  })
})
