/**
 * Reports whether a probe failure carries an answered HTTP status.
 *
 * @remarks Probe helpers throw `… failed: HTTP <n>` when the backend
 * answers with a failure; network-level failures (refused, DNS, CORS)
 * surface as bare `TypeError`s with no status. Only the former supports a
 * verdict about the gateway — the latter renders muted with a retry
 * instead of a red alarm, since a dev CORS gap and a real outage look
 * identical from here.
 *
 * @param error - Caught probe failure.
 * @returns True when the backend answered with an HTTP failure.
 */
export function isProbeHttpFailure(error: unknown): boolean {
  return error instanceof Error && /failed: HTTP \d+/.test(error.message)
}
