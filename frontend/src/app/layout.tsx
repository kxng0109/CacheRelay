import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  Activity,
  BookOpen,
  Brain,
  ChevronsLeft,
  ChevronsRight,
  Database,
  FlaskConical,
  KeyRound,
  LayoutDashboard,
  Lock,
  Menu,
  Moon,
  Plug,
  ShieldCheck,
  Sun,
  Zap,
} from 'lucide-react'
import { NavLink, Outlet, useLocation } from 'react-router'
import { useShallow } from 'zustand/react/shallow'
import {
  GatewayClient,
  parseRateLimit,
  resolveApiBase,
  setHeadersReporter,
} from '../shared/api/client.js'
import { logout, startSessionHeartbeat } from '../shared/auth/session.js'
import { CommandPalette } from '../shared/components/CommandPalette.js'
import { RateLimitHeaders } from '../shared/components/RateLimitHeaders.js'
import { useAuthStore } from '../shared/auth/store.js'
import { useRateLimitStore } from '../shared/ratelimit/store.js'
import { useUiStore } from '../shared/store.js'
import type { LucideIcon } from 'lucide-react'

interface NavItem {
  to: string
  label: string
  icon: LucideIcon
  /** True for admin-only routes (hidden from non-admins, no hint). */
  admin: boolean
  badge?: (() => React.JSX.Element | null) | undefined
}

interface NavGroup {
  label: string | null
  items: NavItem[]
}

/** Storage key for the sidebar preference. Presentation state only. */
const SIDEBAR_KEY = 'cacherelay.sidebar'

type SidebarState = 'open' | 'closed'

/**
 * Validates an untrusted stored value against the sidebar allow-list.
 *
 * @remarks Same doctrine as the theme parser: only exact literals pass;
 * everything else falls back to open. Never reaches markup or auth paths.
 *
 * @param raw - Untrusted value from storage.
 * @returns The sidebar state, or null when the input is not exactly one.
 */
export function parseSidebar(raw: unknown): SidebarState | null {
  if (raw === 'open' || raw === 'closed') return raw
  return null
}

/**
 * Reads the persisted sidebar state without ever throwing.
 *
 * @returns Persisted state, or open when missing, invalid, or unreadable.
 */
function readStoredSidebar(): SidebarState {
  try {
    return parseSidebar(window.localStorage.getItem(SIDEBAR_KEY)) ?? 'open'
  } catch {
    return 'open'
  }
}

/**
 * Persists the sidebar state without ever throwing.
 *
 * @param state - State to persist.
 */
function writeStoredSidebar(state: SidebarState): void {
  try {
    window.localStorage.setItem(SIDEBAR_KEY, state)
  } catch {
    // Private mode: session-only preference. Never a crash.
  }
}

/**
 * Application shell: sidebar navigation, session strip, content outlet.
 *
 * @remarks
 * The shell owns the rate-limit strip: it registers the process-wide headers
 * reporter once (every feature page uses a short-lived client, so no
 * instance persists to carry the subscription) and renders the last observed
 * snapshot below the header. The strip stays hidden until a credential is
 * present and a gateway response has been observed; the snapshot clears
 * whenever the credential identity changes (key switch or sign-out) since
 * quota is identity-bound. Navigation is a grouped sidebar (Overview pinned,
 * Run/Guard/Inspect groups); the approvals badge shows the live pending
 * count only while an admin key is present.
 *
 * @returns The shell layout wrapping every route.
 */
export function Layout(): React.JSX.Element {
  const { dark, toggleDark } = useUiStore(
    useShallow((s) => ({ dark: s.dark, toggleDark: s.toggleDark })),
  )
  const { gatewayKey, session } = useAuthStore(
    useShallow((s) => ({ gatewayKey: s.gatewayKey, session: s.session })),
  )
  const snapshot = useRateLimitStore((s) => s.snapshot)
  const { pathname } = useLocation()
  const [collapsed, setCollapsed] = useState<boolean>(() => readStoredSidebar() === 'closed')
  const [drawer, setDrawer] = useState(false)
  const base = resolveApiBase()
  const authLabel =
    gatewayKey !== null && session !== null
      ? 'gateway + admin'
      : gatewayKey !== null
        ? 'gateway'
        : session !== null
          ? 'admin'
          : 'locked'

  const pending = useQuery({
    queryKey: ['approvals-badge'],
    queryFn: ({ signal }) => new GatewayClient().hitlPending({ signal }),
    enabled: session?.admin === true,
  })
  const pendingCount = pending.data?.approvals.length ?? 0

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
  }, [gatewayKey, session])

  useEffect(() => startSessionHeartbeat(), [])

  const toggleCollapsed = (): void => {
    setCollapsed((c) => {
      writeStoredSidebar(c ? 'open' : 'closed')
      return !c
    })
  }

  const groups: NavGroup[] = [
    { label: null, items: [{ to: '/', label: 'Overview', icon: LayoutDashboard, admin: false }] },
    {
      label: 'Run',
      items: [
        { to: '/playground', label: 'Playground', icon: FlaskConical, admin: false },
        { to: '/embeddings', label: 'Embeddings', icon: Brain, admin: false },
      ],
    },
    {
      label: 'Guard',
      items: [
        { to: '/circuits', label: 'Circuits', icon: Zap, admin: true },
        {
          to: '/approvals',
          label: 'Approvals',
          icon: ShieldCheck,
          admin: true,
          badge:
            pendingCount > 0
              ? () => (
                  <span
                    aria-label={`${String(pendingCount)} pending approvals`}
                    className="rounded-full bg-warn/20 px-2 py-0.5 font-mono text-[11px] text-warn tnum dark:text-warn-soft"
                  >
                    {pendingCount}
                  </span>
                )
              : undefined,
        },
        { to: '/cache', label: 'Cache & budgets', icon: Database, admin: true },
        { to: '/keys', label: 'Keys', icon: KeyRound, admin: true },
      ],
    },
    {
      label: 'Inspect',
      items: [
        { to: '/ledger', label: 'Ledger', icon: BookOpen, admin: true },
        { to: '/mcp', label: 'MCP', icon: Plug, admin: false },
        { to: '/observability', label: 'Observability', icon: Activity, admin: false },
      ],
    },
  ]
  const isAdmin = session?.admin === true
  const visibleGroups = groups
    .map((g) => ({ ...g, items: g.items.filter((i) => isAdmin || !i.admin) }))
    .filter((g) => g.items.length > 0)

  const routeLabel =
    pathname === '/'
      ? 'Overview'
      : (visibleGroups.flatMap((g) => g.items).find((item) => item.to === pathname)?.label ??
        pathname)

  const sidebarBody = (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-2 p-4">
        <span aria-hidden="true" className="inline-block size-3 shrink-0 rounded-sm bg-ember" />
        {collapsed ? null : (
          <p className="font-display text-lg font-medium tracking-tight">CacheRelay</p>
        )}
        <span className="flex-1" />
        <button
          type="button"
          onClick={toggleCollapsed}
          aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          className="hidden rounded-md p-2 text-ink-soft lg:block dark:text-parchment-soft"
        >
          {collapsed ? <ChevronsRight size={16} /> : <ChevronsLeft size={16} />}
        </button>
      </div>
      <nav aria-label="Primary" className="flex-1 space-y-4 overflow-y-auto px-2">
        {visibleGroups.map((group) => (
          <div key={group.label ?? 'home'}>
            {group.label === null || collapsed ? null : (
              <p className="px-2 pb-1 font-mono text-[11px] text-ink-soft dark:text-parchment-soft">
                {group.label}
              </p>
            )}
            <ul className="space-y-0.5">
              {group.items.map((item) => {
                const Badge = item.badge
                const showBadge = !collapsed && Badge !== undefined
                return (
                  <li key={item.to}>
                    <NavLink
                      to={item.to}
                      onClick={() => {
                        setDrawer(false)
                      }}
                      title={collapsed ? item.label : undefined}
                      className={({ isActive }) =>
                        `flex items-center gap-2 rounded-md px-2 py-2 text-xs whitespace-nowrap ${
                          isActive
                            ? 'bg-ink/[0.06] font-medium text-ink dark:bg-parchment/[0.08] dark:text-parchment'
                            : 'text-ink-soft hover:text-ink dark:text-parchment-soft dark:hover:text-parchment'
                        }`
                      }
                    >
                      <item.icon size={16} aria-hidden="true" className="shrink-0" />
                      {collapsed ? null : <span>{item.label}</span>}
                      {showBadge ? <span className="flex-1" /> : null}
                      {showBadge ? <Badge /> : null}
                    </NavLink>
                  </li>
                )
              })}
            </ul>
          </div>
        ))}
      </nav>
      <div className="space-y-2 border-t border-ink/10 p-4 dark:border-parchment/10">
        {session === null ? (
          <NavLink
            to="/login"
            aria-label={collapsed ? 'Log in' : undefined}
            onClick={() => {
              setDrawer(false)
            }}
            className="flex w-full items-center gap-2 rounded-md p-2 text-xs"
          >
            <KeyRound size={16} aria-hidden="true" />
            {collapsed ? null : <span>Log in</span>}
          </NavLink>
        ) : (
          <button
            type="button"
            onClick={() => {
              setDrawer(false)
              void logout()
            }}
            aria-label={`Lock console (signed in as ${session.username})`}
            className="flex w-full items-center gap-2 rounded-md p-2 text-xs"
          >
            <Lock size={16} aria-hidden="true" />
            {collapsed ? null : <span>Lock</span>}
          </button>
        )}
        <button
          type="button"
          onClick={toggleDark}
          aria-label={collapsed ? (dark ? 'Light theme' : 'Dark theme') : undefined}
          className="flex w-full items-center gap-2 rounded-md p-2 text-xs"
        >
          {dark ? <Sun size={16} aria-hidden="true" /> : <Moon size={16} aria-hidden="true" />}
          {collapsed ? null : <span>{dark ? 'Light theme' : 'Dark theme'}</span>}
        </button>
      </div>
    </div>
  )

  return (
    <div className="min-h-screen bg-paper text-ink lg:flex dark:bg-night dark:text-parchment">
      <a href="#main" className="skip-link">
        Skip to content
      </a>
      {drawer ? (
        <button
          type="button"
          aria-label="Close navigation"
          onClick={() => {
            setDrawer(false)
          }}
          className="fixed inset-0 z-40 bg-night/60 lg:hidden"
        />
      ) : null}
      <aside
        aria-label="Console navigation"
        className={`fixed inset-y-0 left-0 z-40 flex w-64 flex-col border-r border-ink/10 bg-paper transition-transform dark:border-parchment/10 dark:bg-night ${
          drawer ? 'translate-x-0' : '-translate-x-full'
        } lg:sticky lg:top-0 lg:z-auto lg:h-screen lg:shrink-0 lg:translate-x-0 ${
          collapsed ? 'lg:w-16' : 'lg:w-64'
        }`}
      >
        {sidebarBody}
      </aside>
      <div className="flex min-h-screen min-w-0 flex-1 flex-col">
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
          <div className="mx-auto flex max-w-6xl items-center gap-2 px-4 py-2">
            <button
              type="button"
              onClick={() => {
                setDrawer(true)
              }}
              aria-label="Open navigation"
              className="rounded-md p-2 lg:hidden"
            >
              <Menu size={18} aria-hidden="true" />
            </button>
            <p className="font-mono text-[11px] text-ink-soft lg:hidden dark:text-parchment-soft">
              {routeLabel}
            </p>
            <span className="flex-1" />
            <CommandPalette />
          </div>
          <p className="sr-only">Enterprise AI gateway console</p>
        </header>
        {snapshot !== null && (gatewayKey !== null || session !== null) ? (
          <div className="mx-auto w-full max-w-6xl px-4 pt-4">
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
    </div>
  )
}
