import { Link } from 'react-router'

/**
 * Direct action of an empty trio: exactly one navigation link or button.
 */
export type TrioAction = { label: string; to: string } | { label: string; onClick: () => void }

interface EmptyTrioProps {
  /** Status line naming the empty state. */
  title: string
  /** One-sentence learning cue saying what fills the region. */
  cue: string
  /** Direct action moving forward, or null when no action applies. */
  action?: TrioAction | null
}

/**
 * Empty-as-prompt trio shared by every collection screen.
 *
 * @remarks One shape everywhere: status line (`status` role) plus learning
 * cue plus an optional direct action. Copy stays at the call site; only
 * the container, type, and action chrome unify here.
 *
 * @param props - Title, cue, and optional action.
 * @returns The trio card.
 */
export function EmptyTrio({ title, cue, action = null }: EmptyTrioProps): React.JSX.Element {
  return (
    <div className="flex min-h-64 flex-col items-center justify-center rounded-xl border border-dashed border-ink/20 bg-cream p-6 text-center dark:border-parchment/20 dark:bg-parchment/5">
      <p role="status" className="font-display text-xl font-medium tracking-tight">
        {title}
      </p>
      <p className="mx-auto mt-1 max-w-md text-[13px] text-ink-soft dark:text-parchment-soft">
        {cue}
      </p>
      {action === null ? null : 'to' in action ? (
        <Link
          to={action.to}
          className="mt-3 inline-block rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
        >
          {action.label}
        </Link>
      ) : (
        <button
          type="button"
          onClick={action.onClick}
          className="mt-3 rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
        >
          {action.label}
        </button>
      )}
    </div>
  )
}
