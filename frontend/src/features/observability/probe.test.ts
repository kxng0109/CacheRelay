import { describe, expect, it } from 'vitest'
import { isProbeHttpFailure } from './probe.js'

describe('isProbeHttpFailure', () => {
  it('recognizes answered HTTP failures', () => {
    expect(isProbeHttpFailure(new Error('Health probe failed: HTTP 503. Retry shortly.'))).toBe(
      true,
    )
    expect(isProbeHttpFailure(new Error('Metrics scrape failed: HTTP 404. Retry shortly.'))).toBe(
      true,
    )
  })

  it('rejects network-level failures and non-errors', () => {
    expect(isProbeHttpFailure(new TypeError('Failed to fetch'))).toBe(false)
    expect(
      isProbeHttpFailure(new Error('Metrics endpoint answered text/html, not Prometheus text.')),
    ).toBe(false)
    expect(isProbeHttpFailure(null)).toBe(false)
    expect(isProbeHttpFailure('HTTP 500')).toBe(false)
  })
})
