import { useEffect, useRef, useState } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router'
import { useShallow } from 'zustand/react/shallow'
import { parseRateLimit, resolveApiBase, setHeadersReporter } from '../shared/api/client.js'
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
 * Decides whether the primary nav belongs in the tab order.
 *
 * @remarks Pure overflow math, extracted for unit tests: the nav is
 * keyboard-focusable only while its content actually overflows.
 *
 * @param scrollWidth - Full scrollable width of the nav.
 * @param clientWidth - Visible width of the nav.
 * @returns `0` when overflowing, `-1` otherwise.
 */
export function computeNavTabIndex(scrollWidth: number, clientWidth: number): -1 | 0 {
  return scrollWidth > clientWidth ? 0 : -1
}

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
  const { pathname } = useLocation()
  const routeLabel = NAV.find((item) => item.to === pathname)?.label ?? pathname
  const base = resolveApiBase()
  const authLabel =
    gatewayKey !== null && adminKey !== null
      ? 'gateway + admin'
      : gatewayKey !== null
        ? 'gateway'
        : adminKey !== null
          ? 'admin'
          : 'locked'

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

  // Keyboard scrolling for the primary nav: the list overflows horizontally
  // on narrow viewports, and WebKit does not scroll overflow regions by
  // keyboard unless the region itself is focusable (see axe
  // `scrollable-region-focusable`, WCAG 2.2 SC 2.1.1). The nav stays out of
  // the tab order (`-1`) unless content actually overflows — no focus-order
  // bloat on wide screens. The landmark keeps its accessible name.
  const navRef = useRef<HTMLElement | null>(null)
  const [navTabIndex, setNavTabIndex] = useState<-1 | 0>(-1)
  useEffect(() => {
    const nav = navRef.current
    // React attaches refs before effects run, so the nav is never null here.
    /* v8 ignore if -- @preserve */
    if (nav === null) return
    const update = (): void => {
      setNavTabIndex(computeNavTabIndex(nav.scrollWidth, nav.clientWidth))
    }
    update()
    const observer = new ResizeObserver(update)
    observer.observe(nav)
    window.addEventListener('resize', update)
    return () => {
      window.removeEventListener('resize', update)
      observer.disconnect()
    }
  }, [])
  return (
    <div className="flex min-h-screen flex-col bg-paper text-ink dark:bg-night dark:text-parchment">
      <a href="#main" className="skip-link">
        Skip to content
      </a>
      <header className="border-b border-ink/10 dark:border-parchment/10">
        <div className="border-b border-ink/10 dark:border-parchment/10">
          <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-1">
            <p className="font-mono text-[11px] text-ink-soft dark:text-parchment-soft">
              cacherelay · {base || 'same-origin'}
            </p>
            <p className="font-mono text-[11px] text-ink-soft tnum dark:text-parchment-soft">
              auth: {authLabel}
            </p>
          </div>
        </div>
        <div className="mx-auto flex max-w-6xl items-center gap-4 px-4 py-3">
          <span aria-hidden="true" className="inline-block size-3 rounded-sm bg-ember" />
          <p className="font-display text-lg font-medium tracking-tight">CacheRelay</p>
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
        <nav
          ref={navRef}
          aria-label="Primary"
          tabIndex={navTabIndex}
          className="mx-auto max-w-6xl overflow-x-auto px-4 pb-3"
        >
          <ul className="flex gap-1">
            {NAV.map((item) => (
              <li key={item.to}>
                <NavLink
                  to={item.to}
                  className={({ isActive }) =>
                    `rounded-md px-3 py-2 text-xs whitespace-nowrap text-ink-soft underline-offset-8 hover:text-ink dark:text-parchment-soft dark:hover:text-parchment ${
                      isActive
                        ? 'font-medium text-ink underline decoration-ember decoration-2 dark:text-parchment'
                        : ''
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
      <main id="main" className="mx-auto w-full max-w-6xl flex-1 px-4 py-6">
        <Outlet />
      </main>
      <footer className="border-t border-ink/10 dark:border-parchment/10">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-1">
          <p className="font-mono text-[11px] text-ink-soft dark:text-parchment-soft">
            route: {routeLabel}
          </p>
          <p className="font-mono text-[11px] text-ink-soft dark:text-parchment-soft">
            theme: {dark ? 'dark' : 'light'}
          </p>
        </div>
      </footer>
    </div>
  )
}
