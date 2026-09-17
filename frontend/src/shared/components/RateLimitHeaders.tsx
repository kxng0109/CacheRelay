import type { RateLimitSnapshot } from '../api/types.js'

interface RateLimitHeadersProps {
  snapshot: RateLimitSnapshot
}

/**
 * Surfaces `X-RateLimit-*` values from the last gateway response.
 *
 * @remarks Proof-type: recorded (echoes real response headers, never synthetic).
 *
 * @param props - The parsed rate-limit snapshot.
 * @returns An inline metric strip, or nothing when the gateway sent no headers.
 */
export function RateLimitHeaders({ snapshot }: RateLimitHeadersProps): React.JSX.Element | null {
  if (
    snapshot.limit === null &&
    snapshot.remaining === null &&
    snapshot.reset === null &&
    snapshot.retryAfter === null
  ) {
    return null
  }
  const cell = (label: string, value: number | null): React.JSX.Element => (
    <span className="text-xs tnum">
      {label}: {value ?? '—'}
    </span>
  )
  return (
    <p role="status" aria-label="Rate limit status" className="flex flex-wrap gap-4">
      {cell('Limit', snapshot.limit)}
      {cell('Remaining', snapshot.remaining)}
      {cell('Reset (s)', snapshot.reset)}
      {cell('Retry after (s)', snapshot.retryAfter)}
    </p>
  )
}
