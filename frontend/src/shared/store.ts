import { create } from 'zustand'

/** Storage key for the theme preference. Presentation state only. */
const THEME_KEY = 'cacherelay.theme'

/** Media query that tracks the OS color scheme. */
const OS_DARK_QUERY = '(prefers-color-scheme: dark)'

export type ThemeName = 'light' | 'dark' | 'system'

interface UiState {
  /** Stored preference. `system` follows the OS and is the default. */
  theme: ThemeName
  /** Resolved canvas. True for night, false for paper. */
  dark: boolean
  setTheme: (theme: ThemeName) => void
  /** Flips to the explicit opposite of the resolved canvas. */
  toggleDark: () => void
}

/**
 * Validates an untrusted stored value against the theme allow-list.
 *
 * @remarks Security: the ONLY accepted inputs are the exact strings
 * `light`, `dark`, and `system`. Everything else — null, objects,
 * `__proto__`, markup, JSON, oversized strings — maps to null and the
 * caller falls back to the system default. The result never reaches
 * markup, URLs, or auth paths; it only toggles a class name.
 *
 * @param raw - Untrusted value from storage (or anywhere else).
 * @returns The theme name, or null when the input is not exactly one.
 */
export function parseTheme(raw: unknown): ThemeName | null {
  if (raw === 'light' || raw === 'dark' || raw === 'system') return raw
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
 * Reads the OS color scheme without ever throwing.
 *
 * @returns True when the OS prefers dark. Defaults to dark when the
 * preference is unreadable, matching the previous default.
 */
function readOsDark(): boolean {
  try {
    return window.matchMedia(OS_DARK_QUERY).matches
  } catch {
    return true
  }
}

/**
 * Resolves a preference to a concrete canvas.
 *
 * @param theme - Stored preference.
 * @returns True for night canvas, false for paper.
 */
function resolveDark(theme: ThemeName): boolean {
  if (theme === 'light') return false
  if (theme === 'dark') return true
  return readOsDark()
}

/**
 * Reads the persisted theme without ever throwing.
 *
 * @returns The stored preference, `system` when nothing valid is stored,
 * and `dark` when storage itself is unreadable (private mode).
 */
function readStoredTheme(): ThemeName {
  try {
    return parseTheme(window.localStorage.getItem(THEME_KEY)) ?? 'system'
  } catch {
    return 'dark'
  }
}

/**
 * Persists the theme without ever throwing.
 *
 * @param theme - Theme preference to persist.
 */
function writeStoredTheme(theme: ThemeName): void {
  try {
    window.localStorage.setItem(THEME_KEY, theme)
  } catch {
    // Private mode or quota: theme stays session-only. Never a crash.
  }
}

const initialTheme = readStoredTheme()
const initialDark = resolveDark(initialTheme)
applyDark(initialDark)

/**
 * UI preferences (theme only). No credentials or operational data live here.
 *
 * @returns The zustand UI store hook.
 */
export const useUiStore = create<UiState>()((set) => ({
  theme: initialTheme,
  dark: initialDark,
  setTheme: (theme) => {
    const dark = resolveDark(theme)
    applyDark(dark)
    writeStoredTheme(theme)
    set({ theme, dark })
  },
  toggleDark: () => {
    set((s) => {
      const theme: ThemeName = s.dark ? 'light' : 'dark'
      applyDark(theme === 'dark')
      writeStoredTheme(theme)
      return { theme, dark: theme === 'dark' }
    })
  },
}))

// While the system preference is active the canvas tracks OS changes live.
try {
  window.matchMedia(OS_DARK_QUERY).addEventListener('change', (event) => {
    const state = useUiStore.getState()
    if (state.theme !== 'system') return
    applyDark(event.matches)
    useUiStore.setState({ dark: event.matches })
  })
} catch {
  // No OS theme signal in this environment. Explicit choices still apply.
}
