import { Link } from 'react-router'
import type { LedgerSummary } from '../../shared/api/types.js'

/**
 * Overview stat strip: five live cells plus a methodology line with
 * Explore links. Translated from the activity dashboard pattern every
 * major gateway console converges on, rebuilt in terminal voice.
 *
 * @remarks Cells render em dashes for anything unknown, never zeros.
 * Billed shows the backend `totalCostUsd` string verbatim per the
 * contract — dividing micros in the client would lose precision.
 *
 * @param props - Ledger summary, live RPS, and a retry action.
 * @returns The stat strip section.
 */
export function StatStrip({
  summary,
  liveRps,
  onRetry,
}: {
  summary: LedgerSummary
  liveRps: number | null
  onRetry: () => void
}): React.JSX.Element {
  // Arrays are contractually present, but a drifted gateway must degrade
  // to dashes, never throw inside the shell.
  const models = Array.isArray(summary.byModel) ? summary.byModel : []
  const providers = Array.isArray(summary.byProvider) ? summary.byProvider : []
  const top =
    models.length === 0
      ? null
      : models.reduce((a, b) => (b.totalRequests > a.totalRequests ? b : a))

  return (
    <section aria-label="Gateway totals" className="space-y-2">
      <dl className="grid gap-3 sm:grid-cols-5">
        <div className="min-h-19 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
          <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">Requests</dt>
          <dd className="font-mono text-lg tnum">{summary.totalRequests}</dd>
        </div>
        <div className="min-h-19 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
          <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">Billed</dt>
          <dd className="font-mono text-lg tnum">${summary.totalCostUsd}</dd>
        </div>
        <div className="min-h-19 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
          <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">Tokens</dt>
          <dd className="font-mono text-lg tnum">{summary.totalTokens}</dd>
        </div>
        <div className="min-h-19 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
          <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">Top model</dt>
          <dd
            className="max-w-full truncate font-mono text-lg"
            title={
              top === null
                ? 'No traffic yet'
                : `${top.model} · ${String(top.totalRequests)} requests`
            }
          >
            {top === null ? '—' : top.model}
          </dd>
        </div>
        <div className="min-h-19 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
          <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">Live RPS</dt>
          <dd className="font-mono text-lg tnum">{liveRps === null ? '—' : liveRps.toFixed(1)}</dd>
        </div>
      </dl>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
        <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
          Across {String(models.length)} models · {String(providers.length)} providers · totals
          since ledger start
        </p>
        <span className="flex-1" />
        <Link
          to="/ledger"
          className="rounded-md border border-ink/15 px-3 py-2 font-mono text-xs dark:border-parchment/15"
        >
          Explore ledger
        </Link>
        <Link
          to="/observability"
          className="rounded-md border border-ink/15 px-3 py-2 font-mono text-xs dark:border-parchment/15"
        >
          Explore probes
        </Link>
        <button
          type="button"
          onClick={onRetry}
          className="rounded-md border border-ink/15 px-3 py-2 font-mono text-xs dark:border-parchment/15"
        >
          Retry totals
        </button>
      </div>
    </section>
  )
}
