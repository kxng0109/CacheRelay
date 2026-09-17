import { NavLink, Outlet } from 'react-router'
import { useShallow } from 'zustand/react/shallow'
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
 * @returns The shell layout wrapping every route.
 */
export function Layout(): React.JSX.Element {
  const { dark, toggleDark } = useUiStore(
    useShallow((s) => ({ dark: s.dark, toggleDark: s.toggleDark })),
  )
  return (
    <div className="min-h-screen bg-paper text-ink dark:bg-night dark:text-parchment">
      <a href="#main" className="skip-link">
        Skip to content
      </a>
      <header className="border-b border-ink/10 dark:border-parchment/10">
        <div className="mx-auto flex max-w-6xl items-center gap-4 px-4 py-3">
          <span aria-hidden="true" className="inline-block size-3 rounded-sm bg-ember" />
          <p className="text-sm font-semibold tracking-tight">CacheRelay</p>
          <p className="hidden text-xs text-ink/60 sm:block dark:text-parchment/60">
            Enterprise AI gateway console
          </p>
          <span className="flex-1" />
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
      <main id="main" className="mx-auto max-w-6xl px-4 py-6">
        <Outlet />
      </main>
    </div>
  )
}
