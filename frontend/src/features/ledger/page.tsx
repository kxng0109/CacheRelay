import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { GatewayClient } from '../../shared/api/client.js'

const PAGE_SIZE = 25

/**
 * Billed totals plus the paginated audit log.
 *
 * @remarks Proof-type: recorded (real `/v1/admin/ledger/*` reads).
 * Summary registers on top, dense table centre, receipt inspector rail.
 * Row selection is local UI state.
 *
 * @param props - The admin key for admin-surface calls.
 * @returns The ledger board.
 */
function LedgerBoard(): React.JSX.Element {
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<string | null>(null)

  const summary = useQuery({
    queryKey: ['ledger-summary'],
    queryFn: ({ signal }) => new GatewayClient().ledgerSummary({ signal }),
  })
  const logs = useQuery({
    queryKey: ['ledger-logs', page],
    queryFn: ({ signal }) => new GatewayClient().ledgerLogs(page, PAGE_SIZE, { signal }),
  })

  const entries = logs.data?.content ?? []
  const inspected = entries.find((e) => e.requestId === selected) ?? null

  return (
    <div className="grid gap-6 lg:grid-cols-[1fr_280px]">
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
            <div className="min-h-19 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
              <dt className="text-xs text-ink-soft dark:text-parchment-soft">Requests</dt>
              <dd className="font-mono text-lg tnum">{summary.data.totalRequests}</dd>
            </div>
            <div className="min-h-19 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
              <dt className="text-xs text-ink-soft dark:text-parchment-soft">Billed (µ$)</dt>
              <dd className="font-mono text-lg tnum">{summary.data.totalCostUsdMicros}</dd>
            </div>
            <div className="min-h-19 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
              <dt className="text-xs text-ink-soft dark:text-parchment-soft">Avg duration (ms)</dt>
              <dd className="font-mono text-lg tnum">
                {summary.data.averageDurationMs.toFixed(1)}
              </dd>
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
        ) : logs.data === undefined || entries.length === 0 ? (
          <p className="text-sm text-ink-soft dark:text-parchment-soft">
            No ledger entries yet. Send traffic through the gateway to populate the audit log.
          </p>
        ) : (
          <>
            <table className="w-full text-left text-sm">
              <caption className="sr-only">Audit log entries</caption>
              <thead>
                <tr className="font-mono text-[11px] text-ink-soft dark:text-parchment-soft">
                  <th scope="col" className="py-2 pr-3 font-medium">
                    Request
                  </th>
                  <th scope="col" className="py-2 pr-3 font-medium">
                    Model
                  </th>
                  <th scope="col" className="py-2 pr-3 text-right font-medium">
                    Cost (µ$)
                  </th>
                  <th scope="col" className="py-2 text-right font-medium">
                    Created
                  </th>
                </tr>
              </thead>
              <tbody>
                {entries.map((e) => (
                  <tr
                    key={e.requestId}
                    aria-selected={e.requestId === selected}
                    onClick={() => {
                      setSelected(e.requestId === selected ? null : e.requestId)
                    }}
                    className={`cursor-pointer border-t border-ink/10 dark:border-parchment/10 ${
                      e.requestId === selected ? 'bg-ink/4 dark:bg-parchment/6' : ''
                    }`}
                  >
                    <td className="py-2 pr-3 font-mono text-xs">{e.requestId}</td>
                    <td className="py-2 pr-3 text-xs">{e.model}</td>
                    <td className="py-2 pr-3 text-right font-mono text-xs tnum">
                      {e.costUsdMicros}
                    </td>
                    <td className="py-2 text-right text-xs tnum">{e.createdAt}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="flex items-center justify-between gap-2">
              <button
                type="button"
                disabled={page === 0}
                onClick={() => {
                  setPage((p) => Math.max(0, p - 1))
                }}
                className="rounded-md border border-ink/15 px-3 py-2 text-xs disabled:opacity-50 dark:border-parchment/15"
              >
                Previous [p]
              </button>
              <p className="p-2 font-mono text-xs tnum" role="status">
                Page {page + 1}
                {logs.data.totalPages > 0 ? ` of ${String(logs.data.totalPages)}` : null}
              </p>
              <button
                type="button"
                disabled={!logs.data.hasNext}
                onClick={() => {
                  setPage((p) => p + 1)
                }}
                className="rounded-md border border-ink/15 px-3 py-2 text-xs disabled:opacity-50 dark:border-parchment/15"
              >
                Next [n]
              </button>
            </div>
          </>
        )}
      </div>
      <aside aria-label="Receipt inspector" className="space-y-3">
        {inspected === null ? (
          <p className="text-xs text-ink-soft dark:text-parchment-soft">
            Select a row to inspect its receipt.
          </p>
        ) : (
          <div className="space-y-3 rounded-xl border border-ink/10 bg-cream p-4 dark:border-parchment/10 dark:bg-transparent">
            <h2 className="font-mono text-sm break-all">{inspected.requestId}</h2>
            <dl className="space-y-2 text-xs">
              <div className="flex justify-between gap-3">
                <dt className="text-ink-soft dark:text-parchment-soft">Model</dt>
                <dd>{inspected.model}</dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-ink-soft dark:text-parchment-soft">Cost (µ$)</dt>
                <dd className="tnum">{inspected.costUsdMicros}</dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-ink-soft dark:text-parchment-soft">Created</dt>
                <dd className="tnum">{inspected.createdAt}</dd>
              </div>
            </dl>
          </div>
        )}
      </aside>
    </div>
  )
}

/**
 * Ledger page: billed totals plus the paginated audit log.
 *
 * @remarks Behind the admin route guard; no gate lives here.
 *
 * @returns The ledger screen.
 */
export function LedgerPage(): React.JSX.Element {
  return (
    <div className="space-y-4">
      <h1 className="font-display text-2xl font-medium tracking-tight">Ledger</h1>
      <LedgerBoard />
    </div>
  )
}
