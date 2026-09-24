import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { GatewayClient, dashboardRetry, dashboardRetryDelay } from '../../shared/api/client.js'
import { DashboardError } from './DashboardError.js'
import { SummaryBoard } from './SummaryBoard.js'
import { validateWindow } from './window.js'

/**
 * Personal usage dashboard: the caller's owned-keys summary, computed on open.
 *
 * @remarks Proof-type: recorded. Window defaults to the trailing 7d
 * server-side; the 90d ceiling is validated client-side before fetching and
 * enforced server-side with a 400. Averages of 0.0 on empty windows render
 * the empty trio, never an error.
 *
 * @returns The usage screen.
 */
export function UsagePage(): React.JSX.Element {
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [applied, setApplied] = useState<{ from?: string; to?: string }>({})
  const [localError, setLocalError] = useState<string | null>(null)

  const dashboard = useQuery({
    queryKey: ['my-usage', applied.from ?? null, applied.to ?? null],
    queryFn: ({ signal }) => new GatewayClient().myUsage(applied.from, applied.to, { signal }),
    retry: dashboardRetry,
    retryDelay: dashboardRetryDelay,
  })

  /**
   * Validates the window and applies it to the query (empty = default).
   */
  const applyWindow = (): void => {
    const checked = validateWindow(from, to)
    setLocalError(checked.error)
    if (checked.error !== null) return
    setApplied({
      ...(checked.fromIso === undefined ? {} : { from: checked.fromIso }),
      ...(checked.toIso === undefined ? {} : { to: checked.toIso }),
    })
  }

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
      <form
        className="flex flex-wrap items-end gap-2"
        onSubmit={(e) => {
          e.preventDefault()
          applyWindow()
        }}
      >
        <div>
          <label htmlFor="usage-from" className="mb-1 block text-[13px] font-medium">
            From
          </label>
          <input
            id="usage-from"
            type="date"
            value={from}
            onChange={(e) => {
              setFrom(e.target.value)
            }}
            className="rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-[13px] tnum dark:border-parchment/15"
          />
        </div>
        <div>
          <label htmlFor="usage-to" className="mb-1 block text-[13px] font-medium">
            To
          </label>
          <input
            id="usage-to"
            type="date"
            value={to}
            onChange={(e) => {
              setTo(e.target.value)
            }}
            className="rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-[13px] tnum dark:border-parchment/15"
          />
        </div>
        <button
          type="submit"
          className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
        >
          Apply
        </button>
      </form>
      {localError === null ? null : (
        <p role="alert" className="text-sm text-danger dark:text-danger-soft">
          {localError}
        </p>
      )}
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
