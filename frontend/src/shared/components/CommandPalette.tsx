import { Command } from 'cmdk'
import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'

export interface PaletteAction {
  id: string
  label: string
  hint?: string
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

  const nav: PaletteAction[] = [
    {
      id: 'nav-play',
      label: 'Go to Playground',
      run: () => {
        go('/')
      },
    },
    {
      id: 'nav-circuits',
      label: 'Go to Circuits',
      run: () => {
        go('/circuits')
      },
    },
    {
      id: 'nav-keys',
      label: 'Go to Keys',
      run: () => {
        go('/keys')
      },
    },
    {
      id: 'nav-ledger',
      label: 'Go to Ledger',
      run: () => {
        go('/ledger')
      },
    },
    {
      id: 'nav-cache',
      label: 'Go to Cache and budgets',
      run: () => {
        go('/cache')
      },
    },
    {
      id: 'nav-emb',
      label: 'Go to Embeddings',
      run: () => {
        go('/embeddings')
      },
    },
    {
      id: 'nav-hitl',
      label: 'Go to Approvals',
      run: () => {
        go('/approvals')
      },
    },
    {
      id: 'nav-obs',
      label: 'Go to Observability',
      run: () => {
        go('/observability')
      },
    },
  ]

  return (
    <>
      <button
        type="button"
        onClick={() => {
          setOpen(true)
        }}
        aria-keyshortcuts="Control+k Meta+k"
        className="rounded-md border border-ink/15 px-3 py-2 text-xs dark:border-parchment/15"
      >
        Commands (Ctrl+K)
      </button>
      <Command.Dialog open={open} onOpenChange={setOpen} label="Global command menu">
        <Command.Input placeholder="Search actions…" aria-label="Search actions" />
        <Command.List>
          <Command.Empty>No results found.</Command.Empty>
          {[...nav, ...actions].map((a) => (
            <Command.Item
              key={a.id}
              value={a.label}
              onSelect={() => {
                a.run()
              }}
            >
              {a.label}
              {a.hint === undefined ? null : (
                <span className="ml-2 text-xs tnum opacity-60">{a.hint}</span>
              )}
            </Command.Item>
          ))}
        </Command.List>
      </Command.Dialog>
    </>
  )
}
