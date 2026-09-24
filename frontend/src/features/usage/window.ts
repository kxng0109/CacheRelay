/** Trailing default (days) is server-side; the ceiling is enforced both sides. */
export const MAX_WINDOW_DAYS = 90

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
