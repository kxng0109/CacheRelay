import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { ApiError, GatewayClient, toErrorMessage } from '../../shared/api/client.js'
import { useAuthStore } from '../../shared/auth/store.js'
import { SsoButtons } from './SsoButtons.js'

/** Milliseconds the callback waits before timing out (backfill budget). */
export const SSO_TIMEOUT_MS = 30_000

/** SSO landing parse: token ready, or a terminal error needing no fetch. */
type Landing = { ok: true; token: string } | { ok: false; error: string }

/**
 * Reads the SSO landing coordinates once (render-time, one-shot).
 *
 * @returns Token to complete, or a terminal error for missing pieces.
 */
function readLanding(): Landing {
  const search = new URLSearchParams(window.location.search)
  if (search.get('sso') !== '1')
    return { ok: false, error: 'This page only completes SSO sign-ins.' }
  const token = new URLSearchParams(window.location.hash.replace(/^#/, '')).get('access_token')
  if (token === null || token === '') {
    return {
      ok: false,
      error: 'SSO sign-in arrived without a token. Start again from a provider below.',
    }
  }
  return { ok: true, token }
}

/**
 * Completes the SSO redirect: fragment token into a memory-only session.
 *
 * @remarks Backend truth (`SsoSuccessHandler`): success lands on
 * `/?sso=1#access_token=<jwt>&admin=<bool>` after a possibly blocking
 * first-login IdP backfill — hence a pending state with timeout copy,
 * never an instant-failure path. The fragment never leaves the browser:
 * it moves to memory and the URL is cleared immediately. Identity
 * (username, admin) comes from `GET /v1/auth/me`, never the fragment.
 * IdP-disabled accounts fail here with 401/403 plus the admin-contact
 * hint instead of a retry loop.
 *
 * @param props - Optional timeout override (tests and embeds only).
 * @returns Pending, error, or nothing (navigates away on success).
 */
export function SsoCallback({
  timeoutMs = SSO_TIMEOUT_MS,
}: {
  timeoutMs?: number
}): React.JSX.Element {
  const navigate = useNavigate()
  const [landing] = useState<Landing>(readLanding)
  const [error, setError] = useState<string | null>(landing.ok ? null : landing.error)

  useEffect(() => {
    if (!landing.ok) return
    const { token } = landing
    const controller = new AbortController()
    // Local flag (never error-shape sniffing): abort rejections cross
    // realms as plain Errors, so `instanceof DOMException` cannot tell a
    // timeout from a failure. Only our own timer sets this.
    let timedOut = false
    const timer = setTimeout(() => {
      timedOut = true
      controller.abort()
    }, timeoutMs)
    new GatewayClient({ token })
      .authMe({ signal: controller.signal })
      .then((me) => {
        clearTimeout(timer)
        useAuthStore
          .getState()
          .setSession({ accessToken: token, admin: me.admin, username: me.username })
        window.history.replaceState(null, '', window.location.pathname)
        void navigate('/', { state: { fromLogin: true } })
      })
      .catch((e: unknown) => {
        clearTimeout(timer)
        if (timedOut) {
          setError(
            'SSO sign-in timed out after 30 seconds. First logins can stall on IdP sync. Try again, or contact your admin.',
          )
        } else if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
          setError(
            'SSO sign-in was refused (401/403). Contact your admin. Your IdP account may be disabled.',
          )
        } else {
          setError(toErrorMessage(e, 'SSO sign-in failed.'))
        }
      })
    return () => {
      clearTimeout(timer)
      controller.abort()
    }
  }, [landing, navigate, timeoutMs])

  if (error !== null) {
    return (
      <div className="mx-auto w-full max-w-sm space-y-4">
        <div
          role="alert"
          className="rounded-md border border-ink/10 bg-transparent p-3 dark:border-parchment/10"
        >
          <p className="text-sm text-danger dark:text-danger-soft">{error}</p>
        </div>
        <SsoButtons />
        <Link
          to="/login"
          className="block w-full rounded-md border border-ink/15 px-4 py-2 text-center text-sm dark:border-parchment/15"
        >
          Back to log in
        </Link>
      </div>
    )
  }
  return (
    <div className="mx-auto w-full max-w-sm space-y-2">
      <p role="status" className="font-display text-2xl font-medium tracking-tight">
        Completing SSO sign-in…
      </p>
      <p className="text-sm text-ink-soft dark:text-parchment-soft">
        First logins can take several seconds while the server syncs IdP groups. Still trying (up to
        30s). Do not close this page.
      </p>
    </div>
  )
}
