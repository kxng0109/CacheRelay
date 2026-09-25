import type { DashboardView } from '../../shared/api/types.js'
import { EmptyTrio } from '../../shared/components/EmptyTrio.js'
import { TableScroll } from '../../shared/components/TableScroll.js'
import {
  formatCount,
  formatDurationMs,
  formatMicros,
  formatUsd,
} from '../../shared/utils/format.js'

/**
 * Formats an ISO instant as a relative staleness note.
 *
 * @param iso - ISO-8601 instant, or null when the backend sent no header.
 * @param now - Current epoch millis (injectable for tests).
 * @returns Relative form (`"42s ago"`), or null when unparseable.
 */
export function formatRelativeTime(iso: string | null, now: number = Date.now()): string | null {
  if (iso === null) return null
  const t = new Date(iso).getTime()
  if (Number.isNaN(t)) return null
  const seconds = Math.max(0, Math.round((now - t) / 1000))
  if (seconds < 60) return `${String(seconds)}s ago`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${String(minutes)}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 48) return `${String(hours)}h ago`
  return `${String(Math.floor(hours / 24))}d ago`
}

interface SummaryBoardProps {
  /** Computed view: summary plus freshness coordinates. */
  view: DashboardView
}

/**
 * Recorded-proof board for usage summaries (personal and admin drill-down).
 *
 * @remarks Shared by the personal dashboard and the admin user drill-down
 * so both screens prove the same numbers the same way: freshness from the
 * `X-Dashboard-Generated-At` header, exact-decimal cost verbatim, tabular
 * numerals, and an instructive empty state instead of an error on empty
 * windows (averages may legitimately be 0.0).
 *
 * @param props - The view to render.
 * @returns Tiles plus breakdown tables, or the empty trio.
 */
export function SummaryBoard({ view }: SummaryBoardProps): React.JSX.Element {
  const { summary } = view

  if (summary.totalRequests === 0) {
    return (
      <EmptyTrio
        title="No usage in range"
        cue="0 requests · 0 tokens · $0.00. Usage lands here after the first request in range."
        action={{ label: 'Open playground', to: '/playground' }}
      />
    )
  }

  return (
    <div className="space-y-4">
      {view.generatedAt === null ? null : (
        <p
          role="status"
          title={view.generatedAt}
          className="font-mono text-xs text-ink-soft tnum dark:text-parchment-soft"
        >
          {`Updated ${formatRelativeTime(view.generatedAt) ?? 'recently'}`}
        </p>
      )}
      <dl className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <div className="min-h-19 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
          <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">Requests</dt>
          <dd className="font-mono text-lg tnum">{formatCount(summary.totalRequests)}</dd>
        </div>
        <div className="min-h-19 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
          <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">Prompt tokens</dt>
          <dd className="font-mono text-lg tnum">{formatCount(summary.totalPromptTokens)}</dd>
        </div>
        <div className="min-h-19 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
          <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">Completion tokens</dt>
          <dd className="font-mono text-lg tnum">{formatCount(summary.totalCompletionTokens)}</dd>
        </div>
        <div className="min-h-19 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
          <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">Billed</dt>
          <dd className="font-mono text-lg tnum" title={summary.totalCostUsd}>
            {formatUsd(summary.totalCostUsdMicros)}
          </dd>
        </div>
        <div className="min-h-19 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
          <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">Avg duration (ms)</dt>
          <dd className="font-mono text-lg tnum">{formatDurationMs(summary.averageDurationMs)}</dd>
        </div>
      </dl>
      {summary.byModel.length === 0 ? null : (
        <TableScroll>
          <table className="w-full text-left text-sm">
            <caption className="sr-only">Usage by model</caption>
            <thead>
              <tr className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
                <th scope="col" className="py-2 pr-3 font-medium">
                  Model
                </th>
                <th scope="col" className="py-2 pr-3 text-right font-medium">
                  Requests
                </th>
                <th scope="col" className="py-2 text-right font-medium">
                  Cost (µ$)
                </th>
              </tr>
            </thead>
            <tbody>
              {summary.byModel.map((row) => (
                <tr
                  key={`${row.provider}/${row.model}`}
                  className="border-t border-ink/10 dark:border-parchment/10"
                >
                  <td
                    className="max-w-44 truncate py-2 pr-3 text-[13px]"
                    title={`${row.provider}/${row.model}`}
                  >
                    {row.provider}/{row.model}
                  </td>
                  <td className="py-2 pr-3 text-right font-mono text-[13px] tnum">
                    {formatCount(row.totalRequests)}
                  </td>
                  <td className="py-2 text-right font-mono text-[13px] tnum">
                    {formatMicros(row.totalCostUsdMicros)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </TableScroll>
      )}
      {summary.byProvider.length === 0 ? null : (
        <TableScroll>
          <table className="w-full text-left text-sm">
            <caption className="sr-only">Usage by provider</caption>
            <thead>
              <tr className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
                <th scope="col" className="py-2 pr-3 font-medium">
                  Provider
                </th>
                <th scope="col" className="py-2 pr-3 text-right font-medium">
                  Requests
                </th>
                <th scope="col" className="py-2 text-right font-medium">
                  Cost (µ$)
                </th>
              </tr>
            </thead>
            <tbody>
              {summary.byProvider.map((row) => (
                <tr key={row.provider} className="border-t border-ink/10 dark:border-parchment/10">
                  <td className="py-2 pr-3 text-[13px]">{row.provider}</td>
                  <td className="py-2 pr-3 text-right font-mono text-[13px] tnum">
                    {formatCount(row.totalRequests)}
                  </td>
                  <td className="py-2 text-right font-mono text-[13px] tnum">
                    {formatMicros(row.totalCostUsdMicros)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </TableScroll>
      )}
    </div>
  )
}
