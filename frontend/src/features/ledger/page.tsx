import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useShallow } from 'zustand/react/shallow'
import { GatewayClient } from '../../shared/api/client.js'
import { useAuthStore } from '../../shared/auth/store.js'

const PAGE_SIZE = 25

/**
 * Ledger page: billed totals plus the paginated audit log.
 *
 * @remarks Proof-type: recorded (real `/v1/admin/ledger/*` reads).
 *
 * @returns The ledger screen.
 */
export function LedgerPage(): React.JSX.Element {
  const { adminKey } = useAuthStore(useShallow((s) => ({ adminKey: s.adminKey })))
  const [page, setPage] = useState(0)

  const summary = useQuery({
    queryKey: ['ledger-summary', adminKey !== null],
    queryFn: ({ signal }) =>
      new GatewayClient({ token: adminKey ?? '', adminKey }).ledgerSummary({ signal }),
    enabled: adminKey !== null,
  })
  const logs = useQuery({
    queryKey: ['ledger-logs', page, adminKey !== null],
    queryFn: ({ signal }) =>
      new GatewayClient({ token: adminKey ?? '', adminKey }).ledgerLogs(page, PAGE_SIZE, {
        signal,
      }),
    enabled: adminKey !== null,
  })

  if (adminKey === null)
    return <p className="text-sm">Unlock the admin key on the Circuits page first.</p>

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold tracking-tight">Ledger</h1>
      {summary.isPending ? (
        <p role="status" className="text-sm">
          Loading summary…
        </p>
      ) : summary.error instanceof Error ? (
        <p role="alert" className="text-sm text-danger">
          {summary.error.message}
        </p>
      ) : summary.data === undefined ? null : (
        <dl className="grid grid-cols-3 gap-3">
          <div className="rounded-lg border border-ink/10 p-3 dark:border-parchment/10">
            <dt className="text-xs opacity-70">Requests</dt>
            <dd className="text-lg tnum">{summary.data.totalRequests}</dd>
          </div>
          <div className="rounded-lg border border-ink/10 p-3 dark:border-parchment/10">
            <dt className="text-xs opacity-70">Billed (µ$)</dt>
            <dd className="text-lg tnum">{summary.data.totalCostMicros}</dd>
          </div>
          <div className="rounded-lg border border-ink/10 p-3 dark:border-parchment/10">
            <dt className="text-xs opacity-70">Cache hit rate</dt>
            <dd className="text-lg tnum">{(summary.data.cacheHitRate * 100).toFixed(1)}%</dd>
          </div>
        </dl>
      )}
      {logs.isPending ? (
        <p role="status" className="text-sm">
          Loading audit log…
        </p>
      ) : logs.error instanceof Error ? (
        <p role="alert" className="text-sm text-danger">
          {logs.error.message}
        </p>
      ) : logs.data === undefined || logs.data.entries.length === 0 ? (
        <p className="text-sm opacity-70">
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
              {logs.data.entries.map((e) => (
                <tr key={e.requestId} className="border-t border-ink/10 dark:border-parchment/10">
                  <td className="py-2 font-mono text-xs">{e.requestId}</td>
                  <td className="py-2 text-xs">{e.model}</td>
                  <td className="py-2 tnum">{e.costMicros}</td>
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
              disabled={logs.data.entries.length < PAGE_SIZE}
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
