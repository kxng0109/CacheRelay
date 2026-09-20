import { create } from 'zustand'

/** Severity of a console notice. Errors announce assertively, rest politely. */
export type ToastKind = 'success' | 'error'

/** One stacked notice. Auto-dismissed by the viewport, never by senders. */
export interface Toast {
  /** Unique id (monotonic per session). */
  id: number
  /** Severity driving role and chrome. */
  kind: ToastKind
  /** Short operator-facing sentence with the confirmed outcome. */
  text: string
}

interface ToastState {
  /** Live stack, newest last. */
  toasts: Toast[]
  /** Appends a notice and returns its id. */
  push: (kind: ToastKind, text: string) => number
  /** Removes one notice by id. */
  dismiss: (id: number) => void
  /** Removes every notice (Esc ladder, route change). */
  clear: () => void
}

let nextId = 1

/**
 * Process-wide notice stack for mutation outcomes.
 *
 * @remarks Senders only push; the viewport owns timing (4 s auto-dismiss)
 * and dismissal. Routine polls must never toast — only operator-initiated
 * outcomes (approve, reject, purge, create, revoke) earn a notice.
 *
 * @returns The zustand toast store hook.
 */
export const useToastStore = create<ToastState>()((set) => ({
  toasts: [],
  push: (kind, text) => {
    const id = nextId
    nextId += 1
    set((s) => ({ toasts: [...s.toasts.slice(-4), { id, kind, text }] }))
    return id
  },
  dismiss: (id) => {
    set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) }))
  },
  clear: () => {
    set({ toasts: [] })
  },
}))
