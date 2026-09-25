import { create } from 'zustand'

interface DriftState {
  /** How many unfamiliar payloads were hidden this session. */
  count: number
  /** Endpoint of the most recent unfamiliar payload, if any. */
  lastEndpoint: string | null
  /** Records one hidden payload. */
  note: (endpoint: string) => void
  /** Dismisses the notice. */
  clear: () => void
}

/**
 * Process-wide wire-drift notice state.
 *
 * @remarks When a gateway response fails transport-boundary validation the
 * client hides the payload and reports here instead of crashing the
 * screen. The shell renders one muted line; senders only note.
 *
 * @returns The zustand drift store hook.
 */
export const useDriftStore = create<DriftState>()((set) => ({
  count: 0,
  lastEndpoint: null,
  note: (endpoint) => {
    set((s) => ({ count: s.count + 1, lastEndpoint: endpoint }))
  },
  clear: () => {
    set({ count: 0, lastEndpoint: null })
  },
}))
