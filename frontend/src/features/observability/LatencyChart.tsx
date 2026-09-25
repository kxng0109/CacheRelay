import { useEffect, useRef, useState } from 'react'
import type { ECharts } from 'echarts/core'
import { echarts } from '../../shared/echarts/setup.js'
import type { EChartsOption } from '../../shared/echarts/setup.js'
import { useUiStore } from '../../shared/store.js'
import { MAX_POINTS, appendLatencyPoint, parsePrometheusHistogram } from './prometheus.js'
import { isProbeHttpFailure } from './probe.js'
import type { Accumulator, LatencyPoint } from './prometheus.js'
import { usePrometheusScrape } from './useScrape.js'

const POLL_MS = 15_000

interface LatencyChartProps {
  /**
   * Scrape interval override. Production uses the 15s poll; tests pin a
   * small value for fast determinism without fake timers.
   */
  pollMs?: number
  /**
   * Latest-point reporter. Called with the newest latency point (or null
   * before two scrapes exist) so parents can surface one live number —
   * e.g. the Overview RPS cell — without duplicating the scrape.
   */
  onLatest?: (
    point: { p50: number | null; p95: number | null; p99: number | null; rps: number } | null,
  ) => void
}

/**
 * Formats one latency point for the screen-reader summary.
 *
 * @param point - Latest chart point.
 * @returns Human sentence, or a waiting line before two scrapes exist.
 */
function describePoint(point: LatencyPoint | null): string {
  if (point === null) return 'Collecting latency samples. The chart needs two scrapes.'
  const ms = (v: number | null): string => (v === null ? 'unknown' : `${v.toFixed(0)} ms`)
  return `P50 ${ms(point.p50)}, P95 ${ms(point.p95)}, P99 ${ms(point.p99)}, ${point.rps.toFixed(1)} requests per second.`
}

/**
 * Live gateway latency chart (P50/P95 from Prometheus histogram deltas).
 *
 * @remarks
 * Proof-type: live (management-port `/actuator/prometheus`, no auth). Lazy-loaded
 * by the observability route so ECharts stays out of the initial bundle. The
 * instance is created once, updated via `setOption` on new points, and
 * disposed on unmount; a `ResizeObserver` drives resizes (never `window`
 * resize). Theme follows the app toggle through v6 `setTheme` — no re-init.
 *
 * @returns The latency chart region with a textual summary for assistive tech.
 */
export default function LatencyChart({
  pollMs = POLL_MS,
  onLatest,
}: LatencyChartProps): React.JSX.Element {
  const containerRef = useRef<HTMLDivElement | null>(null)
  const chartRef = useRef<ECharts | null>(null)
  const accRef = useRef<Accumulator | null>(null)
  const seenRef = useRef<string | null>(null)
  const dark = useUiStore((s) => s.dark)
  const [points, setPoints] = useState<LatencyPoint[]>([])
  const latestRef = useRef(onLatest)
  useEffect(() => {
    latestRef.current = onLatest
  })

  const metrics = usePrometheusScrape(
    pollMs,
    true,
    // Identity select keeps the raw text; parsing stays in the point
    // effect below (dedupe-aware via `seenRef`), exactly as before.
    (scrape) => scrape.text,
  )

  useEffect(() => {
    if (metrics.data === undefined || metrics.data === seenRef.current) return
    seenRef.current = metrics.data
    const snapshot = parsePrometheusHistogram(metrics.data)
    if (snapshot === null) return
    const atMs = Date.now()
    const { point, acc } = appendLatencyPoint(accRef.current, snapshot, atMs)
    accRef.current = acc
    if (point !== null) {
      setPoints((prev) => [...prev.slice(-(MAX_POINTS - 1)), point])
    }
  }, [metrics.data])

  useEffect(() => {
    const container = containerRef.current
    // The div mounts before effects run; the container is never null here.
    /* v8 ignore if -- @preserve */
    if (container === null) return
    const chart = echarts.init(container)
    chartRef.current = chart
    const observer = new ResizeObserver(() => {
      chart.resize()
    })
    observer.observe(container)
    return () => {
      observer.disconnect()
      chart.dispose()
      chartRef.current = null
    }
  }, [])

  useEffect(() => {
    const chart = chartRef.current
    // The mount effect assigns the instance synchronously, so updates always find it.
    /* v8 ignore if -- @preserve */
    if (chart === null) return
    chart.setTheme(dark ? 'dark' : 'default')
    const option: EChartsOption = {
      tooltip: { trigger: 'axis', valueFormatter: (v) => `${String(v)} ms` },
      // Legend rides top right: bottom placement collided with the time
      // axis tick labels in screenshots.
      legend: { data: ['P50', 'P95', 'P99'], top: 0, right: 0 },
      grid: { left: 48, right: 16, top: 40, bottom: 48 },
      // Stable container label: the chart repaints every poll, so templated
      // per-point announcements would spam screen readers. The throttled
      // `role="status"` summary below stays the live announcer.
      aria: {
        enabled: true,
        label: {
          description: 'Gateway request latency, P50, P95 and P99 in milliseconds over time.',
        },
      },
      xAxis: {
        type: 'time',
        name: 'time',
        axisLabel: { hideOverlap: true },
      },
      yAxis: { type: 'value', name: 'latency (ms)' },
      series: [
        {
          name: 'P50',
          type: 'line',
          showSymbol: false,
          data: points.map((p) => [p.at, p.p50]),
        },
        {
          name: 'P95',
          type: 'line',
          showSymbol: false,
          data: points.map((p) => [p.at, p.p95]),
        },
        {
          name: 'P99',
          type: 'line',
          showSymbol: false,
          data: points.map((p) => [p.at, p.p99]),
        },
      ],
    }
    chart.setOption(option, { notMerge: true })
  }, [points, dark])

  // Points are appended whole and arrays have no holes; the element is never undefined here.
  /* v8 ignore next -- @preserve */
  const latest: LatencyPoint | null = points.length > 0 ? (points[points.length - 1] ?? null) : null

  useEffect(() => {
    latestRef.current?.(latest)
  }, [points, latest])

  return (
    <section aria-label="Gateway latency">
      <h2 className="text-sm font-semibold tracking-tight">Request latency (P50/P95/P99)</h2>
      {metrics.error instanceof Error ? (
        <p
          role="alert"
          className={
            isProbeHttpFailure(metrics.error)
              ? 'text-sm text-danger dark:text-danger-soft'
              : 'text-sm text-ink-soft dark:text-parchment-soft'
          }
        >
          {metrics.error.message}
        </p>
      ) : null}
      <div
        ref={containerRef}
        role="img"
        aria-label={`Latency chart. ${describePoint(latest)}`}
        className={latest === null ? 'h-24 w-full' : 'h-64 w-full'}
      />
      <p role="status" className="text-[13px] tnum">
        {describePoint(latest)}
      </p>
    </section>
  )
}
