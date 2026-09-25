import { useQuery } from '@tanstack/react-query'
import type { UseQueryResult } from '@tanstack/react-query'
import { resolveManagementBase } from '../../shared/api/client.js'

/** Raw Prometheus scrape plus the serving content type. */
export interface PrometheusScrape {
  /** Full exposition text (parsed per consumer via `select`). */
  text: string
  /** Response content type for probe validation. */
  contentType: string
}

/** Shared 15 s scrape cadence for every Prometheus consumer. */
export const SCRAPE_POLL_MS = 15_000

/** Download ceiling for one scrape: scrapes are kilobytes; past this the
 * peer is hostile or broken, and partial parses would mislead. */
const MAX_SCRAPE_BYTES = 8 * 1024 * 1024

/**
 * Budget breach: the scrape stayed honest by refusing instead of
 * truncating. Distinguished from transport failures, which read as empty.
 */
class ScrapeBudgetError extends Error {
  constructor() {
    super('Metrics scrape exceeded the 8 MB budget. Retry shortly.')
    this.name = 'ScrapeBudgetError'
  }
}

/**
 * Reads a scrape body with a byte budget.
 *
 * @param res - Accepted scrape response.
 * @returns Full text within budget, or empty when the body dies mid-stream
 * (honest-empty, matching the old per-consumer readers).
 * @throws {@link ScrapeBudgetError} when the body passes the budget.
 */
async function readCappedBody(res: Response): Promise<string> {
  if (res.body === null) return res.text().catch(() => '')
  const reader = res.body.getReader()
  const chunks: Uint8Array[] = []
  let bytes = 0
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      bytes += value.length
      if (bytes > MAX_SCRAPE_BYTES) {
        await reader.cancel().catch(() => undefined)
        throw new ScrapeBudgetError()
      }
      chunks.push(value)
    }
  } catch (e) {
    if (e instanceof ScrapeBudgetError) throw e
    return ''
  } finally {
    try {
      reader.releaseLock()
    } catch {
      // Already closed or cancelled; nothing to release.
    }
  }
  const merged = new Uint8Array(bytes)
  let at = 0
  for (const chunk of chunks) {
    merged.set(chunk, at)
    at += chunk.length
  }
  return new TextDecoder().decode(merged)
}

/**
 * Fetches the raw scrape: the single network call behind the hook.
 *
 * @param signal - Abort signal.
 * @returns Scrape text plus content type.
 */
async function fetchScrape(signal: AbortSignal): Promise<PrometheusScrape> {
  const res = await fetch(`${resolveManagementBase()}/actuator/prometheus`, { signal })
  if (!res.ok) {
    throw new Error(`Metrics scrape failed: HTTP ${String(res.status)}. Retry shortly.`)
  }
  return {
    text: await readCappedBody(res),
    contentType: res.headers.get('content-type') ?? '',
  }
}

/**
 * One shared Prometheus scrape for every consumer on the screen.
 *
 * @remarks FE-18: PulseStrip, LatencyChart, and the metrics probe each
 * fetched and parsed the full scrape on their own query keys (three
 * requests per 15 s on Observability, two on Overview). Every consumer
 * now observes this single query and derives its view via `select`, so
 * one interval means one request. Consumers validate and parse locally;
 * a throwing `select` fails only that observer, never the shared query.
 *
 * @param pollMs - Refetch cadence (production 15 s; tests pin smaller).
 * @param enabled - False suspends the scrape (non-admin sessions).
 * @param select - Local derivation from the raw scrape.
 * @returns The shared scrape query with the selected view.
 */
export function usePrometheusScrape<T>(
  pollMs: number = SCRAPE_POLL_MS,
  enabled = true,
  select?: (scrape: PrometheusScrape) => T,
): UseQueryResult<T> {
  return useQuery({
    queryKey: ['prometheus-scrape'],
    enabled,
    queryFn: ({ signal }): Promise<PrometheusScrape> => fetchScrape(signal),
    refetchInterval: pollMs,
    retry: false,
    ...(select === undefined ? {} : { select }),
  })
}
