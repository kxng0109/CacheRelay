import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * Shared centered dialog for every form popup in the console.
 *
 * @remarks One behavior everywhere: the panel fades and rises in on mount
 * and plays the same motion out before unmounting, so opening mirrors
 * closing. Backdrop, close button, and `Escape` all dismiss through one
 * path; reduced motion dismisses instantly. Callers keep conditional
 * rendering (`open ? <Modal/> : null`): the dialog stays mounted while the
 * exit plays and only then calls `onClose`, exactly like InspectorShell.
 * Motion orients only, so meaning survives screenshots and reduced-motion
 * settings.
 *
 * @param props - Accessible label, title, optional subtitle, close button
 * label, wide layout flag, close handler, and dialog content.
 * @returns The modal dialog.
 */
export function Modal({
  label,
  title,
  subtitle,
  closeLabel,
  wide = false,
  onClose,
  children,
}: {
  label: string
  title: string
  subtitle?: string
  closeLabel: string
  wide?: boolean
  onClose: () => void
  children: React.ReactNode
}): React.JSX.Element {
  const [entered, setEntered] = useState(false)
  const [leaving, setLeaving] = useState(false)
  const leaveTimer = useRef<number | null>(null)

  // Dismissals play the exit before unmounting. The delay matches the
  // --dur-panel motion token used by the entrance.
  const requestClose = useCallback((): void => {
    if (leaving) return
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      onClose()
      return
    }
    setLeaving(true)
    leaveTimer.current = window.setTimeout(onClose, 280)
  }, [leaving, onClose])

  useEffect(() => {
    const frame = window.requestAnimationFrame(() => {
      setEntered(true)
    })
    return () => {
      window.cancelAnimationFrame(frame)
      if (leaveTimer.current !== null) window.clearTimeout(leaveTimer.current)
    }
  }, [])

  const show = entered && !leaving

  return (
    <div
      className={`fixed inset-0 z-50 m-0 flex items-center justify-center bg-night/60 p-4 transition-opacity duration-(--dur-panel) ${
        show ? 'opacity-100' : 'opacity-0'
      }`}
      onClick={(e) => {
        if (e.target === e.currentTarget) requestClose()
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') requestClose()
      }}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={label}
        className={`max-h-[calc(100vh-2rem)] w-full ${wide ? 'max-w-2xl' : 'max-w-lg'} space-y-4 overflow-y-auto rounded-xl border border-ink/10 bg-paper p-5 shadow-lift transition-[translate,opacity] duration-(--dur-panel) ease-enter motion-reduce:transition-none dark:border-parchment/10 dark:bg-night ${
          show ? 'translate-y-0 opacity-100' : 'translate-y-2 opacity-0'
        }`}
      >
        <div className="flex items-start justify-between gap-3">
          <div>
            <h2 className="font-display text-xl font-medium tracking-tight">{title}</h2>
            {subtitle === undefined ? null : (
              <p className="mt-1 text-[13px] text-ink-soft dark:text-parchment-soft">{subtitle}</p>
            )}
          </div>
          <button
            type="button"
            onClick={requestClose}
            aria-label={closeLabel}
            className="shrink-0 rounded-md border border-ink/15 px-2 py-1 font-mono text-xs dark:border-parchment/15"
          >
            ✕
          </button>
        </div>
        {children}
      </div>
    </div>
  )
}
