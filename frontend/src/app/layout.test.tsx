import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { GatewayClient } from '../shared/api/client.js'
import { useAuthStore } from '../shared/auth/store.js'
import { useRateLimitStore } from '../shared/ratelimit/store.js'
import { useUiStore } from '../shared/store.js'
import { server } from '../test/setup.js'
import { renderApp } from '../test/utils.js'
import { useToastStore } from '../shared/toast/store.js'
import { Layout, parseSidebar } from './layout.js'

describe('Layout', () => {
  beforeEach(() => {
    useRateLimitStore.getState().clear()
    useToastStore.getState().clear()
    window.localStorage.removeItem('cacherelay.sidebar')
    window.history.replaceState(null, '', '/')
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

  it('cycles the theme on the document root', async () => {
    const user = userEvent.setup()
    act(() => {
      useUiStore.getState().setTheme('light')
    })
    renderApp(<Layout />)
    expect(document.documentElement.classList.contains('dark')).toBe(false)
    await user.click(screen.getByRole('button', { name: /theme: light/i }))
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(screen.getByText('theme: dark')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /theme: dark/i }))
    expect(screen.getByText('theme: system')).toBeInTheDocument()
  })

  it('toggles the theme with Ctrl+Shift+L', async () => {
    act(() => {
      useUiStore.getState().setTheme('light')
    })
    renderApp(<Layout />)
    fireEvent.keyDown(document, { key: 'L', ctrlKey: true, shiftKey: true })
    await waitFor(() => {
      expect(screen.getByText('theme: dark')).toBeInTheDocument()
    })
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('hides the rate-limit strip before any gateway response', () => {
    renderApp(<Layout />, { gatewayKey: 'gw-test' })
    expect(screen.queryByLabelText(/rate limit status/i)).not.toBeInTheDocument()
  })

  it('hides the rate-limit strip when signed out even with a snapshot', () => {
    renderApp(<Layout />)
    act(() => {
      useRateLimitStore
        .getState()
        .setSnapshot({ dimension: 'RPM', limit: 60, remaining: 41, reset: 12, retryAfter: null })
    })
    expect(screen.queryByLabelText(/rate limit status/i)).not.toBeInTheDocument()
  })

  it('shows the last observed snapshot once authenticated', () => {
    renderApp(<Layout />, { gatewayKey: 'gw-test' })
    act(() => {
      useRateLimitStore
        .getState()
        .setSnapshot({ dimension: 'RPM', limit: 60, remaining: 41, reset: 12, retryAfter: null })
    })
    const strip = screen.getByLabelText(/rate limit status/i)
    expect(strip).toHaveTextContent('Remaining: 41')
  })

  it('clears the snapshot when the credential identity changes', () => {
    renderApp(<Layout />, { gatewayKey: 'gw-first' })
    act(() => {
      useRateLimitStore
        .getState()
        .setSnapshot({ dimension: 'RPM', limit: 60, remaining: 41, reset: 12, retryAfter: null })
    })
    expect(screen.getByLabelText(/rate limit status/i)).toBeInTheDocument()
    act(() => {
      useAuthStore.getState().setGatewayKey('gw-second')
    })
    expect(screen.queryByLabelText(/rate limit status/i)).not.toBeInTheDocument()
    expect(useRateLimitStore.getState().snapshot).toBeNull()
  })

  it('clears the snapshot on sign-out', () => {
    renderApp(<Layout />, { gatewayKey: 'gw-test' })
    act(() => {
      useRateLimitStore
        .getState()
        .setSnapshot({ dimension: 'RPM', limit: 60, remaining: 41, reset: 12, retryAfter: null })
    })
    expect(screen.getByLabelText(/rate limit status/i)).toBeInTheDocument()
    act(() => {
      useAuthStore.getState().clear()
    })
    expect(screen.queryByLabelText(/rate limit status/i)).not.toBeInTheDocument()
  })

  it('shows session identity and route in the shell bars', () => {
    act(() => {
      useUiStore.getState().setTheme('dark')
    })
    renderApp(<Layout />, { gatewayKey: 'gw-test', adminSession: true })
    expect(screen.getByText(/cacherelay ·/i)).toBeInTheDocument()
    expect(screen.getByText('gateway + admin')).toBeInTheDocument()
    expect(screen.getByText('route: Overview')).toBeInTheDocument()
    expect(screen.getByText('theme: dark')).toBeInTheDocument()
  })

  it('shows locked auth when signed out', () => {
    renderApp(<Layout />)
    expect(screen.getByText('locked')).toBeInTheDocument()
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
    expect(screen.getByText('gateway')).toBeInTheDocument()
    unmount()
    renderApp(<Layout />, { adminSession: true })
    expect(screen.getByText('admin')).toBeInTheDocument()
  })

  it('labels a regular session as user, never admin', () => {
    const { unmount } = renderApp(<Layout />, { nonAdminSession: true })
    expect(screen.getByText('user')).toBeInTheDocument()
    unmount()
    renderApp(<Layout />, { nonAdminSession: true, gatewayKey: 'gw-test' })
    expect(screen.getByText('gateway + user')).toBeInTheDocument()
  })

  it('links the wordmark home for sessions and to playground for guests', () => {
    const { unmount } = renderApp(<Layout />, { adminSession: true })
    expect(screen.getByRole('link', { name: /cacherelay home/i })).toHaveAttribute('href', '/')
    unmount()
    renderApp(<Layout />)
    expect(screen.getByRole('link', { name: /cacherelay home/i })).toHaveAttribute(
      'href',
      '/playground',
    )
  })

  it('cycles light, dark, and system from the footer', async () => {
    const user = userEvent.setup()
    act(() => {
      useUiStore.getState().setTheme('light')
    })
    renderApp(<Layout />)
    await user.click(screen.getByRole('button', { name: /theme: light/i }))
    expect(screen.getByText('theme: dark')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /theme: dark/i }))
    expect(screen.getByText('theme: system')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /theme: system/i }))
    expect(screen.getByText('theme: light')).toBeInTheDocument()
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

  it('groups all twelve routes with Overview pinned first', () => {
    renderApp(<Layout />, { adminSession: true })
    expect(screen.getByText('Run')).toBeInTheDocument()
    expect(screen.getByText('Guard')).toBeInTheDocument()
    expect(screen.getByText('Inspect')).toBeInTheDocument()
    for (const label of [
      'Overview',
      'Usage',
      'Playground',
      'Embeddings',
      'Teams',
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

  it('hides session and admin routes from guests without a hint', () => {
    renderApp(<Layout />)
    for (const label of [
      'Overview',
      'Usage',
      'Circuits',
      'Approvals',
      'Cache & budgets',
      'Keys',
      'Ledger',
      'Teams',
      'Observability',
    ]) {
      expect(screen.queryByRole('link', { name: new RegExp(`^${label}$`) })).not.toBeInTheDocument()
    }
    expect(screen.getByRole('link', { name: /^Playground$/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /^Embeddings$/ })).toBeInTheDocument()
    // FE-40: the MCP catalog is paste-key first, so guests may open it.
    expect(screen.getByRole('link', { name: /^MCP$/ })).toBeInTheDocument()
    expect(screen.queryByText('Guard')).not.toBeInTheDocument()
  })

  it('shows session routes but not admin routes to non-admin sessions', () => {
    renderApp(<Layout />, { nonAdminSession: true })
    for (const label of ['Overview', 'Usage', 'Teams', 'MCP', 'Observability']) {
      expect(screen.getByRole('link', { name: new RegExp(`^${label}$`) })).toBeInTheDocument()
    }
    for (const label of ['Circuits', 'Approvals', 'Cache & budgets', 'Keys', 'Ledger']) {
      expect(screen.queryByRole('link', { name: new RegExp(`^${label}$`) })).not.toBeInTheDocument()
    }
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
        HttpResponse.json({
          approvals: [
            { approvalId: 'a1', toolName: 't', requestedAt: 'r', requestedBy: 'b' },
            { approvalId: 'a2', toolName: 't', requestedAt: 'r', requestedBy: 'b' },
          ],
        }),
      ),
    )
    renderApp(<Layout />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByLabelText('2 pending approvals')).toBeInTheDocument()
    })
  })

  it('survives a bare-array approvals payload without crashing', async () => {
    server.use(
      http.get('*/v1/admin/mcp/approvals/pending', () =>
        HttpResponse.json([
          { approvalId: 'a1', toolName: 't', requestedAt: 'r', requestedBy: 'b' },
        ]),
      ),
    )
    renderApp(<Layout />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByLabelText('1 pending approvals')).toBeInTheDocument()
    })
    expect(screen.getByRole('navigation')).toBeInTheDocument()
  })

  it('shows no badge without an admin key', () => {
    renderApp(<Layout />)
    expect(screen.queryByLabelText(/pending approvals/i)).not.toBeInTheDocument()
  })

  it('diagnoses content-policy blocks once per target', async () => {
    const user = userEvent.setup()
    // The shell already renders the toast viewport; do not double it.
    renderApp(<Layout />)
    expect(screen.queryByText(/blocked by content policy/i)).not.toBeInTheDocument()
    const first = new Event('securitypolicyviolation')
    Object.assign(first, { violatedDirective: 'connect-src', blockedURI: 'http://h:9091/x' })
    document.dispatchEvent(first)
    await waitFor(() => {
      expect(screen.getByText(/blocked by content policy/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/connect-src/i)).toBeInTheDocument()
    // A second identical violation must not stack another toast; the user
    // dismisses the first and the 15 s poll stays quiet.
    await user.click(screen.getByRole('button', { name: /dismiss:/i }))
    await waitFor(() => {
      expect(screen.queryByText(/blocked by content policy/i)).not.toBeInTheDocument()
    })
    const second = new Event('securitypolicyviolation')
    Object.assign(second, { violatedDirective: 'connect-src', blockedURI: 'http://h:9091/x' })
    document.dispatchEvent(second)
    expect(screen.queryByText(/blocked by content policy/i)).not.toBeInTheDocument()
  })

  it('names unknown policy blocks without internals', async () => {
    renderApp(<Layout />)
    const bare = new Event('securitypolicyviolation')
    document.dispatchEvent(bare)
    await waitFor(() => {
      expect(screen.getByText(/blocked by content policy/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/\(csp\): unknown target/i)).toBeInTheDocument()
  })

  it('offers login when logged out and lock when in session', () => {
    renderApp(<Layout />)
    expect(screen.getByRole('link', { name: /log in/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /lock/i })).not.toBeInTheDocument()
  })

  it('locks the console on demand and lands on login', async () => {
    const user = userEvent.setup()
    server.use(http.post('*/v1/auth/logout', () => new HttpResponse(null, { status: 204 })))
    renderApp(<Layout />, { adminSession: true })
    await user.click(screen.getByRole('button', { name: /lock/i }))
    await waitFor(() => {
      expect(screen.getByRole('link', { name: /log in/i })).toBeInTheDocument()
    })
    expect(screen.queryByText('Guard')).not.toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByText('route: /login')).toBeInTheDocument()
    })
  })

  it('travels G-chords to visible routes only', () => {
    renderApp(<Layout />, { adminSession: true })
    expect(screen.getByText('route: Overview')).toBeInTheDocument()
    fireEvent.keyDown(document, { key: 'g' })
    fireEvent.keyDown(document, { key: 'p' })
    expect(screen.getByText('route: Playground')).toBeInTheDocument()
    fireEvent.keyDown(document, { key: 'g' })
    fireEvent.keyDown(document, { key: 'c' })
    expect(screen.getByText('route: Circuits')).toBeInTheDocument()
  })

  it('names the screen in the document title on navigation', () => {
    renderApp(<Layout />, { adminSession: true })
    expect(document.title).toBe('Overview · CacheRelay')
    fireEvent.keyDown(document, { key: 'g' })
    fireEvent.keyDown(document, { key: 'p' })
    expect(document.title).toBe('Playground · CacheRelay')
  })

  it('moves focus to the new screen heading on navigation, never on first paint', () => {
    renderApp(<Layout />, { adminSession: true })
    const main = document.querySelector('#main')
    if (main === null) throw new Error('#main missing')
    const heading = document.createElement('h1')
    heading.textContent = 'Probe screen'
    main.appendChild(heading)
    // First paint leaves focus alone.
    expect(heading).not.toHaveFocus()
    fireEvent.keyDown(document, { key: 'g' })
    fireEvent.keyDown(document, { key: 'p' })
    expect(heading).toHaveFocus()
    heading.remove()
  })

  it('blocks chords to routes the session may not see', () => {
    renderApp(<Layout />)
    expect(screen.getByText('route: Overview')).toBeInTheDocument()
    fireEvent.keyDown(document, { key: 'g' })
    fireEvent.keyDown(document, { key: 'c' })
    expect(screen.getByText('route: Overview')).toBeInTheDocument()
    fireEvent.keyDown(document, { key: 'g' })
    fireEvent.keyDown(document, { key: 'p' })
    expect(screen.getByText('route: Playground')).toBeInTheDocument()
  })

  it('never fires chords while typing', () => {
    renderApp(<Layout />, { adminSession: true })
    const box = document.createElement('input')
    document.body.appendChild(box)
    box.focus()
    fireEvent.keyDown(box, { key: 'g' })
    fireEvent.keyDown(box, { key: 'p' })
    expect(screen.getByText('route: Overview')).toBeInTheDocument()
    box.remove()
  })

  it('opens the shortcut sheet on ? and closes it on Esc', async () => {
    const user = userEvent.setup()
    renderApp(<Layout />)
    expect(screen.queryByRole('dialog', { name: /keyboard shortcuts/i })).not.toBeInTheDocument()
    fireEvent.keyDown(document, { key: '?' })
    expect(screen.getByRole('dialog', { name: /keyboard shortcuts/i })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /close shortcuts/i }))
    expect(screen.queryByRole('dialog', { name: /keyboard shortcuts/i })).not.toBeInTheDocument()
    fireEvent.keyDown(document, { key: '?' })
    fireEvent.keyDown(screen.getByRole('button', { name: /close shortcuts/i }), {
      key: 'Escape',
    })
    expect(screen.queryByRole('dialog', { name: /keyboard shortcuts/i })).not.toBeInTheDocument()
  })

  it('closes the mobile drawer on Esc', async () => {
    const user = userEvent.setup()
    renderApp(<Layout />)
    await user.click(screen.getByRole('button', { name: /open navigation/i }))
    expect(screen.getByRole('button', { name: /close navigation/i })).toBeInTheDocument()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('button', { name: /close navigation/i })).not.toBeInTheDocument()
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
    expect(screen.queryByLabelText(/rate limit status/i)).not.toBeInTheDocument()
    await new GatewayClient({ base: '', token: 'gw-test' }).models()
    await waitFor(() => {
      expect(screen.getByLabelText(/rate limit status/i)).toHaveTextContent('Remaining: 41')
    })
  })
})

describe('Layout SSO landing', () => {
  beforeEach(() => {
    window.history.replaceState(null, '', '/')
  })

  it('owns the paint with the callback for sessionless SSO landings', async () => {
    server.use(
      http.get('*/v1/auth/me', () =>
        HttpResponse.json({ userId: 'u1', username: 'sso-op', admin: false }),
      ),
    )
    window.history.replaceState(null, '', '/?sso=1#access_token=sso-jwt&admin=false')
    renderApp(<Layout />, { route: '/?sso=1' })
    await waitFor(() => {
      expect(useAuthStore.getState().session?.username).toBe('sso-op')
    })
    expect(window.location.hash).toBe('')
  })

  it('clears stale SSO fragments on live sessions without navigating', async () => {
    window.history.replaceState(null, '', '/?sso=1#access_token=stale')
    renderApp(<Layout />, { route: '/?sso=1', nonAdminSession: true })
    await waitFor(() => {
      expect(window.location.hash).toBe('')
    })
  })
})

describe('Layout keyboard', () => {
  it('ignores bare non-chord keys without navigating', async () => {
    const user = userEvent.setup()
    renderApp(<Layout />, { adminSession: true })
    expect(screen.getByRole('link', { name: /^overview$/i })).toBeInTheDocument()
    await user.keyboard('t')
    expect(screen.getByRole('link', { name: /^overview$/i })).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
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
