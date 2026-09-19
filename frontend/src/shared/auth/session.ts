import { resolveApiBase } from '../api/client.js'
import { useAuthStore } from './store.js'
import type { Session } from './store.js'

/**
 * Login response body: short-lived access token plus privilege flag.
 * (Shape asserted field-by-field in `toSession`; never trusted blindly.)
 */

/**
 * Refresh response body: fresh access token, rotated cookie server-side.
 */
interface RefreshResponse {
  accessToken: string
  expiresInSeconds: number
  admin: boolean
}

let refreshFlight: Promise<Session | null> | null = null

/**
 * Reads a JSON error message without throwing or leaking internals.
 *
 * @param res - Failed fetch response.
 * @param fallback - Message when the body carries no usable text.
 * @returns Safe message naming the status.
 */
async function errorMessage(res: Response, fallback: string): Promise<string> {
  if (res.status === 401) return 'Invalid credentials.'
  if (res.status === 429) return 'Too many attempts. Try again later.'
  const text = await res.text().catch(() => '')
  if (text.length > 0 && text.length < 500) {
    try {
      const parsed: unknown = JSON.parse(text)
      if (typeof parsed === 'object' && parsed !== null) {
        const detail = (parsed as Record<string, unknown>).detail
        if (typeof detail === 'string' && detail.length > 0) return detail
        const message = (parsed as Record<string, unknown>).message
        if (typeof message === 'string' && message.length > 0) return message
      }
    } catch {
      // Non-JSON bodies fall through to the status fallback.
    }
  }
  return `${fallback} (HTTP ${String(res.status)}).`
}

/**
 * Parses a login/refresh body without throwing.
 *
 * @param body - Unknown decoded JSON.
 * @param username - Login name to attach to the session.
 * @returns Session, or null when the shape is wrong.
 */
function toSession(body: unknown, username: string): Session | null {
  if (typeof body !== 'object' || body === null) return null
  const record = body as Record<string, unknown>
  if (typeof record.accessToken !== 'string' || record.accessToken.length === 0) return null
  if (typeof record.admin !== 'boolean') return null
  return { accessToken: record.accessToken, admin: record.admin, username }
}

/**
 * Logs in with a username and password.
 *
 * @remarks Password is never trimmed (whitespace may be significant) and
 * never stored, logged, or rendered. The refresh cookie travels
 * automatically; only the access token enters memory.
 *
 * @param username - Login name (trimmed by the caller-facing form).
 * @param password - Password, verbatim.
 * @returns The session on success.
 * @throws Error with a safe message on failure.
 */
export async function login(username: string, password: string): Promise<Session> {
  const res = await fetch(`${resolveApiBase()}/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    credentials: 'include',
    body: JSON.stringify({ username, password }),
  })
  if (!res.ok) throw new Error(await errorMessage(res, 'Login failed'))
  const session = toSession(await res.json().catch(() => null), username)
  if (session === null) throw new Error('Login failed (HTTP 200).')
  useAuthStore.getState().setSession(session)
  return session
}

/**
 * Redeems a single-use invite into an account and logs it in immediately.
 *
 * @remarks 404 and 410 resolve to one identical message so invite validity
 * cannot be probed (no user enumeration). The token leaves the URL after
 * submit (replace navigation by the caller).
 *
 * @param token - Opaque invite token.
 * @param username - Desired login name (trimmed by the caller-facing form).
 * @param password - Desired password, verbatim.
 * @returns The session on success.
 * @throws Error with a safe message on failure.
 */
export async function redeemInvite(
  token: string,
  username: string,
  password: string,
): Promise<Session> {
  const res = await fetch(`${resolveApiBase()}/v1/auth/redeem`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    credentials: 'include',
    body: JSON.stringify({ token, username, password }),
  })
  if (res.status === 404 || res.status === 410) {
    throw new Error('Invite invalid or already used.')
  }
  if (!res.ok) throw new Error(await errorMessage(res, 'Redemption failed'))
  const session = toSession(await res.json().catch(() => null), username)
  if (session === null) throw new Error('Redemption failed (HTTP 201).')
  useAuthStore.getState().setSession(session)
  return session
}

/**
 * Refreshes the access token exactly once per concurrent burst.
 *
 * @remarks Single-flight: parallel 401s share one refresh call instead of
 * stamping the rotation endpoint. A failed refresh clears the session so
 * the UI falls back to locked states (replay revokes the whole family
 * server-side, so retrying would be wrong).
 *
 * @returns The fresh session, or null when no session can be restored.
 */
export function refreshSession(): Promise<Session | null> {
  if (refreshFlight !== null) return refreshFlight
  refreshFlight = (async (): Promise<Session | null> => {
    try {
      const res = await fetch(`${resolveApiBase()}/v1/auth/refresh`, {
        method: 'POST',
        headers: { Accept: 'application/json' },
        credentials: 'include',
      })
      if (!res.ok) {
        useAuthStore.getState().setSession(null)
        return null
      }
      const body: unknown = await res.json().catch(() => null)
      if (
        typeof body !== 'object' ||
        body === null ||
        typeof (body as Record<string, unknown>).accessToken !== 'string'
      ) {
        useAuthStore.getState().setSession(null)
        return null
      }
      const record = body as RefreshResponse
      const prev = useAuthStore.getState().session
      const session: Session = {
        accessToken: record.accessToken,
        admin: record.admin,
        username: prev?.username ?? '',
      }
      useAuthStore.getState().setSession(session)
      return session
    } catch {
      useAuthStore.getState().setSession(null)
      return null
    } finally {
      refreshFlight = null
    }
  })()
  return refreshFlight
}

/**
 * Starts proactive session renewal while the app is open.
 *
 * @remarks Access tokens live 5 minutes for admins; refreshing every 4
 * keeps sessions alive without a user-visible expiry blip. Skips ticks
 * with no session. The caller owns cleanup (Layout effect).
 *
 * @returns Stop function clearing the interval.
 */
export function startSessionHeartbeat(): () => void {
  const id = setInterval(
    () => {
      if (useAuthStore.getState().session !== null) void refreshSession()
    },
    4 * 60 * 1000,
  )
  return () => {
    clearInterval(id)
  }
}

/**
 * Attempts one silent session restore at boot.
 *
 * @remarks Succeeds only when a live refresh cookie exists; a 401 means
 * logged-out and stays silent (no error surfaces for the normal case).
 */
export async function restoreSession(): Promise<void> {
  await refreshSession()
}

/**
 * Logs out everywhere: revokes all refresh families server-side, then
 * clears memory regardless of the outcome.
 */
export async function logout(): Promise<void> {
  const token = useAuthStore.getState().session?.accessToken
  try {
    await fetch(`${resolveApiBase()}/v1/auth/logout`, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        ...(token === undefined ? {} : { Authorization: `Bearer ${token}` }),
      },
      credentials: 'include',
    })
  } catch {
    // Offline logout still clears memory below; the cookie dies by expiry.
  }
  useAuthStore.getState().clear()
}
