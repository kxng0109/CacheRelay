import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useParams } from 'react-router'
import {
  ApiError,
  GatewayClient,
  dashboardRetry,
  dashboardRetryDelay,
} from '../../shared/api/client.js'
import { AdminUnavailable } from '../../shared/components/AdminUnavailable.js'
import { DashboardError } from '../usage/DashboardError.js'
import { SummaryBoard } from '../usage/SummaryBoard.js'
import { validateWindow } from '../usage/window.js'

/**
 * Admin user drill-down: one account's usage, audit-logged server-side.
 *
 * @remarks Same shape and freshness headers as the personal view. Never
 * exposed to non-admins (route guard); a stealth 404 renders
 * admin-unavailable without distinguishing no-access from no-route.
 *
 * @returns The user drill-down screen.
 */
export function UserLedgerPage(): React.JSX.Element {
  const params = useParams()
  const [userId, setUserId] = useState(params.userId ?? '')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [applied, setApplied] = useState<{ userId: string; from?: string; to?: string } | null>(
    params.userId === undefined || params.userId === '' ? null : { userId: params.userId },
  )
  const [localError, setLocalError] = useState<string | null>(null)

  const drilldown = useQuery({
    queryKey: [
      'admin-user-usage',
      applied?.userId ?? null,
      applied?.from ?? null,
      applied?.to ?? null,
    ],
    queryFn: ({ signal }) => {
      if (applied === null) throw new Error('Enter an account id first.')
      return new GatewayClient().userUsage(applied.userId, applied.from, applied.to, { signal })
    },
    enabled: applied !== null,
    retry: dashboardRetry,
    retryDelay: dashboardRetryDelay,
  })

  /**
   * Validates the account plus window and applies them to the query.
   */
  const apply = (): void => {
    const id = userId.trim()
    if (id === '') {
      setLocalError('Enter an account id to inspect.')
      return
    }
    const checked = validateWindow(from, to)
    setLocalError(checked.error)
    if (checked.error !== null) return
    setApplied({
      userId: id,
      ...(checked.fromIso === undefined ? {} : { from: checked.fromIso }),
      ...(checked.toIso === undefined ? {} : { to: checked.toIso }),
    })
  }

  const error = drilldown.error
  const stealth = error instanceof ApiError && error.status === 404

  return (
    <div className="space-y-4">
      <div className="space-y-1">
        <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
          <span aria-hidden="true" className="mr-1 text-ember">
            ❯
          </span>
          ledger · user
        </p>
        <h1 className="font-display text-3xl font-medium tracking-tight">User usage</h1>
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          Admin drill-down. Every access is audit-logged server-side.
        </p>
      </div>
      <form
        className="flex flex-wrap items-end gap-2"
        onSubmit={(e) => {
          e.preventDefault()
          apply()
        }}
      >
        <div>
          <label htmlFor="drilldown-user" className="mb-1 block text-[13px] font-medium">
            Account id
          </label>
          <input
            id="drilldown-user"
            type="text"
            value={userId}
            onChange={(e) => {
              setUserId(e.target.value)
            }}
            placeholder="UUID"
            autoComplete="off"
            className="w-72 rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-[13px] tnum dark:border-parchment/15"
          />
        </div>
        <div>
          <label htmlFor="drilldown-from" className="mb-1 block text-[13px] font-medium">
            From
          </label>
          <input
            id="drilldown-from"
            type="date"
            value={from}
            onChange={(e) => {
              setFrom(e.target.value)
            }}
            className="rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-[13px] tnum dark:border-parchment/15"
          />
        </div>
        <div>
          <label htmlFor="drilldown-to" className="mb-1 block text-[13px] font-medium">
            To
          </label>
          <input
            id="drilldown-to"
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
          Inspect
        </button>
      </form>
      {localError === null ? null : (
        <p role="alert" className="text-sm text-danger dark:text-danger-soft">
          {localError}
        </p>
      )}
      {applied === null ? (
        <p className="text-[13px] text-ink-soft dark:text-parchment-soft">
          Enter an account id above. Summaries compute on open, nothing is precomputed.
        </p>
      ) : drilldown.isPending ? (
        <p role="status" className="text-sm">
          Loading account usage…
        </p>
      ) : error instanceof Error ? (
        stealth ? (
          <AdminUnavailable path={`/v1/admin/ledger/user/${applied.userId}/summary`} status={404} />
        ) : (
          <DashboardError error={error} onRetry={() => void drilldown.refetch()} />
        )
      ) : drilldown.data === undefined ? null : (
        <SummaryBoard view={drilldown.data} />
      )}
    </div>
  )
}
