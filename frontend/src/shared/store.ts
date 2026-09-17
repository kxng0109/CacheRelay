import { create } from 'zustand'

interface UiState {
  dark: boolean
  toggleDark: () => void
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
 * UI preferences (theme only). No credentials or operational data live here.
 *
 * @returns The zustand UI store hook.
 */
export const useUiStore = create<UiState>()((set) => ({
  dark: false,
  toggleDark: () => {
    set((s) => {
      const next = !s.dark
      applyDark(next)
      return { dark: next }
    })
  },
}))
