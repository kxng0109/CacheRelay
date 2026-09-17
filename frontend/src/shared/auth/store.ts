import { create } from 'zustand'

/**
 * In-memory credentials. Tokens never touch `localStorage`, `sessionStorage`,
 * cookies, or IndexedDB — any in-origin script could read those stores and
 * replay the bearer until expiry.
 */
interface AuthState {
  /** Virtual gateway key (`gw-…`) for the public surface. Null when signed out. */
  gatewayKey: string | null
  /** Bootstrap master admin key for `/v1/admin/**`. Null when not provided. */
  adminKey: string | null
  /** Stores the gateway key in memory only. */
  setGatewayKey: (key: string | null) => void
  /** Stores the admin key in memory only. */
  setAdminKey: (key: string | null) => void
  /** Clears both credentials (for example on sign-out). */
  clear: () => void
}

/**
 * Memory-only auth store.
 *
 * @returns The zustand auth store hook.
 */
export const useAuthStore = create<AuthState>()((set) => ({
  gatewayKey: null,
  adminKey: null,
  setGatewayKey: (key) => {
    set({ gatewayKey: key })
  },
  setAdminKey: (key) => {
    set({ adminKey: key })
  },
  clear: () => {
    set({ gatewayKey: null, adminKey: null })
  },
}))
