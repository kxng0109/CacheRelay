import { useEffect, useRef } from 'react'

/**
 * Shared right-dock inspector shell for every table in the console.
 *
 * @remarks One behavior everywhere: the panel labels itself through its
 * landmark, moves focus to its heading on open, and closes on `Escape`.
 * Surfaces stay terminal: bordered card, mono numerals, ember accent only,
 * no glow or motion.
 *
 * @param props - Panel title, close handler, panel content.
 * @returns The inspector dock.
 */
export function InspectorShell({
  title,
  onClose,
  children,
}: {
  title: string
  onClose: () => void
  children: React.ReactNode
}): React.JSX.Element {
  const headingRef = useRef<HTMLHeadingElement | null>(null)

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

  return (
    <div className="space-y-3 rounded-xl border border-ink/10 bg-cream p-4 shadow-lift dark:border-parchment/10 dark:bg-transparent dark:shadow-none">
      <div className="flex items-start justify-between gap-3">
        <h2 ref={headingRef} tabIndex={-1} className="font-mono text-sm break-all outline-none">
          {title}
        </h2>
        <button
          type="button"
          onClick={onClose}
          aria-label="Close inspector [esc]"
          className="shrink-0 rounded-md border border-ink/15 px-2 py-1 font-mono text-xs dark:border-parchment/15"
        >
          ✕
        </button>
      </div>
      {children}
    </div>
  )
}
