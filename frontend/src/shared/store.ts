import { create } from 'zustand'

/** Storage key for the theme preference. Presentation state only. */
const THEME_KEY = 'cacherelay.theme'

export type ThemeName = 'light' | 'dark'

interface UiState {
  dark: boolean
  toggleDark: () => void
}

/**
 * Validates an untrusted stored value against the theme allow-list.
 *
 * @remarks Security: the ONLY accepted inputs are the exact strings
 * `light` and `dark`. Everything else — null, objects, `__proto__`,
 * markup, JSON, oversized strings — maps to null and the caller falls
 * back to the dark default. The result never reaches markup, URLs, or
 * auth paths; it only toggles a class name.
 *
 * @param raw - Untrusted value from storage (or anywhere else).
 * @returns The theme name, or null when the input is not exactly one.
 */
export function parseTheme(raw: unknown): ThemeName | null {
  if (raw === 'light' || raw === 'dark') return raw
  return null
}

/**
 * Applies the dark theme class to the document root.
 *
 * @param dark - True for night canvas, false for paper.
 */
function applyDark(dark: boolean): void {
  document.documentElement.classList.toggle('dark', dark)
}

/**
 * Reads the persisted theme without ever throwing.
 *
 * @returns True for dark, false for light. Defaults to dark when the
 * stored value is missing, invalid, or unreadable (private mode).
 */
function readStoredDark(): boolean {
  try {
    return parseTheme(window.localStorage.getItem(THEME_KEY)) !== 'light'
  } catch {
    return true
  }
}

/**
 * Persists the theme without ever throwing.
 *
 * @param dark - Theme to persist.
 */
function writeStoredDark(dark: boolean): void {
  try {
    window.localStorage.setItem(THEME_KEY, dark ? 'dark' : 'light')
  } catch {
    // Private mode or quota: theme stays session-only. Never a crash.
  }
}

const initialDark = readStoredDark()
applyDark(initialDark)

/**
 * UI preferences (theme only). No credentials or operational data live here.
 *
 * @returns The zustand UI store hook.
 */
export const useUiStore = create<UiState>()((set) => ({
  dark: initialDark,
  toggleDark: () => {
    set((s) => {
      const next = !s.dark
      applyDark(next)
      writeStoredDark(next)
      return { dark: next }
    })
  },
}))
