import { describe, expect, it } from 'vitest'
import { appendLatencyPoint, histogramQuantile, parsePrometheusHistogram } from './prometheus.js'
import type { Accumulator, HistogramSnapshot } from './prometheus.js'

const SCRAPE = [
  '# HELP http_server_requests_seconds Duration of HTTP server request handling.',
  '# TYPE http_server_requests_seconds histogram',
  'http_server_requests_seconds_bucket{method="POST",outcome="SUCCESS",status="200",uri="/v1/chat/completions",le="0.05"} 12',
  'http_server_requests_seconds_bucket{method="POST",outcome="SUCCESS",status="200",uri="/v1/chat/completions",le="0.5"} 40',
  'http_server_requests_seconds_bucket{method="POST",outcome="SUCCESS",status="200",uri="/v1/chat/completions",le="+Inf"} 41',
  'http_server_requests_seconds_count{method="POST",outcome="SUCCESS",status="200",uri="/v1/chat/completions"} 41',
  'http_server_requests_seconds_bucket{method="GET",outcome="SUCCESS",status="200",uri="/actuator/health",le="0.05"} 999',
  'jvm_memory_used_bytes{area="heap"} 1.2e+08',
  '# EOF',
].join('\n')

function snapshotAt(count: number, buckets: [number, number][]): HistogramSnapshot {
  return {
    buckets: buckets.map(([le, bucketCount]) => ({ le, count: bucketCount })),
    count,
  }
}

describe('parsePrometheusHistogram', () => {
  it('aggregates v1 bucket series and ignores the rest', () => {
    const snap = parsePrometheusHistogram(SCRAPE)
    expect(snap).toEqual({
      buckets: [
        { le: 0.05, count: 12 },
        { le: 0.5, count: 40 },
        { le: Infinity, count: 41 },
      ],
      count: 41,
    })
  })

  it('returns null without v1 bucket series', () => {
    expect(parsePrometheusHistogram('# HELP x\nelephant 5\n# EOF')).toBeNull()
    expect(parsePrometheusHistogram('')).toBeNull()
    expect(parsePrometheusHistogram('cacherelay_tokens_total{provider="x"} 5\n')).toBeNull()
  })

  it('skips braceless lines and lines without a value separator', () => {
    const text = [
      'http_server_requests_seconds_bucket 5',
      'http_server_requests_seconds_bucket{uri="/v1/x",le="0.1"}5',
      'http_server_requests_seconds_bucket{uri="/v1/x"} 3',
      'http_server_requests_seconds_bucket{uri="/v1/x",le="-0.1"} 3',
      'http_server_requests_seconds_bucket{uri="/v1/x",le="0.1"} 7',
      'http_server_requests_seconds_count{uri="/v1/x"} 7',
    ].join('\n')
    expect(parsePrometheusHistogram(text)).toEqual({
      buckets: [{ le: 0.1, count: 7 }],
      count: 7,
    })
  })

  it('stops aggregating past the bucket cap', () => {
    const buckets = Array.from(
      { length: 110 },
      (_, i) =>
        `http_server_requests_seconds_bucket{uri="/v1/x",le="0.${String(i).padStart(3, '0')}"} 1`,
    ).join('\n')
    const snap = parsePrometheusHistogram(buckets)
    expect(snap?.buckets.length).toBeLessThanOrEqual(101)
  })

  it('skips malformed, non-finite, and oversized lines', () => {
    const text = [
      'http_server_requests_seconds_bucket{uri="/v1/x",le="0.1"} not-a-number',
      'http_server_requests_seconds_bucket{uri="/v1/x",le="oops"} 3',
      'http_server_requests_seconds_bucket{uri="/v1/x",le="0.1"} -2',
      `http_server_requests_seconds_bucket{uri="/v1/x",le="0.1"} 4${' '.repeat(5000)}`,
      'http_server_requests_seconds_bucket{uri="/v1/x",le="0.1"} 7',
      'http_server_requests_seconds_count{uri="/v1/x"} 7',
    ].join('\n')
    expect(parsePrometheusHistogram(text)).toEqual({
      buckets: [{ le: 0.1, count: 7 }],
      count: 7,
    })
  })

  it('treats label injection as opaque text, never code', () => {
    const text = [
      'http_server_requests_seconds_bucket{uri="/v1/<script>alert(1)</script>",le="0.1"} 2',
      'http_server_requests_seconds_count{uri="/v1/<script>alert(1)</script>"} 2',
    ].join('\n')
    const snap = parsePrometheusHistogram(text)
    expect(snap?.count).toBe(2)
    expect(snap?.buckets).toEqual([{ le: 0.1, count: 2 }])
  })
})

describe('histogramQuantile', () => {
  const buckets = [
    { le: 0.05, count: 12 },
    { le: 0.5, count: 28 },
    { le: Infinity, count: 1 },
  ]

  it('returns the first bound covering the quantile', () => {
    expect(histogramQuantile(buckets, 41, 0.5)).toBe(0.5)
    expect(histogramQuantile(buckets, 41, 0.1)).toBe(0.05)
  })

  it('returns null when the quantile only resolves at +Inf', () => {
    expect(histogramQuantile(buckets, 41, 0.99)).toBeNull()
  })

  it('rejects empty intervals and out-of-range quantiles', () => {
    expect(histogramQuantile(buckets, 0, 0.5)).toBeNull()
    expect(histogramQuantile(buckets, 41, 0)).toBeNull()
    expect(histogramQuantile(buckets, 41, 1.5)).toBeNull()
  })

  it('returns null when samples never reach the threshold', () => {
    expect(histogramQuantile([{ le: 0.5, count: 5 }], 100, 0.5)).toBeNull()
  })
})

describe('appendLatencyPoint', () => {
  it('seeds on the first scrape without emitting a point', () => {
    const snap = snapshotAt(41, [
      [0.05, 12],
      [0.5, 40],
    ])
    const { point, acc } = appendLatencyPoint(null, snap, 1_000)
    expect(point).toBeNull()
    expect(acc.count).toBe(41)
  })

  it('derives p50, p95, and rps from bucket deltas', () => {
    const prev: Accumulator = {
      buckets: [
        { le: 0.05, count: 12 },
        { le: 0.5, count: 40 },
        { le: Infinity, count: 41 },
      ],
      count: 41,
      atMs: 1_000,
    }
    const next = snapshotAt(141, [
      [0.05, 32],
      [0.5, 130],
      [Infinity, 141],
    ])
    const { point } = appendLatencyPoint(prev, next, 16_000)
    expect(point).not.toBeNull()
    expect(point?.p50).toBe(500)
    expect(point?.p95).toBe(500)
    expect(point?.rps).toBeCloseTo(100 / 15, 5)
  })

  it('clamps counter resets instead of going negative', () => {
    const prev: Accumulator = {
      buckets: [{ le: 0.5, count: 900 }],
      count: 900,
      atMs: 1_000,
    }
    const next = snapshotAt(10, [[0.5, 10]])
    const first = appendLatencyPoint(prev, next, 16_000)
    expect(first.point).toBeNull()
    expect(first.acc.count).toBe(10)
    const { point } = appendLatencyPoint(first.acc, snapshotAt(160, [[0.5, 160]]), 31_000)
    expect(point?.rps).toBeCloseTo(150 / 15, 5)
  })

  it('emits nothing when the interval carries no new samples', () => {
    const prev: Accumulator = {
      buckets: [{ le: 0.5, count: 40 }],
      count: 41,
      atMs: 1_000,
    }
    const next = snapshotAt(41, [[0.5, 40]])
    expect(appendLatencyPoint(prev, next, 16_000).point).toBeNull()
  })

  it('emits nothing when scrapes share a timestamp', () => {
    const prev: Accumulator = {
      buckets: [{ le: 0.5, count: 40 }],
      count: 41,
      atMs: 16_000,
    }
    const next = snapshotAt(141, [[0.5, 140]])
    expect(appendLatencyPoint(prev, next, 16_000).point).toBeNull()
  })

  it('treats brand-new buckets as starting from zero', () => {
    const prev: Accumulator = {
      buckets: [{ le: 0.5, count: 40 }],
      count: 41,
      atMs: 1_000,
    }
    const next = snapshotAt(141, [
      [0.5, 40],
      [5, 100],
    ])
    const { point } = appendLatencyPoint(prev, next, 16_000)
    expect(point?.p95).toBe(5000)
  })

  it('reports unknown latency when samples only resolve at +Inf', () => {
    const prev: Accumulator = {
      buckets: [{ le: 0.5, count: 5 }],
      count: 5,
      atMs: 1_000,
    }
    const next = snapshotAt(15, [
      [0.5, 5],
      [Infinity, 15],
    ])
    const { point } = appendLatencyPoint(prev, next, 16_000)
    expect(point).not.toBeNull()
    expect(point?.p50).toBeNull()
    expect(point?.p95).toBeNull()
    expect(point?.rps).toBeCloseTo(10 / 15, 5)
  })
})
