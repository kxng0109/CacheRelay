import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { GatewayClient, dashboardRetry, dashboardRetryDelay } from '../../shared/api/client.js'
import { DashboardError } from './DashboardError.js'
import { SummaryBoard } from './SummaryBoard.js'
import { WindowPicker } from './WindowPicker.js'
import { presetBounds } from './window.js'

/**
 * Personal usage dashboard: the caller's owned-keys summary, computed on open.
 *
 * @remarks Proof-type: recorded. The picker defaults to the past 7 days
 * (matching the server trailing default); the 90d ceiling is validated
 * client-side before fetching and enforced server-side with a 400.
 * Averages of 0.0 on empty windows render the empty trio, never an error.
 *
 * @returns The usage screen.
 */
export function UsagePage(): React.JSX.Element {
  const [applied, setApplied] = useState<{ from?: string; to?: string }>(() => {
    const week = presetBounds('past-7d')
    return { from: week.fromIso, to: week.toIso }
  })

  const dashboard = useQuery({
    queryKey: ['my-usage', applied.from ?? null, applied.to ?? null],
    queryFn: ({ signal }) => new GatewayClient().myUsage(applied.from, applied.to, { signal }),
    retry: dashboardRetry,
    retryDelay: dashboardRetryDelay,
  })

  const error = dashboard.error

  return (
    <div className="space-y-4">
      <div className="space-y-1">
        <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
          <span aria-hidden="true" className="mr-1 text-ember">
            ❯
          </span>
          usage
        </p>
        <h1 className="font-display text-3xl font-medium tracking-tight">Usage</h1>
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          Your owned keys only. The owner scope comes from the session.
        </p>
      </div>
      <WindowPicker
        idPrefix="usage"
        submitLabel="Apply"
        onApply={(bounds) => {
          setApplied({ ...bounds })
        }}
      />
      {dashboard.isPending ? (
        <p role="status" className="text-sm">
          Loading usage…
        </p>
      ) : error instanceof Error ? (
        <DashboardError error={error} onRetry={() => void dashboard.refetch()} />
      ) : dashboard.data === undefined ? null : (
        <SummaryBoard view={dashboard.data} />
      )}
    </div>
  )
}
