import { create } from 'zustand'
import type { RateLimitSnapshot } from '../api/types.js'

/**
 * Last observed gateway rate-limit state.
 *
 * @remarks
 * Memory-only like the auth store: snapshots never touch `localStorage`,
 * `sessionStorage`, cookies, or IndexedDB. The transport (or the SSE path)
 * writes each settled response's parsed headers here; the shell strip reads
 * them. Null means no gateway response has been observed yet this session.
 */
interface RateLimitState {
  /** Latest parsed snapshot. Null before the first observed response. */
  snapshot: RateLimitSnapshot | null
  /** Replaces the snapshot with freshly parsed headers. */
  setSnapshot: (snapshot: RateLimitSnapshot) => void
  /** Forgets the snapshot (for example on sign-out). */
  clear: () => void
}

/**
 * Memory-only last-rate-limit store.
 *
 * @returns The zustand rate-limit store hook.
 */
export const useRateLimitStore = create<RateLimitState>()((set) => ({
  snapshot: null,
  setSnapshot: (snapshot) => {
    set({ snapshot })
  },
  clear: () => {
    set({ snapshot: null })
  },
}))
