import { create } from 'zustand'

/**
 * Authenticated human session from `/v1/auth/login` or `/v1/auth/redeem`.
 *
 * @remarks The access token is short-lived (5 minutes for admins) and lives
 * in memory only. The refresh token lives in an httpOnly cookie the browser
 * sends automatically — JavaScript can neither read nor steal it.
 */
export interface Session {
  /** Short-lived access JWT, memory-only. */
  accessToken: string
  /** Whether the account holds the admin claim. */
  admin: boolean
  /** Login name for display. */
  username: string
}

/**
 * In-memory credentials. Nothing here ever touches `localStorage`,
 * `sessionStorage`, cookies, or IndexedDB — any in-origin script could read
 * those stores and replay the bearer until expiry. There is deliberately no
 * master-key field: the master secret is terminal/curl-only and the UI must
 * never be able to present it.
 */
interface AuthState {
  /** Virtual gateway key (`gw-…`) for the public surface. Null when signed out. */
  gatewayKey: string | null
  /** Human session, or null when logged out. */
  session: Session | null
  /** Stores the gateway key in memory only. */
  setGatewayKey: (key: string | null) => void
  /** Stores the session in memory only. */
  setSession: (session: Session | null) => void
  /** Clears gateway key and session (lock / logout). */
  clear: () => void
}

/**
 * Memory-only auth store.
 *
 * @returns The zustand auth store hook.
 */
export const useAuthStore = create<AuthState>()((set) => ({
  gatewayKey: null,
  session: null,
  setGatewayKey: (key) => {
    set({ gatewayKey: key })
  },
  setSession: (session) => {
    set({ session })
  },
  clear: () => {
    set({ gatewayKey: null, session: null })
  },
}))
