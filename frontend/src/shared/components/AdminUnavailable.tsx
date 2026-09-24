import { Link } from 'react-router'

interface AdminUnavailableProps {
  /** Request path that answered not-found (rendered as mono text). */
  path: string
  /** HTTP status observed (expected 404 stealth). */
  status?: number
}

/**
 * Stealth-404 face for admin screens: no access and no route look alike.
 *
 * @remarks Backend truth (`AdminAuthFilter`): every `/v1/admin/**` path
 * answers 404 `{"title":"Not Found","detail":"No such endpoint."}` for
 * missing or bad auth — identical to a missing route. This screen renders
 * "admin unavailable," never distinguishes, and never retry-loops auth.
 *
 * @param props - Path plus observed status.
 * @returns The admin-unavailable diagnosis block.
 */
export function AdminUnavailable({ path, status = 404 }: AdminUnavailableProps): React.JSX.Element {
  return (
    <div
      role="alert"
      className="space-y-2 rounded-xl border border-danger/30 bg-cream p-4 dark:bg-transparent"
    >
      <p className="font-display text-xl font-medium tracking-tight">Admin unavailable</p>
      <p className="text-sm text-ink-soft dark:text-parchment-soft">
        The admin surface answered not-found — this means no access or no route. Check the session
        and the path, then continue from a visible section.
      </p>
      <p className="font-mono text-xs break-all tnum">
        {path} · status: {status} · last: not_found: no such endpoint
      </p>
      <Link
        to="/"
        className="inline-block rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
      >
        Back to Overview
      </Link>
    </div>
  )
}
