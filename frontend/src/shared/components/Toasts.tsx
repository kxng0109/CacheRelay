import { useEffect } from 'react'
import { useToastStore } from '../toast/store.js'

/** Auto-dismiss delay: long enough to read, short enough to not linger. */
const DISMISS_MS = 4000

/**
 * Bottom-right notice stack for operator-initiated mutation outcomes.
 *
 * @remarks Success toasts use `role="status"` (polite), errors
 * `role="alert"` (assertive). Each toast dismisses itself after 4 s;
 * `Esc` anywhere in the stack clears all (Esc ladder). Enter animation is
 * a single opacity/translate fade killed by the reduced-motion switch.
 *
 * @returns The toast viewport, or nothing when the stack is empty.
 */
export function Toasts(): React.JSX.Element | null {
  const toasts = useToastStore((s) => s.toasts)
  const dismiss = useToastStore((s) => s.dismiss)
  const clear = useToastStore((s) => s.clear)

  useEffect(() => {
    if (toasts.length === 0) return
    const id = window.setTimeout(() => {
      const oldest = useToastStore.getState().toasts[0]
      if (oldest !== undefined) useToastStore.getState().dismiss(oldest.id)
    }, DISMISS_MS)
    return () => {
      window.clearTimeout(id)
    }
  }, [toasts])

  if (toasts.length === 0) return null
  return (
    <div
      aria-label="Notifications"
      className="toast-stack"
      onKeyDown={(e) => {
        if (e.key === 'Escape') clear()
      }}
    >
      {toasts.map((t) => (
        <div
          key={t.id}
          role={t.kind === 'error' ? 'alert' : 'status'}
          className={`toast-enter flex items-start gap-3 rounded-lg border px-3 py-2 text-xs shadow-lg ${
            t.kind === 'error'
              ? 'border-danger/40 bg-cream text-ink dark:bg-night dark:text-parchment'
              : 'border-ink/10 bg-cream text-ink dark:border-parchment/10 dark:bg-night dark:text-parchment'
          }`}
        >
          <span aria-hidden="true" className={t.kind === 'error' ? 'text-danger' : 'text-success'}>
            {t.kind === 'error' ? '■' : '●'}
          </span>
          <p className="min-w-0 flex-1">{t.text}</p>
          <button
            type="button"
            onClick={() => {
              dismiss(t.id)
            }}
            aria-label={`Dismiss: ${t.text}`}
            className="shrink-0 rounded px-1 text-ink-soft dark:text-parchment-soft"
          >
            ×
          </button>
        </div>
      ))}
    </div>
  )
}
