import { useEffect } from 'react'
import { useToastStore } from '../toast/store.js'
import type { Toast } from '../toast/store.js'

/** Auto-dismiss delay: long enough to read, short enough to not linger. */
const DISMISS_MS = 4000

/**
 * One notice with its own dismissal timer.
 *
 * @remarks Per-toast timers: each notice lives exactly 4 s from its own
 * push, so a busy console cannot starve an early toast by pushing later
 * ones (the old shared timer reset on every push).
 *
 * @param props - The toast plus its dismiss handler.
 * @returns The notice row.
 */
function ToastItem({ toast, onDismiss }: { toast: Toast; onDismiss: (id: number) => void }) {
  useEffect(() => {
    const id = window.setTimeout(() => {
      onDismiss(toast.id)
    }, DISMISS_MS)
    return () => {
      window.clearTimeout(id)
    }
  }, [toast.id, onDismiss])

  return (
    <div
      role={toast.kind === 'error' ? 'alert' : 'status'}
      className={`toast-enter flex items-start gap-3 rounded-lg border px-3 py-2 text-[13px] shadow-lg ${
        toast.kind === 'error'
          ? 'border-danger/40 bg-cream text-ink dark:bg-night dark:text-parchment'
          : 'border-ink/10 bg-cream text-ink dark:border-parchment/10 dark:bg-night dark:text-parchment'
      }`}
    >
      <span aria-hidden="true" className={toast.kind === 'error' ? 'text-danger' : 'text-success'}>
        {toast.kind === 'error' ? '■' : '●'}
      </span>
      <p className="min-w-0 flex-1">{toast.text}</p>
      <button
        type="button"
        onClick={() => {
          onDismiss(toast.id)
        }}
        aria-label={`Dismiss: ${toast.text}`}
        className="shrink-0 rounded px-1 text-ink-soft dark:text-parchment-soft"
      >
        ×
      </button>
    </div>
  )
}

/**
 * Bottom-right notice stack for operator-initiated mutation outcomes.
 *
 * @remarks Success toasts use `role="status"` (polite), errors
 * `role="alert"` (assertive). Each toast dismisses itself 4 s after its
 * own push. `Esc` anywhere on the page clears all (global Esc ladder
 * entry): the stack is non-modal, so clearing composes with overlay
 * dismissals instead of competing with them. Enter animation is a single
 * opacity/translate fade killed by the reduced-motion switch.
 *
 * @returns The toast viewport, or nothing when the stack is empty.
 */
export function Toasts(): React.JSX.Element | null {
  const toasts = useToastStore((s) => s.toasts)
  const dismiss = useToastStore((s) => s.dismiss)
  const clear = useToastStore((s) => s.clear)

  useEffect(() => {
    if (toasts.length === 0) return
    const onKey = (e: KeyboardEvent): void => {
      if (e.key === 'Escape') clear()
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
    }
  }, [toasts.length, clear])

  if (toasts.length === 0) return null
  return (
    <div aria-label="Notifications" className="toast-stack">
      {toasts.map((t) => (
        <ToastItem key={t.id} toast={t} onDismiss={dismiss} />
      ))}
    </div>
  )
}
