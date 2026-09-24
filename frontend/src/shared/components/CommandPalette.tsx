import { Command } from 'cmdk'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import { useShallow } from 'zustand/react/shallow'
import { useAuthStore } from '../auth/store.js'
import { useUiStore } from '../store.js'

export interface PaletteAction {
  id: string
  label: string
  hint?: string
  /** Visibility tier (guests see public actions only, no hint of the rest). */
  audience?: 'public' | 'session' | 'admin'
  run: () => void
}

/**
 * Global command palette (Ctrl/⌘+K): routes plus operational shortcuts.
 *
 * @remarks
 * Follows the ARIA combobox + dialog pattern via cmdk. Proof-type: live
 * (every action navigates or triggers a real gateway call owned elsewhere).
 *
 * @param props - Extra caller-supplied actions appended after navigation.
 * @returns The palette trigger button plus dialog.
 */
export function CommandPalette({ actions = [] }: { actions?: PaletteAction[] }): React.JSX.Element {
  const [open, setOpen] = useState(false)
  const navigate = useNavigate()

  useEffect(() => {
    const onKey = (e: KeyboardEvent): void => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setOpen((v) => !v)
      }
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
    }
  }, [])

  const go = (to: string): void => {
    setOpen(false)
    void navigate(to)
  }

  const session = useAuthStore(useShallow((s) => s.session))
  const isAdmin = session?.admin === true
  const dark = useUiStore((s) => s.dark)
  const toggleDark = useUiStore((s) => s.toggleDark)

  const toggleTheme = (): void => {
    setOpen(false)
    toggleDark()
  }

  const nav: PaletteAction[] = [
    {
      id: 'nav-overview',
      label: 'Go to Overview',
      audience: 'session',
      run: () => {
        go('/')
      },
    },
    {
      id: 'nav-play',
      label: 'Go to Playground',
      audience: 'public',
      run: () => {
        go('/playground')
      },
    },
    {
      id: 'nav-circuits',
      label: 'Go to Circuits',
      audience: 'admin',
      run: () => {
        go('/circuits')
      },
    },
    {
      id: 'nav-models',
      label: 'Go to Models',
      audience: 'admin',
      run: () => {
        go('/models')
      },
    },
    {
      id: 'nav-keys',
      label: 'Go to Keys',
      audience: 'admin',
      run: () => {
        go('/keys')
      },
    },
    {
      id: 'nav-usage',
      label: 'Go to Usage',
      audience: 'session',
      run: () => {
        go('/usage')
      },
    },
    {
      id: 'nav-teams',
      label: 'Go to Teams',
      audience: 'session',
      run: () => {
        go('/teams')
      },
    },
    {
      id: 'nav-ledger',
      label: 'Go to Ledger',
      audience: 'admin',
      run: () => {
        go('/ledger')
      },
    },
    {
      id: 'nav-cache',
      label: 'Go to Cache and budgets',
      audience: 'admin',
      run: () => {
        go('/cache')
      },
    },
    {
      id: 'nav-emb',
      label: 'Go to Embeddings',
      audience: 'public',
      run: () => {
        go('/embeddings')
      },
    },
    {
      id: 'nav-hitl',
      label: 'Go to Approvals',
      audience: 'admin',
      run: () => {
        go('/approvals')
      },
    },
    {
      id: 'nav-mcp',
      label: 'Go to MCP',
      audience: 'session',
      run: () => {
        go('/mcp')
      },
    },
    {
      id: 'nav-obs',
      label: 'Go to Observability',
      audience: 'session',
      run: () => {
        go('/observability')
      },
    },
  ]

  const ops: PaletteAction[] = [
    {
      id: 'op-theme',
      label: dark ? 'Switch to light theme' : 'Switch to dark theme',
      audience: 'public',
      run: () => {
        toggleTheme()
      },
    },
  ]

  const visible = [...nav, ...ops, ...actions].filter(
    (a) =>
      a.audience === undefined ||
      a.audience === 'public' ||
      (a.audience === 'session' && session !== null) ||
      (a.audience === 'admin' && isAdmin),
  )
  const context =
    session === null ? 'public console' : isAdmin ? `admin · ${session.username}` : session.username

  return (
    <>
      <button
        type="button"
        onClick={() => {
          setOpen(true)
        }}
        aria-keyshortcuts="Control+k Meta+k"
        className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
      >
        Commands (Ctrl+K)
      </button>
      <Command.Dialog open={open} onOpenChange={setOpen} label="Global command menu">
        <Command.Input placeholder="Search actions…" aria-label="Search actions" />
        <Command.List>
          <Command.Empty>No results found.</Command.Empty>
          {visible.map((a) => (
            <Command.Item
              key={a.id}
              value={a.label}
              onSelect={() => {
                a.run()
              }}
            >
              {a.label}
              {a.hint === undefined ? null : (
                <span className="ml-2 text-[13px] text-ink-soft tnum dark:text-parchment-soft">
                  {a.hint}
                </span>
              )}
            </Command.Item>
          ))}
        </Command.List>
        <p className="border-t border-ink/10 px-3 py-2 font-mono text-xs text-ink-soft dark:border-parchment/10 dark:text-parchment-soft">
          {context} · ↑↓ move · Enter run · Esc close
        </p>
      </Command.Dialog>
    </>
  )
}
