import { useEffect } from 'react'
import { NavLink, Outlet } from 'react-router'
import { useShallow } from 'zustand/react/shallow'
import { parseRateLimit, setHeadersReporter } from '../shared/api/client.js'
import { CommandPalette } from '../shared/components/CommandPalette.js'
import { RateLimitHeaders } from '../shared/components/RateLimitHeaders.js'
import { useAuthStore } from '../shared/auth/store.js'
import { useRateLimitStore } from '../shared/ratelimit/store.js'
import { useUiStore } from '../shared/store.js'

const NAV = [
  { to: '/', label: 'Playground' },
  { to: '/circuits', label: 'Circuits' },
  { to: '/keys', label: 'Keys' },
  { to: '/ledger', label: 'Ledger' },
  { to: '/cache', label: 'Cache & budgets' },
  { to: '/embeddings', label: 'Embeddings' },
  { to: '/approvals', label: 'Approvals' },
  { to: '/mcp', label: 'MCP' },
  { to: '/observability', label: 'Observability' },
] as const

/**
 * Application shell: skip link, product header, primary nav, content outlet.
 *
 * @remarks
 * The shell owns the rate-limit strip: it registers the process-wide headers
 * reporter once (every feature page uses a short-lived client, so no
 * instance persists to carry the subscription) and renders the last observed
 * snapshot below the header. The strip stays hidden until a credential is
 * present and a gateway response has been observed; the snapshot clears
 * whenever the credential identity changes (key switch or sign-out) since
 * quota is identity-bound.
 *
 * @returns The shell layout wrapping every route.
 */
export function Layout(): React.JSX.Element {
  const { dark, toggleDark } = useUiStore(
    useShallow((s) => ({ dark: s.dark, toggleDark: s.toggleDark })),
  )
  const { gatewayKey, adminKey } = useAuthStore(
    useShallow((s) => ({ gatewayKey: s.gatewayKey, adminKey: s.adminKey })),
  )
  const snapshot = useRateLimitStore((s) => s.snapshot)

  useEffect(() => {
    setHeadersReporter((headers, code) => {
      useRateLimitStore.getState().setSnapshot(parseRateLimit(headers, code))
    })
    return () => {
      setHeadersReporter(null)
    }
  }, [])

  useEffect(() => {
    useRateLimitStore.getState().clear()
  }, [gatewayKey, adminKey])
  return (
    <div className="min-h-screen bg-paper text-ink dark:bg-night dark:text-parchment">
      <a href="#main" className="skip-link">
        Skip to content
      </a>
      <header className="border-b border-ink/10 dark:border-parchment/10">
        <div className="mx-auto flex max-w-6xl items-center gap-4 px-4 py-3">
          <span aria-hidden="true" className="inline-block size-3 rounded-sm bg-ember" />
          <p className="text-sm font-semibold tracking-tight">CacheRelay</p>
          <p className="hidden text-xs text-ink-soft sm:block dark:text-parchment-soft">
            Enterprise AI gateway console
          </p>
          <span className="flex-1" />
          <CommandPalette />
          <button
            type="button"
            onClick={toggleDark}
            aria-pressed={dark}
            className="rounded-md border border-ink/15 px-3 py-2 text-xs dark:border-parchment/15"
          >
            {dark ? 'Light theme' : 'Dark theme'}
          </button>
        </div>
        <nav aria-label="Primary" className="mx-auto max-w-6xl overflow-x-auto px-4 pb-3">
          <ul className="flex gap-1">
            {NAV.map((item) => (
              <li key={item.to}>
                <NavLink
                  to={item.to}
                  className={({ isActive }) =>
                    `rounded-md px-3 py-2 text-xs whitespace-nowrap ${
                      isActive ? 'bg-ink text-paper dark:bg-parchment dark:text-night' : ''
                    }`
                  }
                >
                  {item.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
      </header>
      {snapshot !== null && (gatewayKey !== null || adminKey !== null) ? (
        <div className="mx-auto max-w-6xl px-4 pt-4">
          <RateLimitHeaders snapshot={snapshot} />
        </div>
      ) : null}
      <main id="main" className="mx-auto max-w-6xl px-4 py-6">
        <Outlet />
      </main>
    </div>
  )
}
