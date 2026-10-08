import { resolveApiBase } from '../api/client.js'
import { useAuthStore } from './store.js'
import type { Session } from './store.js'

/**
 * Login response body: short-lived access token plus privilege flag.
 * (Shape asserted field-by-field in `toSession`; never trusted blindly.)
 */

let refreshFlight: Promise<Session | null> | null = null

/**
 * Reads a JSON error message without throwing or leaking internals.
 *
 * @param res - Failed fetch response.
 * @param fallback - Message when the body carries no usable text.
 * @returns Safe message naming the status.
 */
async function errorMessage(res: Response, fallback: string): Promise<string> {
  // Lockouts answer the same generic 401 as bad passwords
  // (anti-enumeration): 429 never names a lockout distinctly.
  if (res.status === 401 || res.status === 429) return 'Invalid credentials.'
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
  const lifetime = record.expiresInSeconds
  return {
    accessToken: record.accessToken,
    admin: record.admin,
    username,
    ...(typeof lifetime === 'number' && Number.isFinite(lifetime) && lifetime > 0
      ? { expiresInSeconds: lifetime }
      : {}),
  }
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
 * @remarks 404 resolves to the identical invalid message so invite
 * validity cannot be probed (no user enumeration). The gateway never
 * answers 410 here, so no 410 branch exists. The token leaves the URL
 * after submit (replace navigation by the caller).
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
  if (res.status === 404) {
    throw new Error('Invite invalid or already used.')
  }
  if (!res.ok) throw new Error(await errorMessage(res, 'Redemption failed'))
  const session = toSession(await res.json().catch(() => null), username)
  if (session === null) throw new Error('Redemption failed (HTTP 201).')
  useAuthStore.getState().setSession(session)
  return session
}

/**
 * Resolves the display username for a cold-booted session. Refresh
 * responses carry no name; `GET /v1/auth/me` repairs it so the restored
 * session never fabricates a blank identity.
 *
 * @param token - Fresh access token for the identity read.
 * @returns The login name, or null when unreadable.
 */
async function fetchUsername(token: string): Promise<string | null> {
  try {
    const res = await fetch(`${resolveApiBase()}/v1/auth/me`, {
      headers: { Accept: 'application/json', Authorization: `Bearer ${token}` },
    })
    if (!res.ok) return null
    const body: unknown = await res.json().catch(() => null)
    if (typeof body !== 'object' || body === null) return null
    const username = (body as Record<string, unknown>).username
    return typeof username === 'string' && username.length > 0 ? username : null
  } catch {
    return null
  }
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
        // Required CSRF marker: the RefreshFilter rejects cookie-only calls
        // without it (400 problem+json). Safe for CORS preflight in
        // same-origin prod; dev needs the backend to allow-list it.
        headers: { Accept: 'application/json', 'X-CacheRelay-Refresh': '1' },
        credentials: 'include',
      })
      if (!res.ok) {
        useAuthStore.getState().setSession(null)
        return null
      }
      const body: unknown = await res.json().catch(() => null)
      const prev = useAuthStore.getState().session
      // Same shape gate as login/redeem: a non-boolean `admin` (or a
      // missing token) clears instead of entering memory half-trusted.
      // The previous username is kept when present; a cold boot repairs
      // it from the identity endpoint instead of fabricating blank.
      const kept = prev?.username ?? ''
      const bearer =
        typeof body === 'object' && body !== null
          ? (body as Record<string, unknown>).accessToken
          : null
      const name =
        kept.length > 0
          ? kept
          : typeof bearer === 'string' && bearer.length > 0
            ? ((await fetchUsername(bearer)) ?? '')
            : ''
      const session = toSession(body, name)
      if (session === null) {
        useAuthStore.getState().setSession(null)
        return null
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
 * Computes the proactive refresh delay from the server-reported token
 * lifetime. Refresh lands one minute before expiry; short lifetimes
 * floor at one minute and absurd values cap at ten.
 *
 * @param expiresInSeconds - Server lifetime, or null when unreported.
 * @returns Milliseconds until the next refresh tick.
 */
export function heartbeatDelayMs(expiresInSeconds: number | null | undefined): number {
  if (
    expiresInSeconds === null ||
    expiresInSeconds === undefined ||
    !Number.isFinite(expiresInSeconds)
  ) {
    return 4 * 60 * 1000
  }
  return Math.min(Math.max((expiresInSeconds - 60) * 1000, 60_000), 10 * 60 * 1000)
}

/**
 * Starts proactive session renewal while the app is open.
 *
 * @remarks Each tick reschedules from the live session lifetime (10
 * minute standard, 5 minute admin): refresh lands about a minute
 * before expiry without a user-visible blip. Skips ticks with no
 * session. The caller owns cleanup (Layout effect).
 *
 * @returns Stop function clearing the pending tick.
 */
export function startSessionHeartbeat(): () => void {
  let timer: ReturnType<typeof setTimeout> | undefined
  let stopped = false
  const tick = (): void => {
    if (stopped) return
    const lifetime = useAuthStore.getState().session?.expiresInSeconds
    timer = setTimeout(() => {
      if (useAuthStore.getState().session !== null) void refreshSession()
      tick()
    }, heartbeatDelayMs(lifetime))
  }
  tick()
  return () => {
    stopped = true
    clearTimeout(timer)
  }
}

/**
 * Reports whether a pathname is the DEV-only Playwright seed route.
 *
 * @remarks Named concept (not an inline prefix check) because two owners
 * depend on it: boot restore selection and the restore exemption in
 * `main.tsx`.
 *
 * @param pathname - Current location pathname.
 * @returns True for `/__test/session/...` paths.
 */
export function isTestSeedPath(pathname: string): boolean {
  return pathname.startsWith('/__test/session/')
}

/**
 * Public routes that never need a session on first paint. Restoring here
 * can wait for the first interaction, which keeps cold loads clean when the
 * gateway is unreachable. The console home restores immediately so a valid
 * session never bounces through login on cold reload; authed deep links
 * restore immediately so they do not bounce either. The DEV-only
 * Playwright seed route (`/__test/session/...`, FE-05) always defers: a
 * boot restore would wipe the seeded session before the seed navigates.
 *
 * @param pathname - Current location pathname.
 * @returns True when the restore may wait for first input.
 */
export function shouldDeferRestore(pathname: string): boolean {
  return (
    pathname === '/login' ||
    pathname === '/redeem' ||
    pathname === '/playground' ||
    pathname === '/embeddings' ||
    isTestSeedPath(pathname)
  )
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
