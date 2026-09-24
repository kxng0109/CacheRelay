/**
 * Single-letter destinations after the `g` prefix (Railway-style chords).
 * Keys stay mnemonic: overview, playground, embeddings, observability,
 * circuits, keys, ledger, approvals, mcp, usage, teams.
 */
const CHORDS: Readonly<Record<string, string>> = {
  o: '/',
  p: '/playground',
  e: '/embeddings',
  b: '/observability',
  c: '/circuits',
  k: '/keys',
  l: '/ledger',
  a: '/approvals',
  m: '/mcp',
  u: '/usage',
  t: '/teams',
}

/** Milliseconds the `g` prefix waits for its second key. */
export const CHORD_WINDOW_MS = 800

/**
 * Resolves a chord second-key to its destination path.
 *
 * @param key - Pressed key (case-insensitive single letter).
 * @returns Destination path, or null for unbound keys.
 */
export function targetForChord(key: string): string | null {
  return CHORDS[key.toLowerCase()] ?? null
}

/**
 * Reports whether a keydown originates in editable content.
 *
 * @remarks Chords never fire while typing; `?` and `Esc` are the only
 * keys handled inside inputs (sheet toggle and ladder-out).
 *
 * @param target - Event target.
 * @returns True for inputs, textareas, selects, and rich editors.
 */
export function isEditable(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  if (target.isContentEditable) return true
  const tag = target.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT'
}
