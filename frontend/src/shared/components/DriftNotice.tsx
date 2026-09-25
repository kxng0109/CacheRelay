import { useDriftStore } from '../drift/store.js'

/**
 * Muted wire-drift notice: names the endpoint whose unfamiliar payload was
 * hidden instead of crashing the screen.
 *
 * @remarks Discrete `status` event (asserted once per change, not ticked),
 * with a dismiss control. Renders nothing while no payload was hidden.
 *
 * @returns The notice, or null when quiet.
 */
export function DriftNotice(): React.JSX.Element | null {
  const count = useDriftStore((s) => s.count)
  const lastEndpoint = useDriftStore((s) => s.lastEndpoint)
  const clear = useDriftStore((s) => s.clear)
  if (count === 0 || lastEndpoint === null) return null
  return (
    <div className="mx-auto w-full max-w-6xl px-4 pt-4">
      <p role="status" className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
        Hid unfamiliar data from {lastEndpoint} ({count}×). Screens show what validated.
        <button
          type="button"
          onClick={clear}
          aria-label="Dismiss drift notice"
          className="ml-2 underline"
        >
          Dismiss
        </button>
      </p>
    </div>
  )
}
