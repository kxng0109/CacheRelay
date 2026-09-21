import { useQuery } from '@tanstack/react-query'
import { useShallow } from 'zustand/react/shallow'
import { resolveManagementBase } from '../../shared/api/client.js'
import { useAuthStore } from '../../shared/auth/store.js'
import { histogramQuantile, parseGatewayPulse, parsePrometheusHistogram } from './prometheus.js'
import type { GatewayPulse } from './prometheus.js'

const POLL_MS = 15_000

/**
 * Formats seconds as a compact uptime phrase.
 *
 * @param seconds - Uptime in seconds.
 * @returns A short phrase (`3d 4h`, `4h 12m`, `12m`, `<1m`).
 */
function formatUptime(seconds: number): string {
  if (seconds < 60) return '<1m'
  const minutes = Math.floor(seconds / 60)
  const hours = Math.floor(minutes / 60)
  const days = Math.floor(hours / 24)
  if (days > 0) return `${String(days)}d ${String(hours % 24)}h`
  if (hours > 0) return `${String(hours)}h ${String(minutes % 60)}m`
  return `${String(minutes)}m`
}

/**
 * Formats a byte count for a compact cell.
 *
 * @param bytes - Byte count.
 * @returns MB below a gigabyte, GB above.
 */
function formatBytes(bytes: number): string {
  const gb = bytes / 1024 / 1024 / 1024
  if (gb >= 1) return `${gb.toFixed(1)} GB`
  return `${(bytes / 1024 / 1024).toFixed(0)} MB`
}

interface PulseValues extends GatewayPulse {
  /** Lifetime P99 latency in milliseconds from the cumulative histogram. */
  p99: number | null
}

/**
 * Gateway pulse strip: aggregate live gauges for operator sessions.
 *
 * @remarks
 * Admin-only by design: traffic volume, restart cadence, heap sizing,
 * live stream counts, and latency percentiles are operator intelligence,
 * not user-facing status data. Regular sessions never fetch the scrape
 * and never see the strip; their status page stays the liveness card.
 * Proof-type: live (`/actuator/prometheus`, no auth on the wire, but the
 * session gate decides who renders it). Deliberately aggregate: no
 * provider names, spend, keys, or per-tenant figures ever appear.
 *
 * @param props - Scrape interval override for tests.
 * @returns The pulse strip, or null for non-admin sessions.
 */
export function PulseStrip({ pollMs = POLL_MS }: { pollMs?: number }): React.JSX.Element | null {
  const isAdmin = useAuthStore(useShallow((s) => s.session?.admin === true))
  const query = useQuery({
    queryKey: ['prometheus-pulse'],
    enabled: isAdmin,
    queryFn: async ({ signal }): Promise<PulseValues> => {
      const res = await fetch(`${resolveManagementBase()}/actuator/prometheus`, { signal })
      if (!res.ok) {
        throw new Error(`Metrics scrape failed: HTTP ${String(res.status)}. Retry shortly.`)
      }
      const text = await res.text()
      const pulse = parseGatewayPulse(text)
      const snapshot = parsePrometheusHistogram(text)
      const quantile =
        snapshot === null || snapshot.count <= 0
          ? null
          : histogramQuantile(snapshot.buckets, snapshot.count, 0.99)
      return { ...pulse, p99: quantile === null ? null : quantile * 1000 }
    },
    refetchInterval: pollMs,
    retry: false,
  })

  // Regular sessions keep the status page only; operators see the pulse.
  if (!isAdmin) return null
  // The chart and probe cards own scrape-failure messaging; stay quiet.
  if (query.error instanceof Error) return null
  const pulse = query.data

  const uptimeSeconds = pulse?.uptimeSeconds ?? null
  const requestsTotal = pulse?.requestsTotal ?? null
  const errorsTotal = pulse?.errorsTotal ?? null
  const p99Ms = pulse?.p99 ?? null
  const liveStreams = pulse?.liveStreams ?? null
  const heapUsed = pulse?.heapUsedBytes ?? null
  const heapMax = pulse?.heapMaxBytes ?? null

  const uptime = uptimeSeconds === null ? '—' : formatUptime(uptimeSeconds)
  const requests = requestsTotal === null ? '—' : requestsTotal.toLocaleString('en-US')
  const errorRate =
    requestsTotal === null || requestsTotal <= 0 || errorsTotal === null
      ? '—'
      : `${((100 * errorsTotal) / requestsTotal).toFixed(2)}%`
  const errorsHot = (errorsTotal ?? 0) > 0
  const p99 = p99Ms === null ? '—' : `${p99Ms.toFixed(0)} ms`
  const streams = liveStreams === null ? '—' : String(liveStreams)
  const streamsLive = (liveStreams ?? 0) > 0
  const heap = heapUsed === null ? '—' : formatBytes(heapUsed)
  const heapSub = heapMax === null ? 'heap in use' : `of ${formatBytes(heapMax)}`

  return (
    <section aria-label="Gateway pulse" className="space-y-2">
      <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        <div className="min-h-19 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
          <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">Uptime</dt>
          <dd className="font-mono text-lg tnum">{uptime}</dd>
          <dd className="mt-1 text-xs text-ink-soft dark:text-parchment-soft">
            since process start
          </dd>
        </div>
        <div className="min-h-19 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
          <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">Requests</dt>
          <dd className="font-mono text-lg tnum">{requests}</dd>
          <dd className="mt-1 text-xs text-ink-soft dark:text-parchment-soft">all statuses</dd>
        </div>
        <div className="min-h-19 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
          <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">Errors</dt>
          <dd
            className={`font-mono text-lg tnum ${
              errorsHot ? 'text-danger dark:text-danger-soft' : ''
            }`}
          >
            {errorRate}
          </dd>
          <dd className="mt-1 text-xs text-ink-soft dark:text-parchment-soft">5xx rate</dd>
        </div>
        <div className="min-h-19 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
          <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">P99</dt>
          <dd className="font-mono text-lg tnum">{p99}</dd>
          <dd className="mt-1 text-xs text-ink-soft dark:text-parchment-soft">all time</dd>
        </div>
        <div className="min-h-19 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
          <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">Streams</dt>
          <dd className="font-mono text-lg tnum">
            <span
              aria-hidden="true"
              className={streamsLive ? 'text-success dark:text-success-soft' : ''}
            >
              {streamsLive ? '●' : '○'}{' '}
            </span>
            {streams}
          </dd>
          <dd className="mt-1 text-xs text-ink-soft dark:text-parchment-soft">live now</dd>
        </div>
        <div className="min-h-19 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
          <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">Heap</dt>
          <dd className="font-mono text-lg tnum">{heap}</dd>
          <dd className="mt-1 text-xs text-ink-soft dark:text-parchment-soft">{heapSub}</dd>
        </div>
      </dl>
    </section>
  )
}
