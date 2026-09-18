import { useEffect, useState } from 'react'
import type { RateLimitSnapshot } from '../api/types.js'

interface RateLimitHeadersProps {
  snapshot: RateLimitSnapshot
}

/**
 * Surfaces `X-RateLimit-*` values from the last gateway response.
 *
 * @remarks Proof-type: recorded (echoes real response headers, never synthetic).
 * The row shows the binding dimension (`dimension` caption: request vs token
 * quota) selected by `parseRateLimit`. The reset cell counts down live from
 * the reset epoch: a 1s interval re-renders only while a future reset is
 * displayed, and cleanup runs on unmount and on every reset change. No
 * tab-visibility pausing — the countdown re-derives from the epoch on every
 * tick, so background throttling self-corrects on return.
 *
 * @param props - The parsed rate-limit snapshot.
 * @returns An inline metric strip, or nothing when the gateway sent no headers.
 */
export function RateLimitHeaders({ snapshot }: RateLimitHeadersProps): React.JSX.Element | null {
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    if (snapshot.reset === null) return
    if (snapshot.reset * 1000 <= Date.now()) return
    const id = setInterval(() => {
      setNow(Date.now())
    }, 1000)
    return () => {
      clearInterval(id)
    }
  }, [snapshot.reset])

  if (
    snapshot.limit === null &&
    snapshot.remaining === null &&
    snapshot.reset === null &&
    snapshot.retryAfter === null
  ) {
    return null
  }
  const resetLabel =
    snapshot.reset === null
      ? '—'
      : `${String(Math.max(0, snapshot.reset - Math.floor(now / 1000)))}s`
  const cell = (label: string, value: number | null): React.JSX.Element => (
    <span className="text-xs tnum">
      {label}: {value ?? '—'}
    </span>
  )
  return (
    <p role="status" aria-label="Rate limit status" className="flex flex-wrap gap-4">
      <span className="text-xs">
        {snapshot.dimension === 'TPM' ? 'Token quota' : 'Request quota'}
      </span>
      {cell('Limit', snapshot.limit)}
      {cell('Remaining', snapshot.remaining)}
      <span className="text-xs tnum">Resets in: {resetLabel}</span>
      {cell('Retry after (s)', snapshot.retryAfter)}
    </p>
  )
}
