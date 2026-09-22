import { useEffect, useMemo, useRef, useState } from 'react'
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
  Layers,
  LayoutDashboard,
  Lock,
  Menu,
  Moon,
  Plug,
  ShieldCheck,
  Sun,
  Zap,
} from 'lucide-react'
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router'
import { useShallow } from 'zustand/react/shallow'
import {
  GatewayClient,
  parseRateLimit,
  resolveApiBase,
  setHeadersReporter,
} from '../shared/api/client.js'
import { logout, startSessionHeartbeat } from '../shared/auth/session.js'
import { CommandPalette } from '../shared/components/CommandPalette.js'
import { CacheRelayMark } from '../shared/components/CacheRelayMark.js'
import { RateLimitHeaders } from '../shared/components/RateLimitHeaders.js'
import { ShortcutSheet } from '../shared/components/ShortcutSheet.js'
import { Toasts } from '../shared/components/Toasts.js'
import { useAuthStore } from '../shared/auth/store.js'
import { useRateLimitStore } from '../shared/ratelimit/store.js'
import { useUiStore } from '../shared/store.js'
import type { LucideIcon } from 'lucide-react'
import { CHORD_WINDOW_MS, isEditable, targetForChord } from './shortcuts.js'

/** Who may see a nav item: everyone, any live session, or admins only. */
type Audience = 'public' | 'session' | 'admin'

interface NavItem {
  to: string
  label: string
  icon: LucideIcon
  /** Visibility tier (guests see public items only, no hint of the rest). */
  audience: Audience
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
  const location = useLocation()
  const { pathname } = location
  const navigate = useNavigate()
  const [collapsed, setCollapsed] = useState<boolean>(() => readStoredSidebar() === 'closed')
  const [drawer, setDrawer] = useState(false)
  const [sheetOpen, setSheetOpen] = useState(false)
  const chordAt = useRef(0)
  const base = resolveApiBase()
  // The status bar must never upgrade a regular session to "admin".
  const role = session === null ? null : session.admin ? 'admin' : 'user'
  const authLabel =
    role === null
      ? gatewayKey !== null
        ? 'gateway'
        : 'locked'
      : gatewayKey !== null
        ? `gateway + ${role}`
        : role

  const pending = useQuery({
    queryKey: ['approvals-badge'],
    queryFn: ({ signal }) => new GatewayClient().hitlPending({ signal }),
    enabled: session?.admin === true,
  })
  /**
   * Pending approvals tolerant of wire shape drift. The contract promises
   * `{ approvals: [] }`, but the live gateway answers a bare array — and a
   * badge must never crash the shell. Unknown shapes resolve to empty.
   */
  const pendingList: unknown[] = Array.isArray(pending.data)
    ? pending.data
    : (pending.data?.approvals ?? [])
  const pendingCount = pendingList.length

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

  const hasSession = session !== null
  useEffect(() => {
    // Heartbeat runs only while a session exists: it starts on login and
    // stops on lock or logout, so idle signed out tabs never probe.
    if (!hasSession) return
    return startSessionHeartbeat()
  }, [hasSession])

  useEffect(() => {
    // Post-login landing: move screen-reader and keyboard focus to the
    // screen heading exactly once. Only the login redirect sets this flag.
    const landed = location.state as { fromLogin?: boolean } | null
    if (landed?.fromLogin === true) {
      const heading = document.querySelector('#main h1')
      if (heading instanceof HTMLElement) {
        heading.tabIndex = -1
        heading.focus({ preventScroll: true })
      }
    }
  }, [location])

  const toggleCollapsed = (): void => {
    setCollapsed((c) => {
      writeStoredSidebar(c ? 'open' : 'closed')
      return !c
    })
  }

  const groups: NavGroup[] = [
    {
      label: null,
      items: [{ to: '/', label: 'Overview', icon: LayoutDashboard, audience: 'session' }],
    },
    {
      label: 'Run',
      items: [
        { to: '/playground', label: 'Playground', icon: FlaskConical, audience: 'public' },
        { to: '/embeddings', label: 'Embeddings', icon: Brain, audience: 'public' },
      ],
    },
    {
      label: 'Guard',
      items: [
        { to: '/circuits', label: 'Circuits', icon: Zap, audience: 'admin' },
        { to: '/models', label: 'Models', icon: Layers, audience: 'admin' },
        {
          to: '/approvals',
          label: 'Approvals',
          icon: ShieldCheck,
          audience: 'admin',
          badge:
            pendingCount > 0
              ? () => (
                  <span
                    aria-label={`${String(pendingCount)} pending approvals`}
                    className="rounded-full bg-warn/20 px-2 py-0.5 font-mono text-xs text-warn tnum dark:text-warn-soft"
                  >
                    {pendingCount}
                  </span>
                )
              : undefined,
        },
        { to: '/cache', label: 'Cache & budgets', icon: Database, audience: 'admin' },
        { to: '/keys', label: 'Keys', icon: KeyRound, audience: 'admin' },
      ],
    },
    {
      label: 'Inspect',
      items: [
        { to: '/ledger', label: 'Ledger', icon: BookOpen, audience: 'admin' },
        { to: '/mcp', label: 'MCP', icon: Plug, audience: 'session' },
        { to: '/observability', label: 'Observability', icon: Activity, audience: 'session' },
      ],
    },
  ]
  const isAdmin = session?.admin === true
  const visibleGroups = groups
    .map((g) => ({
      ...g,
      items: g.items.filter(
        (i) =>
          i.audience === 'public' ||
          (i.audience === 'session' && session !== null) ||
          (i.audience === 'admin' && isAdmin),
      ),
    }))
    .filter((g) => g.items.length > 0)
  const visiblePaths = useMemo(
    () => new Set(visibleGroups.flatMap((g) => g.items.map((i) => i.to))),
    [visibleGroups],
  )

  useEffect(() => {
    // G-chords, `?` sheet, Esc ladder. Chords never fire while typing and
    // never travel anywhere the session may not see.
    const onKey = (e: KeyboardEvent): void => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') return
      if (e.key === 'Escape') {
        if (sheetOpen) setSheetOpen(false)
        else setDrawer(false)
        return
      }
      if (isEditable(e.target)) return
      if (e.key === '?') {
        setSheetOpen(true)
        return
      }
      const bare = !e.metaKey && !e.ctrlKey && !e.altKey
      if (e.key.toLowerCase() === 'g' && bare) {
        chordAt.current = Date.now()
        return
      }
      if (Date.now() - chordAt.current > CHORD_WINDOW_MS) return
      chordAt.current = 0
      const dest = targetForChord(e.key)
      if (dest !== null && visiblePaths.has(dest)) {
        setDrawer(false)
        void navigate(dest)
      }
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
    }
  }, [navigate, sheetOpen, visiblePaths])

  const routeLabel =
    pathname === '/'
      ? 'Overview'
      : (visibleGroups.flatMap((g) => g.items).find((item) => item.to === pathname)?.label ??
        pathname)

  const sidebarBody = (
    <div className="flex h-full flex-col">
      <div
        className={
          collapsed ? 'flex flex-col items-center gap-1 p-3' : 'flex items-center gap-2 p-4'
        }
      >
        <Link
          to={session === null ? '/playground' : '/'}
          onClick={() => {
            setDrawer(false)
          }}
          aria-label="CacheRelay home"
          className="flex min-w-0 items-center gap-2 rounded-md"
        >
          <span className="inline-flex shrink-0 items-center text-ember">
            <CacheRelayMark size={collapsed ? 20 : 18} />
          </span>
          {collapsed ? null : (
            <span className="truncate font-display text-lg font-medium tracking-tight">
              CacheRelay
            </span>
          )}
        </Link>
        {collapsed ? null : <span className="flex-1" />}
        <button
          type="button"
          onClick={toggleCollapsed}
          aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          className={`hidden rounded-md text-ink-soft lg:block dark:text-parchment-soft ${
            collapsed ? 'p-1' : 'p-2'
          }`}
        >
          {collapsed ? <ChevronsRight size={14} /> : <ChevronsLeft size={16} />}
        </button>
      </div>
      <nav aria-label="Primary" className="flex-1 space-y-4 overflow-y-auto px-2">
        {visibleGroups.map((group) => (
          <div key={group.label ?? 'home'}>
            {group.label === null || collapsed ? null : (
              <p className="px-2 pt-2 pb-1 font-mono text-[11px] tracking-[0.14em] text-ink-soft uppercase dark:text-parchment-soft">
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
                        `relative flex items-center gap-2 rounded-md px-2 py-1.5 text-[13px] whitespace-nowrap ${
                          collapsed ? 'justify-center' : ''
                        } ${
                          isActive
                            ? 'bg-ink/[0.06] font-medium text-ink dark:bg-parchment/[0.08] dark:text-parchment'
                            : 'text-ink-soft hover:bg-ink/4 hover:text-ink dark:text-parchment-soft dark:hover:bg-parchment/6 dark:hover:text-parchment'
                        }`
                      }
                    >
                      {({ isActive }) => (
                        <>
                          {isActive && !collapsed ? (
                            <span
                              aria-hidden="true"
                              className="absolute top-1/2 left-0 h-3.5 w-0.5 -translate-y-1/2 rounded-full bg-ember"
                            />
                          ) : null}
                          <item.icon size={16} aria-hidden="true" className="shrink-0" />
                          {collapsed ? null : <span>{item.label}</span>}
                          {showBadge ? <span className="flex-1" /> : null}
                          {showBadge ? <Badge /> : null}
                        </>
                      )}
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
            className="flex w-full items-center gap-2 rounded-md p-2 text-[13px]"
          >
            <KeyRound size={16} aria-hidden="true" />
            {collapsed ? null : <span>Log in</span>}
          </NavLink>
        ) : (
          <button
            type="button"
            onClick={() => {
              setDrawer(false)
              // Every lock lands on the login screen, whatever route the
              // session was on when it ended.
              void logout().then(() => {
                void navigate('/login', { replace: true })
              })
            }}
            aria-label={`Lock console (signed in as ${session.username})`}
            className="flex w-full items-center gap-2 rounded-md p-2 text-[13px]"
          >
            <Lock size={16} aria-hidden="true" />
            {collapsed ? null : <span>Lock</span>}
          </button>
        )}
        <button
          type="button"
          onClick={toggleDark}
          aria-label={collapsed ? (dark ? 'Light theme' : 'Dark theme') : undefined}
          className="flex w-full items-center gap-2 rounded-md p-2 text-[13px]"
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
        className={`fixed inset-y-0 left-0 z-40 flex w-64 flex-col overflow-hidden border-r border-ink/10 bg-paper transition-transform dark:border-parchment/10 dark:bg-night ${
          drawer ? 'translate-x-0' : '-translate-x-full'
        } motion-reduce:transition-none lg:sticky lg:top-0 lg:z-auto lg:h-screen lg:shrink-0 lg:translate-x-0 lg:transition-[width] lg:duration-200 lg:ease-out ${
          collapsed ? 'lg:w-16' : 'lg:w-64'
        }`}
      >
        {sidebarBody}
      </aside>
      <div className="flex min-h-screen min-w-0 flex-1 flex-col">
        <header className="border-b border-ink/10 dark:border-parchment/10">
          <div className="border-b border-ink/10 dark:border-parchment/10">
            <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-1">
              <p className="min-w-0 flex-1 truncate font-mono text-xs whitespace-nowrap text-ink-soft dark:text-parchment-soft">
                cacherelay
                {import.meta.env.DEV ? ` · ${base || 'same-origin'}` : null}
              </p>
              <p className="shrink-0 rounded-full border border-ink/15 px-2 py-0.5 font-mono text-xs whitespace-nowrap text-ink-soft tnum dark:border-parchment/15 dark:text-parchment-soft">
                {authLabel}
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
            <p className="font-mono text-xs text-ink-soft lg:hidden dark:text-parchment-soft">
              {routeLabel}
            </p>
            <span className="flex-1" />
            <button
              type="button"
              onClick={() => {
                setSheetOpen(true)
              }}
              aria-label="Keyboard shortcuts"
              className="rounded-md border border-ink/15 px-3 py-2 font-mono text-[13px] dark:border-parchment/15"
            >
              ?
            </button>
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
        <ShortcutSheet
          open={sheetOpen}
          onClose={() => {
            setSheetOpen(false)
          }}
        />
        <Toasts />
        {import.meta.env.DEV ? (
          <footer className="border-t border-ink/10 dark:border-parchment/10">
            <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-1">
              <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
                route: {routeLabel}
              </p>
              <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
                theme: {dark ? 'dark' : 'light'}
              </p>
            </div>
          </footer>
        ) : null}
      </div>
    </div>
  )
}
