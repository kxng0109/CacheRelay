import { Suspense, lazy, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Activity, BookOpen, FlaskConical, Zap } from 'lucide-react'
import { Link } from 'react-router'
import { useShallow } from 'zustand/react/shallow'
import { GatewayClient } from '../../shared/api/client.js'
import { useAuthStore } from '../../shared/auth/store.js'
import { PulseStrip } from '../observability/PulseStrip.js'
import { LiveStrip } from './LiveStrip.js'
import { StatStrip } from './StatStrip.js'

const LatencyChart = lazy(() => import('../observability/LatencyChart.js'))

const LINKS = [
  {
    to: '/playground',
    title: 'Playground',
    body: 'Stream a completion through the gateway.',
    icon: FlaskConical,
  },
  {
    to: '/circuits',
    title: 'Circuits',
    body: 'Watch provider breakers and force resets.',
    icon: Zap,
  },
  {
    to: '/ledger',
    title: 'Ledger',
    body: 'Audit every billed request.',
    icon: BookOpen,
  },
  {
    to: '/observability',
    title: 'Observability',
    body: 'Health probes and latency evidence.',
    icon: Activity,
  },
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
      <div className="space-y-1">
        <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
          <span aria-hidden="true" className="mr-1 text-ember">
            ❯
          </span>
          home
        </p>
        <h1 className="font-display text-3xl font-medium tracking-tight">Overview</h1>
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          Gateway health, spend, and latency at a glance.
        </p>
      </div>
      <PulseStrip />
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
          <>
            <LiveStrip summary={summary.data} liveRps={liveRps} />
            <StatStrip
              summary={summary.data}
              liveRps={liveRps}
              onRetry={() => void summary.refetch()}
            />
          </>
        )
      ) : null}
      {isAdmin ? (
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
      ) : null}
      <nav aria-label="Console sections" className="grid gap-3 sm:grid-cols-2">
        {LINKS.map((link) => (
          <Link
            key={link.to}
            to={link.to}
            className="lift group rounded-xl border border-ink/10 bg-cream p-4 dark:border-parchment/10 dark:bg-transparent"
          >
            <span className="flex items-center gap-2 text-ink-soft dark:text-parchment-soft">
              <link.icon size={16} aria-hidden="true" />
              <span className="font-display text-xl font-medium tracking-tight text-ink dark:text-parchment">
                {link.title}
              </span>
            </span>
            <p className="mt-1 text-[13px] text-ink-soft dark:text-parchment-soft">{link.body}</p>
          </Link>
        ))}
      </nav>
    </div>
  )
}
