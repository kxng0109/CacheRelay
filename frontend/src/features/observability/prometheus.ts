/**
 * Minimal Prometheus exposition parser for gateway latency histograms.
 *
 * @remarks
 * Reads only `http_server_requests_seconds_bucket` / `_count` lines for
 * `uri=~"/v1/.*"` series from same-origin `/actuator/prometheus` (no auth,
 * `permitAll`). Quantiles come from per-interval bucket deltas — never from
 * cumulative counters, which would flatten into a meaningless line. Bounds
 * (line count, line length, bucket count) keep a hostile or bloated scrape
 * from stalling the UI thread.
 */

const METRIC_BUCKET = 'http_server_requests_seconds_bucket'
const METRIC_COUNT = 'http_server_requests_seconds_count'
const MAX_LINES = 50_000
const MAX_LINE_LENGTH = 4096
const MAX_BUCKETS = 100
/** Points kept per chart: 48 polls at 15s cover the last 12 minutes. */
export const MAX_POINTS = 48

/**
 * Scans exposition lines without allocating a line array.
 *
 * @remarks A 100 MB body split on `\n` would materialize millions of
 * substrings before the `MAX_LINES` cap ever applied. The generator
 * yields at most `MAX_LINES` slices, so unbounded bodies cost iterations,
 * never a giant array — and the transport already caps bytes (FE-35).
 *
 * @param text - Raw scrape text.
 * @returns Up to `MAX_LINES` line slices.
 */
export function* scanLines(text: string): Generator<string> {
  let start = 0
  let count = 0
  for (let i = 0; i < text.length && count < MAX_LINES; i += 1) {
    if (text[i] === '\n') {
      count += 1
      yield text.slice(start, i)
      start = i + 1
    }
  }
  if (start < text.length && count < MAX_LINES) yield text.slice(start)
}

export interface HistogramBucket {
  /** Bucket upper bound in seconds (`Infinity` for `+Inf`). */
  le: number
  /** Cumulative count at this bound. */
  count: number
}

export interface HistogramSnapshot {
  buckets: HistogramBucket[]
  count: number
}

export interface LatencyPoint {
  /** Sample time (epoch milliseconds). */
  at: number
  /** P50 latency in milliseconds, or null when unbounded. */
  p50: number | null
  /** P95 latency in milliseconds, or null when unbounded. */
  p95: number | null
  /** P99 latency in milliseconds, or null when unbounded. */
  p99: number | null
  /** Requests per second over the interval. */
  rps: number
}

export interface Accumulator {
  buckets: HistogramBucket[]
  count: number
  atMs: number
}

/**
 * Parses one `name{labels} value` exposition line for the latency histogram.
 *
 * @param line - Raw exposition line (comments and EOF already skipped).
 * @param into - Mutable aggregate receiving bucket and count samples.
 */
function parseLine(line: string, into: { buckets: Map<number, number>; count: number }): void {
  const metric =
    line.startsWith(METRIC_BUCKET) && line.charAt(METRIC_BUCKET.length) !== '_'
      ? METRIC_BUCKET
      : line.startsWith(METRIC_COUNT)
        ? METRIC_COUNT
        : null
  if (metric === null) return
  const braceOpen = line.indexOf('{')
  const braceClose = line.lastIndexOf('}')
  const space = line.indexOf(' ', braceClose)
  if (braceOpen < 0 || braceClose < 0 || space < 0) return
  const labels = line.slice(braceOpen + 1, braceClose)
  if (!/(?:^|,)uri="\/v1\//.test(labels)) return
  const value = Number(
    line
      .slice(space + 1)
      .trim()
      .split(' ')[0],
  )
  if (!Number.isFinite(value) || value < 0) return
  if (metric === METRIC_COUNT) {
    into.count += value
    return
  }
  const leMatch = /(?:^|,)le="([^"]*)"/.exec(labels)
  if (leMatch === null) return
  const le = leMatch[1] === '+Inf' ? Infinity : Number(leMatch[1])
  if (!Number.isFinite(le) && le !== Infinity) return
  if (le < 0) return
  into.buckets.set(le, (into.buckets.get(le) ?? 0) + value)
}

/**
 * Aggregates the gateway latency histogram from exposition text.
 *
 * @param text - Raw `/actuator/prometheus` body.
 * @returns Snapshot with ascending finite buckets plus `+Inf`, or null when
 * no `/v1/` bucket series arrived.
 */
export function parsePrometheusHistogram(text: string): HistogramSnapshot | null {
  const aggregate = { buckets: new Map<number, number>(), count: 0 }
  for (const line of scanLines(text)) {
    if (line.length === 0 || line.startsWith('#') || line.length > MAX_LINE_LENGTH) continue
    parseLine(line, aggregate)
    if (aggregate.buckets.size > MAX_BUCKETS) break
  }
  if (aggregate.buckets.size === 0) return null
  const sorted = [...aggregate.buckets.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([le, bucketCount]) => ({ le, count: bucketCount }))
  return { buckets: sorted, count: aggregate.count }
}

/**
 * Estimates a quantile from one interval's bucket deltas.
 *
 * @param buckets - Ascending per-interval bucket counts (deltas, not cumulative).
 * @param total - Total samples in the interval.
 * @param q - Quantile in `(0, 1]`.
 * @returns The smallest finite bound covering the quantile, or null when the
 * quantile is unbounded (only reachable at `+Inf`) or the interval is empty.
 */
export function histogramQuantile(
  buckets: HistogramBucket[],
  total: number,
  q: number,
): number | null {
  if (!(q > 0 && q <= 1) || total <= 0) return null
  const threshold = q * total
  let cumulative = 0
  for (const bucket of buckets) {
    cumulative += bucket.count
    if (cumulative >= threshold) {
      return Number.isFinite(bucket.le) ? bucket.le : null
    }
  }
  return null
}

/**
 * Folds one scrape into the latency series.
 *
 * @remarks
 * The first scrape only seeds the accumulator (a single cumulative sample
 * cannot form a rate). Counter resets clamp to zero so deploys never render
 * negative latency. Points are plain data — rendering stays in the component.
 *
 * @param prev - Previous accumulator, or null before the first scrape.
 * @param snapshot - Freshly parsed histogram.
 * @param atMs - Scrape time (epoch milliseconds).
 * @returns The appended point (null until two scrapes exist) and the next
 * accumulator. The accumulator is immutable; callers hold no shared state.
 */
export function appendLatencyPoint(
  prev: Accumulator | null,
  snapshot: HistogramSnapshot,
  atMs: number,
): { point: LatencyPoint | null; acc: Accumulator } {
  const acc: Accumulator = { buckets: snapshot.buckets, count: snapshot.count, atMs }
  if (prev === null) return { point: null, acc }
  const elapsedSec = (atMs - prev.atMs) / 1000
  if (elapsedSec <= 0) return { point: null, acc }
  const prevByLe = new Map(prev.buckets.map((b) => [b.le, b.count]))
  const deltas = snapshot.buckets.map((b) => ({
    le: b.le,
    count: Math.max(0, b.count - (prevByLe.get(b.le) ?? 0)),
  }))
  const deltaCount = Math.max(0, snapshot.count - prev.count)
  if (deltaCount <= 0) return { point: null, acc }
  const toMs = (v: number | null): number | null => (v === null ? null : v * 1000)
  return {
    point: {
      at: atMs,
      p50: toMs(histogramQuantile(deltas, deltaCount, 0.5)),
      p95: toMs(histogramQuantile(deltas, deltaCount, 0.95)),
      p99: toMs(histogramQuantile(deltas, deltaCount, 0.99)),
      rps: deltaCount / elapsedSec,
    },
    acc,
  }
}

/**
 * Aggregate gauge snapshot for the gateway pulse strip.
 *
 * @remarks Every field is nullable: a missing series renders an em dash,
 * never a fabricated zero. Chosen sources are aggregate and
 * non-sensitive: process uptime, HTTP request and 5xx counters, heap
 * usage (process health, not business data), and active SSE streams.
 * Provider names, spend, keys, and per-tenant data never appear here.
 */
export interface GatewayPulse {
  /** Process uptime in seconds (`process_uptime_seconds`). */
  uptimeSeconds: number | null
  /** Total HTTP requests served since start (all statuses). */
  requestsTotal: number | null
  /** HTTP 5xx responses since start. */
  errorsTotal: number | null
  /** JVM heap used in bytes (`area="heap"`). */
  heapUsedBytes: number | null
  /** JVM heap max in bytes, or null when the runtime reports none. */
  heapMaxBytes: number | null
  /** Currently open SSE connections (`sse_connection_active`). */
  liveStreams: number | null
}

/**
 * Reads the trailing numeric sample from one exposition line.
 *
 * @param line - Raw exposition line.
 * @returns The sample value, or null for malformed or negative samples.
 */
function sampleValue(line: string): number | null {
  const braceClose = line.lastIndexOf('}')
  const space = braceClose >= 0 ? line.indexOf(' ', braceClose) : line.indexOf(' ')
  if (space < 0) return null
  const value = Number(
    line
      .slice(space + 1)
      .trim()
      .split(' ')[0],
  )
  return Number.isFinite(value) && value >= 0 ? value : null
}

/**
 * Extracts the metric name from an exposition line.
 *
 * @param line - Raw exposition line.
 * @returns The name before `{` or the first space.
 */
function metricName(line: string): string {
  const brace = line.indexOf('{')
  const space = line.indexOf(' ')
  if (brace < 0) return space < 0 ? line : line.slice(0, space)
  return space >= 0 && space < brace ? line.slice(0, space) : line.slice(0, brace)
}

/**
 * Parses the gateway pulse snapshot from exposition text.
 *
 * @remarks
 * Bounded like the histogram parser so a hostile or bloated scrape cannot
 * stall the UI thread. `errorsTotal` normalizes to `0` when request
 * counters exist but no 5xx series does, matching Prometheus semantics.
 *
 * @param text - Raw `/actuator/prometheus` body.
 * @returns The pulse snapshot; unknown fields stay null.
 */
export function parseGatewayPulse(text: string): GatewayPulse {
  let uptimeSeconds: number | null = null
  let requestsTotal: number | null = null
  let errorsTotal: number | null = null
  let heapUsedBytes: number | null = null
  let heapMaxBytes: number | null = null
  let liveStreams: number | null = null
  for (const line of scanLines(text)) {
    if (line.length === 0 || line.startsWith('#') || line.length > MAX_LINE_LENGTH) continue
    const name = metricName(line)
    if (name === 'process_uptime_seconds') {
      const value = sampleValue(line)
      if (value !== null && uptimeSeconds === null) uptimeSeconds = value
      continue
    }
    if (name === METRIC_COUNT) {
      const value = sampleValue(line)
      if (value === null) continue
      requestsTotal = (requestsTotal ?? 0) + value
      if (/status="5\d\d"/.test(line)) errorsTotal = (errorsTotal ?? 0) + value
      continue
    }
    if (name === 'jvm_memory_used_bytes' && line.includes('area="heap"')) {
      const value = sampleValue(line)
      if (value !== null) heapUsedBytes = (heapUsedBytes ?? 0) + value
      continue
    }
    if (name === 'jvm_memory_max_bytes' && line.includes('area="heap"')) {
      const value = sampleValue(line)
      if (value !== null) heapMaxBytes = (heapMaxBytes ?? 0) + value
      continue
    }
    if (name === 'sse_connection_active') {
      const value = sampleValue(line)
      if (value !== null) liveStreams = value
    }
  }
  if (requestsTotal !== null && errorsTotal === null) errorsTotal = 0
  return { uptimeSeconds, requestsTotal, errorsTotal, heapUsedBytes, heapMaxBytes, liveStreams }
}
