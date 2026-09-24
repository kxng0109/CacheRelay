import { ApiError } from '../../shared/api/client.js'

interface DashboardErrorProps {
  /** Thrown query error (reads status when it is an ApiError). */
  error: Error
  /** Manual retry (never an automatic auth loop). */
  onRetry: () => void
}

/**
 * Status-matched diagnosis for dashboard-family queries.
 *
 * @remarks 400 carries narrow-the-window guidance verbatim; 401 names the
 * session plus the IdP-disablement hint (sudden cross-key 401s mean the
 * IdP account, not the password); 429 names client-side backoff since the
 * backend sends no `Retry-After` on these paths.
 *
 * @param props - Error plus retry.
 * @returns The diagnosis block.
 */
export function DashboardError({ error, onRetry }: DashboardErrorProps): React.JSX.Element {
  const status = error instanceof ApiError ? error.status : null
  return (
    <div
      role="alert"
      className="space-y-1 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent"
    >
      <p className="text-sm text-danger dark:text-danger-soft">{error.message}</p>
      {status === 400 ? (
        <p className="text-[13px] text-ink-soft dark:text-parchment-soft">
          The window was rejected. Narrow the window and retry.
        </p>
      ) : null}
      {status === 401 ? (
        <p className="text-[13px] text-ink-soft dark:text-parchment-soft">
          The session is invalid. Sign in again. If access vanished suddenly across keys and
          sessions, contact your admin. Your IdP account may be disabled.
        </p>
      ) : null}
      {status === 429 ? (
        <p className="text-[13px] text-ink-soft dark:text-parchment-soft">
          Slow down. Too many dashboard views. Backing off client side. Retry shortly.
        </p>
      ) : null}
      <button type="button" onClick={onRetry} className="mt-2 text-sm underline">
        Retry
      </button>
    </div>
  )
}
