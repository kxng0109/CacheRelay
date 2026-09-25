import { useCallback, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useOverlayFocus } from './useOverlayFocus.js'

/**
 * Shared overlay inspector drawer for every table in the console.
 *
 * @remarks Floats over the table (portalled to `document.body`) so opening
 * it never reflows content: the table keeps full width behind a dimming
 * backdrop. Entrance and exit slide and fade per the theme motion tokens;
 * exit plays out before unmounting so dismissing mirrors opening. One
 * behavior everywhere: backdrop, ✕, and `Escape` dismiss; focus moves to
 * the heading on open and returns to the originating row on close; the
 * document stops scrolling while open. Motion orients only — state is never
 * conveyed by animation, so meaning survives screenshots and reduced motion
 * settings (reduced motion dismisses instantly).
 *
 * @param props - Landmark label, title, close handler, panel content.
 * @returns The inspector drawer.
 */
export function InspectorShell({
  label,
  title,
  onClose,
  children,
}: {
  label: string
  title: string
  onClose: () => void
  children: React.ReactNode
}): React.JSX.Element {
  const headingRef = useRef<HTMLHeadingElement | null>(null)
  const panelRef = useRef<HTMLElement | null>(null)
  const [entered, setEntered] = useState(false)
  const [leaving, setLeaving] = useState(false)
  const leaveTimer = useRef<number | null>(null)
  // Shared overlay contract (FE-09): heading-first initial focus, Tab trap,
  // background inert, focus return. Replaces the bespoke heading/origin
  // effects below.
  const { release } = useOverlayFocus(panelRef, headingRef)

  // Dismissals play the exit transition before unmounting so closing
  // mirrors opening. The delay matches the --dur-panel motion token.
  // Focus returns the moment the close starts, never inside the exit.
  const requestClose = useCallback((): void => {
    if (leaving) return
    release()
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      onClose()
      return
    }
    setLeaving(true)
    leaveTimer.current = window.setTimeout(onClose, 280)
  }, [leaving, onClose, release])

  useEffect(() => {
    return () => {
      if (leaveTimer.current !== null) window.clearTimeout(leaveTimer.current)
    }
  }, [])

  useEffect(() => {
    const frame = requestAnimationFrame(() => {
      setEntered(true)
    })
    return () => {
      cancelAnimationFrame(frame)
    }
  }, [])

  useEffect(() => {
    const onKey = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        event.preventDefault()
        requestClose()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('keydown', onKey)
    }
  }, [requestClose])

  useEffect(() => {
    const root = document.documentElement
    const previous = root.style.overflow
    root.style.overflow = 'hidden'
    return () => {
      root.style.overflow = previous
    }
  }, [])

  return createPortal(
    <>
      <button
        type="button"
        aria-label="Dismiss inspector"
        onClick={requestClose}
        className="fixed inset-0 z-40 cursor-default bg-night/60 transition-opacity duration-(--dur-panel) ease-enter motion-reduce:transition-none"
      />
      <aside
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-label={label}
        className={`fixed inset-y-0 right-0 z-50 max-h-screen w-[clamp(24rem,36vw,42rem)] max-w-[calc(100vw-2rem)] overflow-y-auto overscroll-contain border-l border-ink/10 bg-cream p-4 shadow-lift transition-[translate,opacity] duration-(--dur-panel) ease-enter motion-reduce:transition-none dark:border-parchment/10 dark:bg-night ${
          entered && !leaving ? 'translate-x-0 opacity-100' : 'translate-x-full opacity-0'
        }`}
      >
        <div className="space-y-3">
          <div className="flex items-start justify-between gap-3">
            <h2 ref={headingRef} tabIndex={-1} className="font-mono text-sm break-all outline-none">
              {title}
            </h2>
            <button
              type="button"
              onClick={requestClose}
              aria-label="Close inspector"
              className="shrink-0 rounded-md border border-ink/15 px-2 py-1 font-mono text-xs dark:border-parchment/15"
            >
              ✕
            </button>
          </div>
          {children}
        </div>
      </aside>
    </>,
    document.body,
  )
}
