import { useQuery } from '@tanstack/react-query'
import { resolveApiBase } from '../../shared/api/client.js'

/**
 * Observability page: gateway liveness plus documentation entry points.
 *
 * @remarks
 * Proof-type: live (real `/actuator/health` probe, no auth required).
 *
 * @returns The observability screen.
 */
export function ObservabilityPage(): React.JSX.Element {
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

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold tracking-tight">Observability</h1>
      {health.isPending ? (
        <p role="status" className="text-sm">
          Probing gateway health…
        </p>
      ) : health.error instanceof Error ? (
        <p role="alert" className="text-sm text-danger dark:text-danger-soft">
          {health.error.message}
        </p>
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
      <ul className="space-y-2 text-sm">
        <li>
          Metrics (Prometheus): same-origin `/actuator/prometheus` — scrape, do not render secrets
          here.
        </li>
        <li>API reference: same-origin `/swagger-ui.html` and `/v3/api-docs`.</li>
      </ul>
    </div>
  )
}
