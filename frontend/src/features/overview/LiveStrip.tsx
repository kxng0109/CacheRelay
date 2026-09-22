import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { GatewayClient } from '../../shared/api/client.js'
import type { LedgerSummary } from '../../shared/api/types.js'
import { formatBytes, formatMicros } from '../../shared/utils/format.js'

const TAIL_SIZE = 5
const POLL_MS = 15_000

/**
 * Overview live strip: ticking eyebrow, latest-requests tail, cache config.
 *
 * @remarks Proof-type: live + recorded. The eyebrow re-renders whenever the
 * shared `ledger-summary` cache or the live RPS value moves, so operators
 * see the gateway tick without opening another screen. The tail reads the
 * first page of `/v1/admin/ledger/entries` on its own `ledger-tail` key and
 * the config panel shares the `cache-stats` key with the cache screen, both
 * polling on the operator cadence. Unknowns render em dashes, never zeros.
 * The stats endpoint reports configuration flags, never fill counters.
 *
 * @param props - Ledger summary, live RPS, both owned by the overview page.
 * @returns The live strip section.
 */
export function LiveStrip({
  summary,
  liveRps,
}: {
  summary: LedgerSummary
  liveRps: number | null
}): React.JSX.Element {
  const tail = useQuery({
    queryKey: ['ledger-tail'],
    queryFn: ({ signal }) => new GatewayClient().ledgerLogs(0, TAIL_SIZE, { signal }),
    refetchInterval: POLL_MS,
    retry: false,
  })
  const cache = useQuery({
    queryKey: ['cache-stats'],
    queryFn: ({ signal }) => new GatewayClient().cacheStats({ signal }),
    refetchInterval: POLL_MS,
    retry: false,
  })

  const entries = tail.data?.content ?? []
  const cacheFlags = cache.data

  return (
    <section aria-label="Live gateway activity" className="space-y-4">
      <p role="status" className="font-mono text-xs text-ink-soft tnum dark:text-parchment-soft">
        <span aria-hidden="true" className="mr-1 text-ember">
          ●
        </span>
        live · {summary.totalRequests} requests ·{' '}
        {liveRps === null ? 'waiting for live rate' : `${liveRps.toFixed(1)} rps`}
      </p>
      <div className="grid gap-3 lg:grid-cols-2">
        <div className="rounded-xl border border-ink/10 bg-cream p-4 dark:border-parchment/10 dark:bg-transparent">
          <div className="flex items-baseline justify-between gap-2">
            <h2 className="font-display text-xl font-medium tracking-tight">Latest requests</h2>
            <Link to="/ledger" className="font-mono text-xs underline">
              Explore ledger
            </Link>
          </div>
          {tail.isPending ? (
            <p role="status" className="mt-2 text-sm">
              Loading latest requests…
            </p>
          ) : tail.error instanceof Error ? (
            <div role="alert" className="mt-2 space-y-2">
              <p className="text-sm text-danger dark:text-danger-soft">
                Latest requests unavailable: {tail.error.message}
              </p>
              <button
                type="button"
                onClick={() => void tail.refetch()}
                className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
              >
                Retry
              </button>
            </div>
          ) : entries.length === 0 ? (
            <div className="mt-2 space-y-1">
              <p className="text-sm">No requests yet</p>
              <p className="text-[13px] text-ink-soft dark:text-parchment-soft">
                Send traffic through the gateway and the newest receipts land here.
              </p>
              <Link
                to="/playground"
                className="mt-2 inline-block rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
              >
                Open playground
              </Link>
            </div>
          ) : (
            <table className="mt-2 w-full text-left text-sm">
              <caption className="sr-only">Latest billed requests</caption>
              <thead>
                <tr className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
                  <th scope="col" className="py-1 pr-3 font-medium">
                    Request
                  </th>
                  <th scope="col" className="py-1 pr-3 font-medium">
                    Model
                  </th>
                  <th scope="col" className="py-1 text-right font-medium">
                    Cost (µ$)
                  </th>
                </tr>
              </thead>
              <tbody>
                {entries.map((e) => (
                  <tr key={e.requestId} className="border-t border-ink/10 dark:border-parchment/10">
                    <td
                      className="max-w-36 truncate py-1 pr-3 font-mono text-[13px]"
                      title={e.requestId}
                    >
                      {e.requestId}
                    </td>
                    <td className="max-w-32 truncate py-1 pr-3 text-[13px]" title={e.model}>
                      {e.model}
                    </td>
                    <td className="py-1 text-right font-mono text-[13px] tnum">
                      {e.costUsdMicros === 0 ? (
                        <span className="text-ink-soft dark:text-parchment-soft">free</span>
                      ) : (
                        formatMicros(e.costUsdMicros)
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
        <div className="rounded-xl border border-ink/10 bg-cream p-4 dark:border-parchment/10 dark:bg-transparent">
          <div className="flex items-baseline justify-between gap-2">
            <h2 className="font-display text-xl font-medium tracking-tight">Cache now</h2>
            <Link to="/cache" className="font-mono text-xs underline">
              Explore cache
            </Link>
          </div>
          {cache.isPending ? (
            <p role="status" className="mt-2 text-sm">
              Loading cache config…
            </p>
          ) : cache.error instanceof Error ? (
            <div role="alert" className="mt-2 space-y-2">
              <p className="text-sm text-danger dark:text-danger-soft">
                Cache config unavailable: {cache.error.message}
              </p>
              <button
                type="button"
                onClick={() => void cache.refetch()}
                className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
              >
                Retry
              </button>
            </div>
          ) : cacheFlags === undefined ? null : (
            <dl className="mt-2 space-y-3">
              <div className="flex items-baseline justify-between gap-2">
                <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">State</dt>
                <dd className="font-mono text-sm tnum">
                  {cacheFlags.enabled ? 'on' : 'off'} · {cacheFlags.defaultScope}
                </dd>
              </div>
              <div className="flex items-baseline justify-between gap-2">
                <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">L0 cap</dt>
                <dd className="font-mono text-sm tnum">
                  {formatBytes(cacheFlags.l0MaxBytes)} · TTL {cacheFlags.l0InMemoryTtlSeconds}s
                </dd>
              </div>
              <div className="flex items-baseline justify-between gap-2">
                <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">Tiers</dt>
                <dd className="font-mono text-sm tnum">
                  l1 {cacheFlags.l1RedisEnabled ? 'on' : 'off'} · l2{' '}
                  {cacheFlags.l2SemanticEnabled ? 'on' : 'off'} · guards{' '}
                  {cacheFlags.polarityGuardEnabled ? 'on' : 'off'}/
                  {cacheFlags.entityGuardEnabled ? 'on' : 'off'}
                </dd>
              </div>
            </dl>
          )}
        </div>
      </div>
    </section>
  )
}
