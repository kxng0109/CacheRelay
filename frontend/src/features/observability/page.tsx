import { Suspense, lazy, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useShallow } from 'zustand/react/shallow'
import { resolveApiBase, resolveManagementBase } from '../../shared/api/client.js'
import { isProbeHttpFailure } from './probe.js'
import { useAuthStore } from '../../shared/auth/store.js'
import { PulseStrip } from './PulseStrip.js'
import { SafeOutboundLink } from '../../shared/components/SafeOutboundLink.js'
import { SCRAPE_POLL_MS, usePrometheusScrape } from './useScrape.js'

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
    purpose: 'Latency histograms and counters, read by your monitoring scraper.',
    auth: 'No key needed',
  },
  {
    name: 'API reference',
    path: '/swagger-ui.html',
    purpose: 'Interactive endpoint explorer.',
    auth: 'Viewing is open; admin routes need an admin key',
  },
  {
    name: 'API docs',
    path: '/v3/api-docs',
    purpose: 'Machine readable OpenAPI document.',
    auth: 'No key needed',
  },
]

/**
 * Base URL for one inspected endpoint path.
 *
 * @remarks SEC-15 serves `/actuator/**` only on the management port; docs
 * paths stay on the app port.
 *
 * @param path - Endpoint path from the list.
 * @returns The absolute URL to open.
 */
function hrefFor(path: string): string {
  const base = path.startsWith('/actuator/') ? resolveManagementBase() : resolveApiBase()
  return `${base}${path}`
}
/**
 * Validates one shared scrape as a metrics probe.
 *
 * @param scrape - Shared scrape text plus content type.
 * @returns Up flag plus checked timestamp.
 * @throws When the endpoint answered the wrong content type.
 */
function probeSelect(scrape: { text: string; contentType: string }): { up: boolean; at: string } {
  if (!scrape.contentType.includes('text/plain')) {
    throw new Error(
      `Metrics endpoint answered ${scrape.contentType === '' ? 'without a content type' : scrape.contentType}, not Prometheus text. Check the management base URL.`,
    )
  }
  return { up: true, at: new Date().toISOString() }
}

/**
 * Observability page: liveness for every session, operator depth for admins.
 *
 * @remarks
 * Proof-type: live (real `/actuator/health` + `/actuator/prometheus`
 * probes, no auth required). Regular sessions get a status page: gateway
 * liveness plus reference docs. The metrics scrape card, pulse strip, and
 * latency chart are operator intelligence and render for admin sessions
 * only; the scrape query stays disabled for everyone else. Endpoints list
 * promotes docs to inspectable rows; selection is local UI state.
 *
 * @returns The observability screen.
 */
export function ObservabilityPage(): React.JSX.Element {
  const qc = useQueryClient()
  const isAdmin = useAuthStore(useShallow((s) => s.session?.admin === true))
  const [selected, setSelected] = useState<string | null>(null)

  const health = useQuery({
    queryKey: ['health'],
    queryFn: async ({ signal }): Promise<{ status: string }> => {
      const res = await fetch(`${resolveManagementBase()}/actuator/health`, { signal })
      if (!res.ok)
        throw new Error(`Health probe failed: HTTP ${String(res.status)}. Retry shortly.`)
      const contentType = res.headers.get('content-type') ?? ''
      const isJson = contentType.includes('application/json') || /\+json(\s|;|$)/.test(contentType)
      if (!isJson) {
        throw new Error(
          `Health endpoint answered ${contentType === '' ? 'without a content type' : contentType}, not JSON. Check the management base URL.`,
        )
      }
      return (await res.json()) as { status: string }
    },
    refetchInterval: 15_000,
  })

  const metrics = usePrometheusScrape(SCRAPE_POLL_MS, isAdmin, probeSelect)

  const probes = health.isPending ? 'probing' : health.data?.status === 'UP' ? 'up' : 'down'
  // The metrics endpoint row is operator tooling: regular sessions do not
  // need its path advertised, admins keep the full list.
  const visibleEndpoints = ENDPOINTS.filter((e) => e.path !== '/actuator/prometheus' || isAdmin)
  const inspected = visibleEndpoints.find((e) => e.path === selected) ?? null

  const retryAll = (): void => {
    void qc.invalidateQueries({ queryKey: ['health'] })
    void qc.invalidateQueries({ queryKey: ['prometheus-scrape'] })
  }

  /**
   * Retries one probe without disturbing the other card's state. The
   * metrics card shares the scrape query, so its retry re-scrapes once
   * for every consumer.
   *
   * @param key - The probe query key to invalidate.
   */
  const retryProbe = (key: string): void => {
    void qc.invalidateQueries({ queryKey: [key] })
  }

  return (
    <div className="space-y-4">
      <div className="space-y-1">
        <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
          <span aria-hidden="true" className="mr-1 text-ember">
            ❯
          </span>
          inspect
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <h1 className="font-display text-3xl font-medium tracking-tight">Observability</h1>
          <span className="rounded-full border border-ink/15 px-2 py-0.5 font-mono text-xs dark:border-parchment/15">
            probes:{probes}
          </span>
          <span className="flex-1" />
          <span className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
            probe:15s
          </span>
          <button
            type="button"
            onClick={retryAll}
            className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
          >
            Retry
          </button>
        </div>
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          Liveness, aggregate pulse, and latency evidence for the gateway.
        </p>
      </div>
      <PulseStrip />
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="space-y-2 rounded-xl border border-ink/10 bg-cream p-4 dark:border-parchment/10 dark:bg-transparent">
          <p className="flex items-center gap-2 text-sm font-medium">
            <span
              aria-hidden="true"
              className={
                probes === 'up'
                  ? 'text-success dark:text-success-soft'
                  : probes === 'down'
                    ? 'text-danger dark:text-danger-soft'
                    : ''
              }
            >
              {probes === 'up' ? '●' : probes === 'down' ? '■' : '○'}
            </span>
            Liveness {health.data?.status === 'UP' ? 'UP' : health.data ? 'DOWN' : ''}
          </p>
          <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
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
              <p
                className={
                  isProbeHttpFailure(health.error)
                    ? 'text-sm text-danger dark:text-danger-soft'
                    : 'text-sm text-ink-soft dark:text-parchment-soft'
                }
              >
                {health.error.message}
              </p>
              <p className="mt-1 font-mono text-xs text-ink-soft dark:text-parchment-soft">
                GET /actuator/health · auto-retries every 15s
              </p>
              <button
                type="button"
                onClick={() => {
                  retryProbe('health')
                }}
                className="mt-2 rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
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
        {isAdmin ? (
          <div className="space-y-2 rounded-xl border border-ink/10 bg-cream p-4 dark:border-parchment/10 dark:bg-transparent">
            <p className="flex items-center gap-2 text-sm font-medium">
              <span
                aria-hidden="true"
                className={
                  metrics.error instanceof Error
                    ? 'text-danger dark:text-danger-soft'
                    : metrics.data
                      ? 'text-success dark:text-success-soft'
                      : ''
                }
              >
                {metrics.error instanceof Error ? '■' : metrics.data ? '●' : '○'}
              </span>{' '}
              Metrics scrape
            </p>
            <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
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
                <p
                  className={
                    isProbeHttpFailure(metrics.error)
                      ? 'text-sm text-danger dark:text-danger-soft'
                      : 'text-sm text-ink-soft dark:text-parchment-soft'
                  }
                >
                  {metrics.error.message}
                </p>
                <p className="mt-1 font-mono text-xs text-ink-soft dark:text-parchment-soft">
                  GET /actuator/prometheus · auto-retries every 15s
                </p>
                <button
                  type="button"
                  onClick={() => {
                    retryProbe('prometheus-scrape')
                  }}
                  className="mt-2 rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
                >
                  Retry probe
                </button>
              </div>
            ) : metrics.data === undefined ? (
              <p role="status" className="text-sm">
                No metrics data. Retry the probe.
              </p>
            ) : (
              <p role="status" className="text-[13px] text-ink-soft tnum dark:text-parchment-soft">
                scrape ok · {new Date(metrics.data.at).toLocaleTimeString()}
              </p>
            )}
          </div>
        ) : null}
      </div>
      {isAdmin ? (
        <Suspense
          fallback={
            <p role="status" className="text-sm">
              Loading latency chart…
            </p>
          }
        >
          <LatencyChart />
        </Suspense>
      ) : null}
      <section
        aria-label="Signal rail"
        className="rounded-xl border border-ink/10 bg-cream p-4 dark:border-parchment/10 dark:bg-transparent"
      >
        <h2 className="font-mono text-[13px] text-ink-soft dark:text-parchment-soft">
          <span aria-hidden="true" className="mr-1 text-ember">
            ❯
          </span>
          signals
        </h2>
        <ul className="mt-2 space-y-1 text-sm">
          <li className="flex items-baseline justify-between gap-3">
            <span>
              <span aria-hidden="true" className="mr-1">
                {probes === 'up' ? '●' : probes === 'down' ? '■' : '○'}
              </span>
              Probes {probes}
            </span>
            <span className="font-mono text-xs text-ink-soft tnum dark:text-parchment-soft">
              /actuator/health
            </span>
          </li>
          {isAdmin ? (
            <li className="flex items-baseline justify-between gap-3">
              <span>
                <span aria-hidden="true" className="mr-1">
                  {metrics.error instanceof Error ? '■' : metrics.data ? '●' : '○'}
                </span>
                Metrics{' '}
                {metrics.error instanceof Error ? 'failing' : metrics.data ? 'live' : 'probing'}
              </span>
              <span className="font-mono text-xs text-ink-soft tnum dark:text-parchment-soft">
                /actuator/prometheus
              </span>
            </li>
          ) : null}
        </ul>
        <p className="mt-2 text-[13px] text-ink-soft dark:text-parchment-soft">
          One rail, two signals, same 15s cadence as the cards above. No alert feed exists on this
          stack, so the rail reads the probes directly.
        </p>
      </section>
      <div className="grid gap-6 lg:grid-cols-[1fr_340px]">
        <ul className="space-y-2 text-sm">
          {visibleEndpoints.map((e) => (
            <li key={e.path}>
              <button
                type="button"
                onClick={() => {
                  setSelected(e.path === selected ? null : e.path)
                }}
                aria-pressed={e.path === selected}
                className={`flex w-full items-center gap-3 rounded-lg border border-ink/10 bg-cream p-3 text-left dark:border-parchment/10 dark:bg-transparent ${
                  e.path === selected ? 'bg-ink/4 dark:bg-parchment/6' : ''
                }`}
              >
                <span className="font-medium">{e.name}</span>
                <span className="font-mono text-[13px] text-ink-soft dark:text-parchment-soft">
                  {e.path}
                </span>
              </button>
            </li>
          ))}
        </ul>
        <aside aria-label="Endpoint inspector" className="space-y-3">
          {inspected === null ? (
            <p className="text-[13px] text-ink-soft dark:text-parchment-soft">
              Select an endpoint to inspect purpose and auth.
            </p>
          ) : (
            <div className="space-y-3 rounded-xl border border-ink/10 bg-cream p-4 dark:border-parchment/10 dark:bg-transparent">
              <h2 className="font-mono text-sm break-all">{inspected.path}</h2>
              <dl className="space-y-2 text-[13px]">
                <div className="flex justify-between gap-3">
                  <dt className="text-ink-soft dark:text-parchment-soft">Purpose</dt>
                  <dd className="text-right">{inspected.purpose}</dd>
                </div>
                <div className="flex justify-between gap-3">
                  <dt className="text-ink-soft dark:text-parchment-soft">Auth</dt>
                  <dd className="text-right">{inspected.auth}</dd>
                </div>
              </dl>
              <SafeOutboundLink
                href={hrefFor(inspected.path)}
                className="block rounded-md border border-ink/15 px-3 py-2 text-center text-[13px] dark:border-parchment/15"
              >
                Open in new tab
              </SafeOutboundLink>
            </div>
          )}
        </aside>
      </div>
    </div>
  )
}
