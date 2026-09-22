import { Link } from 'react-router'
import type { LedgerSummary } from '../../shared/api/types.js'
import { formatCount, formatUsd } from '../../shared/utils/format.js'

const TOP_N = 5

/**
 * Top models by billed requests, with provider totals.
 *
 * @remarks Proof-type: recorded — reads the shared `ledger-summary`
 * payload, no new fetch. Ranks by request count; costs use the same
 * significant-decimal money format as every billed cell.
 *
 * @param props - Ledger summary owned by the overview page.
 * @returns The top-models section, or nothing without model rows.
 */
export function TopModels({ summary }: { summary: LedgerSummary }): React.JSX.Element | null {
  if (summary.byModel.length === 0) return null
  const ranked = [...summary.byModel]
    .sort((a, b) => b.totalRequests - a.totalRequests)
    .slice(0, TOP_N)
  return (
    <section aria-label="Top models" className="space-y-2">
      <div className="flex items-baseline justify-between gap-2">
        <h2 className="font-display text-xl font-medium tracking-tight">Top models</h2>
        <Link to="/ledger" className="font-mono text-xs underline">
          Explore ledger
        </Link>
      </div>
      <ol className="divide-y divide-ink/10 rounded-xl border border-ink/10 bg-cream dark:divide-parchment/10 dark:border-parchment/10 dark:bg-transparent">
        {ranked.map((m, i) => (
          <li key={m.model} className="flex items-baseline gap-3 px-4 py-2 text-sm">
            <span
              aria-hidden="true"
              className="font-mono text-xs text-ink-soft tnum dark:text-parchment-soft"
            >
              {i + 1}
            </span>
            <span className="min-w-0 flex-1 truncate font-mono text-[13px]" title={m.model}>
              {m.model}
            </span>
            <span className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
              {m.provider}
            </span>
            <span className="font-mono text-[13px] tnum">{formatCount(m.totalRequests)}</span>
            <span className="w-20 text-right font-mono text-[13px] tnum">
              {formatUsd(m.totalCostUsdMicros)}
            </span>
          </li>
        ))}
      </ol>
    </section>
  )
}
