/** Trailing default (days) is server-side; the ceiling is enforced both sides. */
export const MAX_WINDOW_DAYS = 90

/**
 * Preset window identifiers. Every preset resolves inside the backend
 * 90-day ceiling — there is deliberately no year-long option.
 */
export type PresetId =
  'today' | 'yesterday' | 'past-3d' | 'past-7d' | 'past-30d' | 'past-90d' | 'this-month' | 'custom'

/**
 * Preset picker entries in display order.
 */
export const PRESETS: readonly { id: PresetId; label: string }[] = [
  { id: 'today', label: 'Today' },
  { id: 'yesterday', label: 'Yesterday' },
  { id: 'past-3d', label: 'Past 3 days' },
  { id: 'past-7d', label: 'Past 7 days' },
  { id: 'past-30d', label: 'Past 30 days' },
  { id: 'past-90d', label: 'Past 90 days' },
  { id: 'this-month', label: 'This month' },
  { id: 'custom', label: 'Custom range' },
]

/**
 * Formats a date as a `YYYY-MM-DD` UTC calendar day.
 *
 * @param d - Date to format.
 * @returns UTC calendar day.
 */
function toDatePart(d: Date): string {
  const month = String(d.getUTCMonth() + 1).padStart(2, '0')
  const day = String(d.getUTCDate()).padStart(2, '0')
  return `${String(d.getUTCFullYear())}-${month}-${day}`
}

/**
 * Resolves a preset to ISO-8601 window bounds.
 *
 * @remarks Day arithmetic runs on UTC calendar days so bounds never
 * straddle DST. Spans stay inclusive: past-N covers today plus N-1 days
 * back, keeping every preset under the 90-day ceiling.
 *
 * @param id - Preset identifier (never `custom`).
 * @param now - Current instant (injectable for tests).
 * @returns Start/end instants.
 */
export function presetBounds(
  id: Exclude<PresetId, 'custom'>,
  now: Date = new Date(),
): { fromIso: string; toIso: string } {
  const start = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()))
  const endOf = (d: Date): string => `${toDatePart(d)}T23:59:59Z`
  if (id === 'today') {
    return { fromIso: `${toDatePart(start)}T00:00:00Z`, toIso: endOf(start) }
  }
  if (id === 'yesterday') {
    const day = new Date(start.getTime() - 86_400_000)
    return { fromIso: `${toDatePart(day)}T00:00:00Z`, toIso: endOf(day) }
  }
  if (id === 'this-month') {
    const first = new Date(Date.UTC(start.getUTCFullYear(), start.getUTCMonth(), 1))
    return { fromIso: `${toDatePart(first)}T00:00:00Z`, toIso: endOf(start) }
  }
  const backDays = id === 'past-3d' ? 2 : id === 'past-7d' ? 6 : id === 'past-30d' ? 29 : 89
  const from = new Date(start.getTime() - backDays * 86_400_000)
  return { fromIso: `${toDatePart(from)}T00:00:00Z`, toIso: endOf(start) }
}

/**
 * Converts a `YYYY-MM-DD` date input to an ISO-8601 instant bound.
 *
 * @param date - Date input value.
 * @param end - True for the end-of-day bound, false for start-of-day.
 * @returns ISO instant, or null when the input is blank or malformed.
 */
export function dateToIso(date: string, end: boolean): string | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return null
  return `${date}T${end ? '23:59:59' : '00:00:00'}Z`
}

export interface WindowValidation {
  /** Applied start bound (undefined = backend default). */
  fromIso?: string
  /** Applied end bound (undefined = backend default). */
  toIso?: string
  /** User-safe rejection copy, or null when the window applies. */
  error: string | null
}

/**
 * Validates a dashboard window before it reaches the query.
 *
 * @remarks Blank means backend default (never sent). Reversed and oversize
 * windows are rejected with the same copy the backend 400 path surfaces
 * ("narrow the window"), so client and server agree.
 *
 * @param from - Start date input (`YYYY-MM-DD` or blank).
 * @param to - End date input (`YYYY-MM-DD` or blank).
 * @returns Bounds plus the rejection copy, if any.
 */
export function validateWindow(from: string, to: string): WindowValidation {
  const fromIso = from === '' ? null : dateToIso(from, false)
  const toIso = to === '' ? null : dateToIso(to, true)
  if ((from !== '' && fromIso === null) || (to !== '' && toIso === null)) {
    return { error: 'Use YYYY-MM-DD dates for the window.' }
  }
  if (fromIso !== null && toIso !== null) {
    if (fromIso > toIso) return { error: 'The start date cannot be after the end date.' }
    const days = (Date.parse(toIso) - Date.parse(fromIso)) / 86_400_000
    if (days > MAX_WINDOW_DAYS) {
      return {
        error: `That window is wider than ${String(MAX_WINDOW_DAYS)} days. Narrow the window and retry.`,
      }
    }
  }
  return {
    ...(fromIso === null ? {} : { fromIso }),
    ...(toIso === null ? {} : { toIso }),
    error: null,
  }
}
