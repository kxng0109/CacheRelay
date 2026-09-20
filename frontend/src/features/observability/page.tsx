import { Suspense, lazy, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { resolveApiBase } from '../../shared/api/client.js'

const LatencyChart = lazy(() => import('./LatencyChart.js'))

interface EndpointInfo {
  name: string
  path: string
  purpose: string
  auth: string
}

const ENDPOINTS: EndpointInfo[] = [
  {
    name: 'Metrics (Prometheus)',
    path: '/actuator/prometheus',
    purpose: 'Latency histograms and counters. Scrape, do not render secrets here.',
    auth: 'none required',
  },
  {
    name: 'API reference',
    path: '/swagger-ui.html',
    purpose: 'Interactive endpoint explorer.',
    auth: 'none required to view; admin key to call admin routes',
  },
  {
    name: 'API docs',
    path: '/v3/api-docs',
    purpose: 'Machine-readable OpenAPI document.',
    auth: 'none required',
  },
]

/**
 * Probes one actuator endpoint without throwing on empty bodies.
 *
 * @param base - Resolved API base.
 * @param path - Actuator path.
 * @param signal - Abort signal.
 * @returns Up flag plus checked timestamp.
 */
async function probeEndpoint(
  base: string,
  path: string,
  signal: AbortSignal,
): Promise<{ up: boolean; at: string }> {
  const res = await fetch(`${base}${path}`, { signal })
  if (!res.ok) throw new Error(`Health probe failed: HTTP ${String(res.status)}. Retry shortly.`)
  await res.text().catch(() => '')
  return { up: true, at: new Date().toISOString() }
}

/**
 * Observability page: liveness plus metrics scrape plus endpoint drill-down.
 *
 * @remarks
 * Proof-type: live (real `/actuator/health` + `/actuator/prometheus`
 * probes, no auth required). Readiness is intentionally absent: the
 * actuator only exposes health and prometheus, so only those two cards
 * ship. Endpoints list promotes docs to inspectable rows; selection is
 * local UI state.
 *
 * @returns The observability screen.
 */
export function ObservabilityPage(): React.JSX.Element {
  const qc = useQueryClient()
  const [selected, setSelected] = useState<string | null>(null)

  const health = useQuery({
    queryKey: ['health'],
    queryFn: async ({ signal }): Promise<{ status: string }> => {
      const res = await fetch(`${resolveApiBase()}/actuator/health`, { signal })
      if (!res.ok)
        throw new Error(`Health probe failed: HTTP ${String(res.status)}. Retry shortly.`)
      return (await res.json()) as { status: string }
    },
    refetchInterval: 15_000,
  })

  const metrics = useQuery({
    queryKey: ['metrics-probe'],
    queryFn: ({ signal }) => probeEndpoint(resolveApiBase(), '/actuator/prometheus', signal),
    refetchInterval: 15_000,
  })

  const gateway = health.isPending ? 'probing' : health.data?.status === 'UP' ? 'up' : 'down'
  const inspected = ENDPOINTS.find((e) => e.path === selected) ?? null

  const retryAll = (): void => {
    void qc.invalidateQueries({ queryKey: ['health'] })
    void qc.invalidateQueries({ queryKey: ['metrics-probe'] })
  }

  /**
   * Retries one probe without disturbing the other card's state.
   *
   * @param key - The probe query key to invalidate.
   */
  const retryProbe = (key: string): void => {
    void qc.invalidateQueries({ queryKey: [key] })
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <h1 className="font-display text-2xl font-medium tracking-tight">Observability</h1>
        <span className="rounded-full border border-ink/15 px-2 py-0.5 font-mono text-[11px] dark:border-parchment/15">
          gateway:{gateway}
        </span>
        <span className="flex-1" />
        <span className="font-mono text-[11px] text-ink-soft dark:text-parchment-soft">
          probe:15s
        </span>
        <button
          type="button"
          onClick={retryAll}
          className="rounded-md border border-ink/15 px-3 py-2 text-xs dark:border-parchment/15"
        >
          Retry [r]
        </button>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="space-y-2 rounded-xl border border-ink/10 bg-cream p-4 dark:border-parchment/10 dark:bg-transparent">
          <p className="flex items-center gap-2 text-sm font-medium">
            <span aria-hidden="true">
              {gateway === 'up' ? '●' : gateway === 'down' ? '■' : '○'}
            </span>
            Liveness {health.data?.status === 'UP' ? 'UP' : health.data ? 'DOWN' : ''}
          </p>
          <p className="font-mono text-[11px] text-ink-soft dark:text-parchment-soft">
            /actuator/health
          </p>
          {health.isPending ? (
            <p role="status" className="text-sm">
              Probing gateway health…
            </p>
          ) : health.error instanceof Error ? (
            <div
              role="alert"
              className="rounded-md border border-ink/10 p-3 dark:border-parchment/10"
            >
              <p className="text-sm text-danger dark:text-danger-soft">{health.error.message}</p>
              <p className="mt-1 font-mono text-[11px] text-ink-soft dark:text-parchment-soft">
                GET /actuator/health · auto-retries every 15s
              </p>
              <button
                type="button"
                onClick={() => {
                  retryProbe('health')
                }}
                className="mt-2 rounded-md border border-ink/15 px-3 py-2 text-xs dark:border-parchment/15"
              >
                Retry probe
              </button>
            </div>
          ) : health.data === undefined ? (
            <p role="status" className="text-sm">
              No health data. Retry the probe.
            </p>
          ) : (
            <p role="status" className="text-sm">
              {health.data.status === 'UP'
                ? '● Gateway is up'
                : `■ Gateway reports ${health.data.status}`}
            </p>
          )}
        </div>
        <div className="space-y-2 rounded-xl border border-ink/10 bg-cream p-4 dark:border-parchment/10 dark:bg-transparent">
          <p className="flex items-center gap-2 text-sm font-medium">
            <span aria-hidden="true">{metrics.data ? '●' : '○'}</span> Metrics scrape
          </p>
          <p className="font-mono text-[11px] text-ink-soft dark:text-parchment-soft">
            /actuator/prometheus
          </p>
          {metrics.isPending ? (
            <p role="status" className="text-sm">
              Probing metrics…
            </p>
          ) : metrics.error instanceof Error ? (
            <div
              role="alert"
              className="rounded-md border border-ink/10 p-3 dark:border-parchment/10"
            >
              <p className="text-sm text-danger dark:text-danger-soft">{metrics.error.message}</p>
              <p className="mt-1 font-mono text-[11px] text-ink-soft dark:text-parchment-soft">
                GET /actuator/prometheus · auto-retries every 15s
              </p>
              <button
                type="button"
                onClick={() => {
                  retryProbe('metrics-probe')
                }}
                className="mt-2 rounded-md border border-ink/15 px-3 py-2 text-xs dark:border-parchment/15"
              >
                Retry probe
              </button>
            </div>
          ) : metrics.data === undefined ? (
            <p role="status" className="text-sm">
              No metrics data. Retry the probe.
            </p>
          ) : (
            <p role="status" className="text-xs text-ink-soft tnum dark:text-parchment-soft">
              scrape ok · {metrics.data.at}
            </p>
          )}
        </div>
      </div>
      <Suspense
        fallback={
          <p role="status" className="text-sm">
            Loading latency chart…
          </p>
        }
      >
        <LatencyChart />
      </Suspense>
      <details className="rounded-xl border border-ink/10 bg-cream p-4 dark:border-parchment/10 dark:bg-transparent">
        <summary className="cursor-pointer font-mono text-xs">
          Reading cache headers on a stream
        </summary>
        <dl className="mt-2 space-y-2 text-xs">
          <div className="flex justify-between gap-3">
            <dt className="font-mono">X-Cache</dt>
            <dd className="text-right text-ink-soft dark:text-parchment-soft">
              HIT (L0-Memory), HIT (L1-Exact), or HIT (L2-Semantic). Absent means a provider-backed
              live response.
            </dd>
          </div>
          <div className="flex justify-between gap-3">
            <dt className="font-mono">X-CacheRelay-Similarity-Score</dt>
            <dd className="text-right text-ink-soft dark:text-parchment-soft">
              Semantic similarity, 4 decimals; 1.0 on exact tiers.
            </dd>
          </div>
          <div className="flex justify-between gap-3">
            <dt className="font-mono">Age</dt>
            <dd className="text-right text-ink-soft dark:text-parchment-soft">
              Seconds since the cached entry was stored.
            </dd>
          </div>
        </dl>
        <p className="mt-2 text-xs text-ink-soft dark:text-parchment-soft">
          The Playground stream header prints all three live on every run.
        </p>
      </details>
      <div className="grid gap-6 lg:grid-cols-[1fr_280px]">
        <ul className="space-y-2 text-sm">
          {ENDPOINTS.map((e) => (
            <li key={e.path}>
              <button
                type="button"
                onClick={() => {
                  setSelected(e.path === selected ? null : e.path)
                }}
                aria-pressed={e.path === selected}
                className={`flex w-full items-center gap-3 rounded-lg border border-ink/10 p-3 text-left dark:border-parchment/10 ${
                  e.path === selected ? 'bg-ink/4 dark:bg-parchment/6' : ''
                }`}
              >
                <span className="font-medium">{e.name}</span>
                <span className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
                  {e.path}
                </span>
              </button>
            </li>
          ))}
        </ul>
        <aside aria-label="Endpoint inspector" className="space-y-3">
          {inspected === null ? (
            <p className="text-xs text-ink-soft dark:text-parchment-soft">
              Select an endpoint to inspect purpose and auth.
            </p>
          ) : (
            <div className="space-y-3 rounded-xl border border-ink/10 bg-cream p-4 dark:border-parchment/10 dark:bg-transparent">
              <h2 className="font-mono text-sm break-all">{inspected.path}</h2>
              <dl className="space-y-2 text-xs">
                <div className="flex justify-between gap-3">
                  <dt className="text-ink-soft dark:text-parchment-soft">Purpose</dt>
                  <dd className="text-right">{inspected.purpose}</dd>
                </div>
                <div className="flex justify-between gap-3">
                  <dt className="text-ink-soft dark:text-parchment-soft">Auth</dt>
                  <dd className="text-right">{inspected.auth}</dd>
                </div>
              </dl>
              <a
                href={`${resolveApiBase()}${inspected.path}`}
                target="_blank"
                rel="noreferrer"
                className="block rounded-md border border-ink/15 px-3 py-2 text-center text-xs dark:border-parchment/15"
              >
                Open in new tab
              </a>
            </div>
          )}
        </aside>
      </div>
    </div>
  )
}
