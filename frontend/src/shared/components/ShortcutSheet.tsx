interface ShortcutSheetProps {
  /** Visibility flag owned by the shell. */
  open: boolean
  /** Closes the sheet (button, backdrop, or Esc). */
  onClose: () => void
}

const ROWS: readonly (readonly [string, string])[] = [
  ['Ctrl/⌘ + K', 'Command menu'],
  ['Ctrl/⌘ + Enter', 'Send playground prompt'],
  ['G then O / P / E', 'Overview / Playground / Embeddings'],
  ['G then B / M', 'Observability / MCP'],
  ['G then C / K / L / A', 'Circuits / Keys / Ledger / Approvals'],
  ['?', 'This shortcut sheet'],
  ['Esc', 'Close dialog, then drawer, then filter'],
]

/**
 * Keyboard shortcut reference sheet.
 *
 * @remarks Focus lands on Close when opened; Esc closes from anywhere
 * inside (keydown bubbles from the focused close button). Chords only
 * travel to routes the current session may see — the shell filters them.
 *
 * @param props - Visibility and close handler.
 * @returns The sheet dialog, or nothing when closed.
 */
export function ShortcutSheet({ open, onClose }: ShortcutSheetProps): React.JSX.Element | null {
  if (!open) return null
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-night/60 p-4"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose()
      }}
      onKeyDown={(e) => {
        if (e.key === 'Escape') onClose()
      }}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Keyboard shortcuts"
        className="w-full max-w-md space-y-3 rounded-xl border border-ink/10 bg-paper p-4 dark:border-parchment/10 dark:bg-night"
      >
        <div className="flex items-center justify-between gap-3">
          <h2 className="font-display text-lg font-medium tracking-tight">Keyboard shortcuts</h2>
          <button
            type="button"
            autoFocus
            onClick={onClose}
            aria-label="Close shortcuts"
            className="rounded-md border border-ink/15 px-3 py-1 text-[13px] dark:border-parchment/15"
          >
            Close
          </button>
        </div>
        <dl className="space-y-2">
          {ROWS.map(([keys, what]) => (
            <div key={keys} className="flex items-baseline justify-between gap-4 text-[13px]">
              <dt className="shrink-0 font-mono">{keys}</dt>
              <dd className="text-right text-ink-soft dark:text-parchment-soft">{what}</dd>
            </div>
          ))}
        </dl>
      </div>
    </div>
  )
}
