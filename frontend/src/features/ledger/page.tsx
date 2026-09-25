import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { GatewayClient } from '../../shared/api/client.js'
import { EmptyTrio } from '../../shared/components/EmptyTrio.js'
import { TableScroll } from '../../shared/components/TableScroll.js'
import {
  formatCount,
  formatDurationMs,
  formatMicros,
  formatShortDate,
  formatUsd,
} from '../../shared/utils/format.js'
import { RunInspector } from './RunInspector.js'

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

  /**
   * Selects a receipt row. Shared by pointer and keyboard so audit rows
   * are never click-only.
   *
   * @param requestId - Row to select, or toggle off when already selected.
   */
  const selectRow = (requestId: string): void => {
    setSelected(requestId === selected ? null : requestId)
  }
  const [tableFilter, setTableFilter] = useState('')
  const [jump, setJump] = useState('')
  const filterQuery = tableFilter.trim().toLowerCase()
  const visible =
    filterQuery.length === 0
      ? entries
      : entries.filter(
          (e) =>
            e.requestId.toLowerCase().includes(filterQuery) ||
            e.model.toLowerCase().includes(filterQuery),
        )

  return (
    <div className="space-y-6">
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
          <dl className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            <div className="min-h-19 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
              <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">Requests</dt>
              <dd className="font-mono text-lg tnum">{formatCount(summary.data.totalRequests)}</dd>
            </div>
            <div className="min-h-19 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
              <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">Billed</dt>
              <dd className="font-mono text-lg tnum">
                {formatUsd(summary.data.totalCostUsdMicros)}
              </dd>
            </div>
            <div className="min-h-19 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
              <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">
                Avg duration (ms)
              </dt>
              <dd className="font-mono text-lg tnum">
                {formatDurationMs(summary.data.averageDurationMs)}
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
          <EmptyTrio
            title="No entries yet"
            cue="Send traffic through the gateway to populate the audit log. Each request lands here with model, cost, and timestamp."
            action={{ label: 'Open playground', to: '/playground' }}
          />
        ) : (
          <>
            <div className="flex flex-wrap items-center gap-2">
              <label htmlFor="ledger-filter" className="sr-only">
                Filter audit log by request id or model
              </label>
              <input
                id="ledger-filter"
                type="search"
                value={tableFilter}
                onChange={(e) => {
                  setTableFilter(e.target.value)
                }}
                placeholder="Filter"
                className="w-60 rounded-md border border-ink/15 bg-transparent px-3 py-2 text-[13px] dark:border-parchment/15"
              />
              <p
                role="status"
                className="font-mono text-xs text-ink-soft tnum dark:text-parchment-soft"
              >
                {filterQuery.length === 0
                  ? `${String(entries.length)} on this page`
                  : `${String(visible.length)} of ${String(entries.length)} match`}
              </p>
              <span className="flex-1" />
              {filterQuery.length === 0 ? null : (
                <button
                  type="button"
                  onClick={() => {
                    setTableFilter('')
                  }}
                  className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
                >
                  Clear
                </button>
              )}
            </div>
            {visible.length === 0 ? (
              <p className="text-sm text-ink-soft dark:text-parchment-soft">
                No entries match this filter.
              </p>
            ) : (
              <>
                <TableScroll>
                  <table className="w-full text-left text-sm">
                    <caption className="sr-only">Audit log entries</caption>
                    <thead className="sticky top-0 bg-paper dark:bg-night">
                      <tr className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
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
                        <th scope="col" className="py-2 pl-1 font-medium">
                          <span className="sr-only">Open receipt</span>
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {visible.map((e) => (
                        <tr
                          key={e.requestId}
                          className={`border-t border-ink/10 hover:bg-ink/3 dark:border-parchment/10 dark:hover:bg-parchment/4 ${
                            e.requestId === selected ? 'bg-ink/4 dark:bg-parchment/6' : ''
                          }`}
                        >
                          <td
                            className="max-w-44 truncate py-2 pr-3 font-mono text-[13px]"
                            title={e.requestId}
                          >
                            {e.requestId}
                          </td>
                          <td className="max-w-40 truncate py-2 pr-3 text-[13px]" title={e.model}>
                            {e.model}
                          </td>
                          <td className="py-2 pr-3 text-right font-mono text-[13px] tnum">
                            {e.costUsdMicros === 0 ? (
                              <span className="text-ink-soft dark:text-parchment-soft">free</span>
                            ) : (
                              formatMicros(e.costUsdMicros)
                            )}
                          </td>
                          <td className="py-2 text-right text-[13px] tnum" title={e.createdAt}>
                            {formatShortDate(e.createdAt)}
                          </td>
                          <td className="py-2 pl-1 text-right">
                            <button
                              type="button"
                              onClick={() => {
                                selectRow(e.requestId)
                              }}
                              aria-label={`Inspect receipt ${e.requestId}`}
                              className="rounded px-1 text-ink-soft dark:text-parchment-soft"
                            >
                              <span aria-hidden="true">›</span>
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </TableScroll>
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <button
                    type="button"
                    disabled={page === 0}
                    onClick={() => {
                      setPage((p) => Math.max(0, p - 1))
                    }}
                    className="rounded-md border border-ink/15 px-3 py-2 text-[13px] disabled:cursor-not-allowed disabled:border-ink-soft disabled:text-ink-soft dark:border-parchment/15 dark:disabled:border-parchment-soft dark:disabled:text-parchment-soft"
                  >
                    Previous
                  </button>
                  <p className="p-2 font-mono text-[13px] tnum" role="status">
                    Page {page + 1}
                    {logs.data.totalPages > 0 ? ` of ${String(logs.data.totalPages)}` : null}
                  </p>
                  <button
                    type="button"
                    disabled={!logs.data.hasNext}
                    onClick={() => {
                      setPage((p) => p + 1)
                    }}
                    className="rounded-md border border-ink/15 px-3 py-2 text-[13px] disabled:cursor-not-allowed disabled:border-ink-soft disabled:text-ink-soft dark:border-parchment/15 dark:disabled:border-parchment-soft dark:disabled:text-parchment-soft"
                  >
                    Next
                  </button>
                  <span className="flex-1" />
                  {logs.data.totalPages > 1 ? (
                    <form
                      className="flex items-center gap-2"
                      onSubmit={(e) => {
                        e.preventDefault()
                        const n = Number.parseInt(jump, 10)
                        if (Number.isInteger(n)) {
                          setPage(Math.min(Math.max(n - 1, 0), logs.data.totalPages - 1))
                          setJump('')
                        }
                      }}
                    >
                      <label htmlFor="ledger-jump" className="sr-only">
                        Jump to page
                      </label>
                      <input
                        id="ledger-jump"
                        inputMode="numeric"
                        value={jump}
                        onChange={(e) => {
                          setJump(e.target.value.replace(/[^0-9]/g, ''))
                        }}
                        placeholder={`1 to ${String(logs.data.totalPages)}`}
                        className="w-20 rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-[13px] tnum dark:border-parchment/15"
                      />
                      <button
                        type="submit"
                        className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
                      >
                        Go
                      </button>
                    </form>
                  ) : null}
                </div>
              </>
            )}
          </>
        )}
      </div>
      {inspected === null ? (
        <p className="text-[13px] text-ink-soft dark:text-parchment-soft">
          Select a row to inspect its receipt.
        </p>
      ) : (
        <RunInspector
          rows={visible}
          selected={inspected.requestId}
          onSelect={setSelected}
          onClose={() => {
            setSelected(null)
          }}
        />
      )}
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
