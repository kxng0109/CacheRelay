import { describe, expect, it } from 'vitest'
import {
  formatBytes,
  formatCount,
  formatDurationMs,
  formatMicros,
  formatShortDate,
  formatUsd,
} from './format.js'

describe('formatCount', () => {
  it('groups below one thousand and compacts above', () => {
    expect(formatCount(999)).toBe('999')
    expect(formatCount(6691)).toBe('6.7K')
    expect(formatCount(87708)).toBe('87.7K')
  })
})

describe('formatBytes', () => {
  it('buckets to explicit units without decimals', () => {
    expect(formatBytes(512)).toBe('512 B')
    expect(formatBytes(268435456)).toBe('256 MB')
    expect(formatBytes(2147483648)).toBe('2 GB')
    expect(formatBytes(1536)).toBe('2 KB')
    expect(formatBytes(5497558138880)).toBe('5 TB')
  })
})

describe('formatUsd', () => {
  it('trims to significant decimals with a two-decimal floor', () => {
    expect(formatUsd(0)).toBe('$0.00')
    expect(formatUsd(4525000)).toBe('$4.525')
    expect(formatUsd(1000000)).toBe('$1.00')
    expect(formatUsd(100000)).toBe('$0.10')
  })
})

describe('formatMicros', () => {
  it('groups dense table costs', () => {
    expect(formatMicros(0)).toBe('0µ$')
    expect(formatMicros(1184)).toBe('1,184µ$')
  })
})

describe('formatDurationMs', () => {
  it('keeps one decimal only when significant', () => {
    expect(formatDurationMs(92)).toBe('92ms')
    expect(formatDurationMs(40.5)).toBe('40.5ms')
  })
})

describe('formatShortDate', () => {
  it('shortens valid instants and echoes garbage verbatim', () => {
    expect(formatShortDate('2026-09-22T14:42:31.000Z')).toContain('Sep 22')
    expect(formatShortDate('not-a-date')).toBe('not-a-date')
  })
})
