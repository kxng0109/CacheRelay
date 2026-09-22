import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'

/**
 * Shared overlay inspector drawer for every table in the console.
 *
 * @remarks Floats over the table (portalled to `document.body`) so opening
 * it never reflows content: the table keeps full width behind a dimming
 * backdrop. Entrance slides and fades per the theme motion tokens; exit
 * unmounts immediately. One behavior everywhere: backdrop, ✕, and `Escape`
 * dismiss; focus moves to the heading on open and returns to the
 * originating row on close; the document stops scrolling while open.
 * Motion orients only — state is never conveyed by animation, so meaning
 * survives screenshots and reduced-motion settings.
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
  const [entered, setEntered] = useState(false)

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
        onClose()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('keydown', onKey)
    }
  }, [onClose])

  useEffect(() => {
    headingRef.current?.focus()
  }, [])

  useEffect(() => {
    const origin = document.activeElement as HTMLElement | null
    return () => {
      if (origin?.isConnected === true) origin.focus()
    }
  }, [])

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
        onClick={onClose}
        className="fixed inset-0 z-40 cursor-default bg-night/60 transition-opacity duration-(--dur-panel) ease-enter motion-reduce:transition-none"
      />
      <aside
        aria-label={label}
        className={`fixed inset-y-0 right-0 z-50 max-h-screen w-105 max-w-[calc(100vw-2rem)] overflow-y-auto overscroll-contain border-l border-ink/10 bg-cream p-4 shadow-lift transition-[translate,opacity] duration-(--dur-panel) ease-enter motion-reduce:transition-none dark:border-parchment/10 dark:bg-night ${
          entered ? 'translate-x-0 opacity-100' : 'translate-x-full opacity-0'
        }`}
      >
        <div className="space-y-3">
          <div className="flex items-start justify-between gap-3">
            <h2 ref={headingRef} tabIndex={-1} className="font-mono text-sm break-all outline-none">
              {title}
            </h2>
            <button
              type="button"
              onClick={onClose}
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
