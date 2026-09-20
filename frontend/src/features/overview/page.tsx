import { Suspense, lazy, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { useShallow } from 'zustand/react/shallow'
import { GatewayClient } from '../../shared/api/client.js'
import { useAuthStore } from '../../shared/auth/store.js'

const LatencyChart = lazy(() => import('../observability/LatencyChart.js'))

const LINKS = [
  { to: '/playground', title: 'Playground', body: 'Stream a completion through the gateway.' },
  { to: '/circuits', title: 'Circuits', body: 'Watch provider breakers and force resets.' },
  { to: '/ledger', title: 'Ledger', body: 'Audit every billed request.' },
  { to: '/observability', title: 'Observability', body: 'Health probes and latency evidence.' },
] as const

/**
 * Overview home: billed totals, live latency, and entry points.
 *
 * @remarks Proof-type: recorded + live. Tiles read the real ledger
 * summary when an admin key is present (shared `ledger-summary` query
 * cache with the ledger screen); otherwise they stay locked. The latency
 * chart is the same lazy component as the observability screen, so no
 * second ECharts copy ever loads.
 *
 * @returns The overview screen.
 */
export function OverviewPage(): React.JSX.Element {
  const session = useAuthStore(useShallow((s) => s.session))
  const isAdmin = session?.admin === true

  const summary = useQuery({
    queryKey: ['ledger-summary'],
    queryFn: ({ signal }) => new GatewayClient().ledgerSummary({ signal }),
    enabled: isAdmin,
  })

  /**
   * Live requests-per-second from the latency chart's newest point. Null
   * until two scrapes exist — the cell shows an em dash, never a zero that
   * would read as a dead gateway.
   */
  const [liveRps, setLiveRps] = useState<number | null>(null)

  return (
    <div className="space-y-6">
      <div>
        <h1 className="font-display text-3xl font-medium tracking-tight">Overview</h1>
        <p className="mt-1 text-sm text-ink-soft dark:text-parchment-soft">
          Gateway health, spend, and latency at a glance.
        </p>
      </div>
      {isAdmin ? (
        summary.isPending ? (
          <p role="status" className="text-sm">
            Loading totals…
          </p>
        ) : summary.error instanceof Error ? (
          <div
            role="alert"
            className="rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent"
          >
            <p className="text-sm text-danger dark:text-danger-soft">
              Totals unavailable: {summary.error.message}
            </p>
            <button
              type="button"
              onClick={() => void summary.refetch()}
              className="mt-2 text-sm underline"
            >
              Retry [r]
            </button>
          </div>
        ) : summary.data === undefined ? null : (
          <dl className="grid gap-3 sm:grid-cols-4">
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
            <div className="min-h-19 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
              <dt className="text-xs text-ink-soft dark:text-parchment-soft">Live RPS</dt>
              <dd className="font-mono text-lg tnum">
                {liveRps === null ? '—' : liveRps.toFixed(1)}
              </dd>
            </div>
          </dl>
        )
      ) : null}
      <Suspense
        fallback={
          <p role="status" className="text-sm">
            Loading latency chart…
          </p>
        }
      >
        <LatencyChart
          onLatest={(point) => {
            setLiveRps(point === null ? null : point.rps)
          }}
        />
      </Suspense>
      <nav aria-label="Console sections" className="grid gap-3 sm:grid-cols-2">
        {LINKS.map((link) => (
          <Link
            key={link.to}
            to={link.to}
            className="lift rounded-xl border border-ink/10 bg-cream p-4 dark:border-parchment/10 dark:bg-transparent"
          >
            <p className="font-display text-xl font-medium tracking-tight">{link.title}</p>
            <p className="mt-1 text-xs text-ink-soft dark:text-parchment-soft">{link.body}</p>
          </Link>
        ))}
      </nav>
    </div>
  )
}
