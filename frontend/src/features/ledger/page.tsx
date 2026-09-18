import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useShallow } from 'zustand/react/shallow'
import { GatewayClient } from '../../shared/api/client.js'
import { useAuthStore } from '../../shared/auth/store.js'

const PAGE_SIZE = 25

interface LedgerBoardProps {
  /** Master admin key; the gate guarantees non-null before mounting. */
  adminKey: string
}

/**
 * Billed totals plus the paginated audit log.
 *
 * @remarks Proof-type: recorded (real `/v1/admin/ledger/*` reads).
 *
 * @param props - The admin key for admin-surface calls.
 * @returns The ledger board.
 */
function LedgerBoard({ adminKey }: LedgerBoardProps): React.JSX.Element {
  const [page, setPage] = useState(0)

  const summary = useQuery({
    queryKey: ['ledger-summary'],
    queryFn: ({ signal }) =>
      new GatewayClient({ token: adminKey, adminKey }).ledgerSummary({ signal }),
  })
  const logs = useQuery({
    queryKey: ['ledger-logs', page],
    queryFn: ({ signal }) =>
      new GatewayClient({ token: adminKey, adminKey }).ledgerLogs(page, PAGE_SIZE, { signal }),
  })

  return (
    <div className="space-y-4">
      {summary.isPending ? (
        <p role="status" className="text-sm">
          Loading summary…
        </p>
      ) : summary.error instanceof Error ? (
        <p role="alert" className="text-sm text-danger dark:text-danger-soft">
          {summary.error.message}
        </p>
      ) : summary.data === undefined ? null : (
        <dl className="grid grid-cols-3 gap-3">
          <div className="rounded-lg border border-ink/10 p-3 dark:border-parchment/10">
            <dt className="text-xs text-ink-soft dark:text-parchment-soft">Requests</dt>
            <dd className="text-lg tnum">{summary.data.totalRequests}</dd>
          </div>
          <div className="rounded-lg border border-ink/10 p-3 dark:border-parchment/10">
            <dt className="text-xs text-ink-soft dark:text-parchment-soft">Billed (µ$)</dt>
            <dd className="text-lg tnum">{summary.data.totalCostUsdMicros}</dd>
          </div>
          <div className="rounded-lg border border-ink/10 p-3 dark:border-parchment/10">
            <dt className="text-xs text-ink-soft dark:text-parchment-soft">Avg duration (ms)</dt>
            <dd className="text-lg tnum">{summary.data.averageDurationMs.toFixed(1)}</dd>
          </div>
        </dl>
      )}
      {logs.isPending ? (
        <p role="status" className="text-sm">
          Loading audit log…
        </p>
      ) : logs.error instanceof Error ? (
        <p role="alert" className="text-sm text-danger dark:text-danger-soft">
          {logs.error.message}
        </p>
      ) : logs.data === undefined || logs.data.content.length === 0 ? (
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          No ledger entries yet. Send traffic through the gateway to populate the audit log.
        </p>
      ) : (
        <>
          <table className="w-full text-left text-sm">
            <caption className="sr-only">Audit log entries</caption>
            <thead>
              <tr>
                <th scope="col">Request</th>
                <th scope="col">Model</th>
                <th scope="col">Cost (µ$)</th>
                <th scope="col">Created</th>
              </tr>
            </thead>
            <tbody>
              {logs.data.content.map((e) => (
                <tr key={e.requestId} className="border-t border-ink/10 dark:border-parchment/10">
                  <td className="py-2 font-mono text-xs">{e.requestId}</td>
                  <td className="py-2 text-xs">{e.model}</td>
                  <td className="py-2 tnum">{e.costUsdMicros}</td>
                  <td className="py-2 text-xs tnum">{e.createdAt}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="flex gap-2">
            <button
              type="button"
              disabled={page === 0}
              onClick={() => {
                setPage((p) => Math.max(0, p - 1))
              }}
              className="rounded-md border border-ink/15 px-3 py-2 text-xs disabled:opacity-50 dark:border-parchment/15"
            >
              Previous
            </button>
            <p className="p-2 text-xs tnum" role="status">
              Page {page + 1}
            </p>
            <button
              type="button"
              disabled={!logs.data.hasNext}
              onClick={() => {
                setPage((p) => p + 1)
              }}
              className="rounded-md border border-ink/15 px-3 py-2 text-xs disabled:opacity-50 dark:border-parchment/15"
            >
              Next
            </button>
          </div>
        </>
      )}
    </div>
  )
}

/**
 * Ledger page: billed totals plus the paginated audit log.
 *
 * @returns The ledger screen.
 */
export function LedgerPage(): React.JSX.Element {
  const { adminKey } = useAuthStore(useShallow((s) => ({ adminKey: s.adminKey })))

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold tracking-tight">Ledger</h1>
      {adminKey === null ? (
        <p className="text-sm">Unlock the admin key on the Circuits page first.</p>
      ) : (
        <LedgerBoard adminKey={adminKey} />
      )}
    </div>
  )
}
