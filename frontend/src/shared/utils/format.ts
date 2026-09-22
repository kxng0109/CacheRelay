/**
 * Human number/date formatting: every operational figure the console renders
 * passes through here so raw backend values (byte counts, micro-dollars,
 * ISO instants) never reach the eye unformatted.
 *
 * @remarks Intl-only, zero dependencies. Compact counts pin
 * `maximumFractionDigits` explicitly (bare compact defaults vary by
 * locale); byte buckets divide manually (no auto-scale unit API exists);
 * money trims to significant decimals without `roundingMode` (94% runtime
 * support is not enough for a console). Dates use explicit fields, never
 * `dateStyle`/`timeStyle` (locale-unstable).
 */

const COMPACT = new Intl.NumberFormat('en-US', {
  notation: 'compact',
  maximumFractionDigits: 1,
})

const GROUPED = new Intl.NumberFormat('en-US')

const BYTES: readonly { limit: number; unit: string }[] = [
  { limit: 1024 ** 4, unit: 'TB' },
  { limit: 1024 ** 3, unit: 'GB' },
  { limit: 1024 ** 2, unit: 'MB' },
  { limit: 1024, unit: 'KB' },
]

const SHORT_DATE = new Intl.DateTimeFormat('en-US', {
  month: 'short',
  day: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
})

/**
 * Formats a count compactly for tiles and rails.
 *
 * @param n - Raw count.
 * @returns Compact form (`6691` → `"6.7K"`), grouped below one thousand.
 */
export function formatCount(n: number): string {
  if (n < 1000) return GROUPED.format(n)
  return COMPACT.format(n)
}

/**
 * Formats a byte count with an explicit unit.
 *
 * @param n - Raw bytes.
 * @returns Bucketed form (`268435456` → `"256 MB"`), no decimals.
 */
export function formatBytes(n: number): string {
  for (const { limit, unit } of BYTES) {
    if (n >= limit) return `${String(Math.round(n / limit))} ${unit}`
  }
  return `${String(n)} B`
}

/**
 * Formats micro-dollars as dollars with significant decimals.
 *
 * @remarks Value-neutral: zero reads `"$0.00"`; screens choose the
 * zero-word (`free`, muted `0`) at the call site.
 *
 * @param micros - Micro-dollar amount.
 * @returns Dollar form (`4525000` → `"$4.525"`, `0` → `"$0.00"`).
 */
export function formatUsd(micros: number): string {
  if (micros === 0) return '$0.00'
  const fixed = (micros / 1_000_000).toFixed(6)
  const trimmed = fixed.replace(/0+$/, '').replace(/\.$/, '')
  const dot = trimmed.indexOf('.')
  if (dot < 0) return `$${trimmed}.00`
  const decimals = trimmed.length - dot - 1
  return decimals >= 2 ? `$${trimmed}` : `$${trimmed}${'0'.repeat(2 - decimals)}`
}

/**
 * Formats a micro-dollar cost for dense table cells.
 *
 * @param micros - Micro-dollar amount.
 * @returns Grouped form (`1184` → `"1,184µ$"`).
 */
export function formatMicros(micros: number): string {
  return `${GROUPED.format(micros)}µ$`
}

/**
 * Formats a duration for tiles and inspector cards.
 *
 * @param ms - Duration in milliseconds.
 * @returns Compact form (`92` → `"92ms"`, `40.5` → `"40.5ms"`).
 */
export function formatDurationMs(ms: number): string {
  return `${String(Math.round(ms * 10) / 10)}ms`
}

/**
 * Formats an ISO instant as a short local date-time.
 *
 * @param iso - ISO-8601 instant.
 * @returns Short form (`"Sep 22, 02:42 PM"`); the raw value back when unparseable.
 */
export function formatShortDate(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return SHORT_DATE.format(d)
}
