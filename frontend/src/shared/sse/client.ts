/**
 * Native SSE client over `fetch` + `ReadableStream.getReader()`.
 *
 * @remarks
 * Chosen over `@microsoft/fetch-event-source` (stale since 2021, no malformed
 * counter, no heartbeat, `document`-coupled): zero dependencies, Bearer
 * headers (never in URLs), POST bodies, abort wiring, bounded exponential
 * backoff with jitter, heartbeat watchdog, malformed-frame accounting, and
 * explicit `[DONE]` handling. Token text is appended through a caller-owned
 * ref buffer — this client never stores the transcript itself, so long
 * sessions stay memory-bounded.
 */

export const SSE_DONE = '[DONE]'
const SSE_CONTENT_TYPE = 'text/event-stream'

/**
 * Per-frame ceiling (chars ≈ bytes for SSE text). Oversized frames are
 * dropped and counted via `onMalformed`, never accumulated: a peer that
 * never sends a blank line cannot grow the tab.
 */
const MAX_SSE_FRAME_CHARS = 1_048_576
/**
 * Per-attempt stream ceiling. Breaching ends the stream with an error
 * instead of retrying: re-downloading a flood would hammer the gateway.
 */
const MAX_SSE_STREAM_CHARS = 8_388_608

export interface SseCallbacks {
  /** Invoked once per `data:` payload (excluding `[DONE]`). */
  onMessage: (data: string) => void
  /** Invoked when `[DONE]` arrives or the stream closes cleanly. */
  onDone?: () => void
  /** Invoked when retries are exhausted or the handshake fails fatally. */
  onError?: (error: Error) => void
  /** Invoked per malformed frame with the running total (never fatal). */
  onMalformed?: (count: number) => void
  /**
   * Invoked before each automatic retry with the 1-based retry number, so
   * the UI can surface how many rebroadcasts a run needed.
   */
  onRetry?: (attempt: number) => void
  /**
   * Invoked when the stream fails after delivering frames. The partial
   * transcript is kept and no automatic retry follows: retrying would bill
   * a second completion and interleave a second generation. Falls back to
   * `onError` when absent.
   */
  onIncomplete?: (error: Error, delivered: number) => void
}

export interface SseRequest {
  url: string
  method?: 'GET' | 'POST'
  headers: Record<string, string>
  body?: unknown
  signal: AbortSignal
  maxRetries?: number
  heartbeatMs?: number
  /**
   * Idempotency key for the logical run. Generated per `openSseStream`
   * call when absent and reused across every retry, so a retried
   * completion replays byte-identically instead of billing twice.
   */
  idempotencyKey?: string
  /**
   * Receives the handshake response headers once the stream is accepted.
   * Lets the shell mirror operational headers (for example rate limits)
   * without touching the token flow. Handshakes never carry a deny code.
   */
  onHeaders?: (headers: Headers, code: null) => void
  /**
   * Optional slot receiving the active stream reader. Lets the caller cancel
   * a pending `read()` on user stop: aborting `fetch` alone does not settle
   * reads that already resolved headers. Cleared when the stream settles.
   */
  readerSlot?: { current: ReadableStreamDefaultReader<Uint8Array> | null }
}

/**
 * Extracts the `data:` payload from one double-newline-delimited SSE frame.
 *
 * @param frame - Raw frame text without the trailing blank line.
 * @returns The joined payload, or null when the frame carries no `data:` field.
 */
export function parseSseFrame(frame: string): string | null {
  const lines = frame.split('\n')
  const payload: string[] = []
  for (const line of lines) {
    if (line.startsWith('data:')) {
      payload.push(line.slice(5).replace(/^ /, ''))
    } else if (line.startsWith(':')) {
      continue
    }
  }
  if (payload.length === 0) return null
  return payload.join('\n')
}

/**
 * Handshake rejection carrying the HTTP status for retry classification.
 * Only the transport raises it; callers distinguish deterministic denials
 * (400/401/403/404/422 — surface immediately) from transient ones
 * (408/429/5xx — worth a bounded retry) via `isRetryableHandshakeStatus`.
 */
export class SseHandshakeError extends Error {
  /** HTTP status that refused the stream. */
  readonly status: number

  /**
   * @param status - Refusing HTTP status.
   */
  constructor(status: number) {
    super(`SSE handshake failed: HTTP ${String(status)}`)
    this.name = 'SseHandshakeError'
    this.status = status
  }
}

/**
 * Decides whether a refused handshake deserves a retry.
 *
 * @param status - Refusing HTTP status.
 * @returns True for 408/429/5xx (transient); false for other 4xx
 * (deterministic — retrying hammers the gateway for ~31 s with no hope).
 */
export function isRetryableHandshakeStatus(status: number): boolean {
  return status === 408 || status === 429 || status >= 500
}

/**
 * Normalizes an unknown rejection reason into an `Error`.
 *
 * @remarks
 * Fetch implementations and stream readers are supposed to reject with
 * `Error` instances, but non-conforming promises exist in the wild. Wrapping
 * anything else preserves the failure instead of crashing error handling.
 *
 * @param reason - Rejection reason of unknown shape.
 * @returns The reason itself when already an `Error`, otherwise a wrapper.
 */
export function asError(reason: unknown): Error {
  return reason instanceof Error ? reason : new Error(String(reason))
}

/**
 * Computes the reconnect delay for an attempt with full jitter.
 *
 * @param attempt - Zero-based attempt index.
 * @returns Milliseconds to wait: `min(1000 * 2^attempt, 30000)` plus up to 500ms jitter.
 */
export function backoffDelay(attempt: number): number {
  const capped = Math.min(1000 * 2 ** attempt, 30_000)
  return capped + Math.random() * 500
}

/**
 * Sleep that wakes early when the abort signal fires.
 *
 * @remarks
 * A plain `setTimeout` sleep would hold a doomed reconnect delay to full
 * term after the user hits Stop. Racing the timer against the abort event
 * keeps teardown immediate, and the listener is removed on whichever arm
 * settles first so repeated retries never leak listeners.
 *
 * @param ms - Milliseconds to wait.
 * @param signal - Abort signal that short-circuits the wait.
 */
function abortableSleep(ms: number, signal: AbortSignal): Promise<void> {
  // No `signal.aborted` pre-check: the sole caller reaches sleep
  // synchronously from a catch block that just observed a live signal, and
  // JavaScript runs that stretch without interleaving abort events. Should a
  // caller ever violate that contract, the wait is still bounded by the
  // backoff cap and the follow-up fetch rejects on the dead signal.
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', onAbort)
      resolve()
    }, ms)
    const onAbort = (): void => {
      clearTimeout(timer)
      reject(new DOMException('Aborted', 'AbortError'))
    }
    signal.addEventListener('abort', onAbort, { once: true })
  })
}

/**
 * Mints one idempotency key per logical stream run.
 *
 * @remarks Callers pass the result as `idempotencyKey` (or let
 * `openSseStream` mint one). A manual "retry run" mints a fresh key: it is
 * a new billed run by operator intent, never a replay.
 *
 * @returns A unique opaque key for the run.
 */
export function createIdempotencyKey(): string {
  const g = globalThis as { crypto?: { randomUUID?: () => string } }
  if (typeof g.crypto?.randomUUID === 'function') return g.crypto.randomUUID()
  return `${Date.now().toString(36)}-${Math.floor(Math.random() * 0xffffff).toString(36)}`
}

/**
 * Opens an SSE stream with bounded retries and a heartbeat watchdog.
 *
 * @param req - Request, callbacks, and tuning (see {@link SseRequest}).
 */
export async function openSseStream(req: SseRequest & SseCallbacks): Promise<void> {
  const maxRetries = req.maxRetries ?? 5
  const heartbeatMs = req.heartbeatMs ?? 30_000
  const idempotencyKey = req.idempotencyKey ?? createIdempotencyKey()
  let attempt = 0
  let malformed = 0
  let delivered = 0

  const connect = async (): Promise<void> => {
    let buffer = ''
    // A chunk boundary may split a CRLF pair: a trailing CR is held back
    // and reattached to the next chunk before normalization, so a split
    // pair never becomes a false frame boundary.
    let pendingCR = false
    let streamChars = 0
    let reader: ReadableStreamDefaultReader<Uint8Array> | undefined
    let watchdog: ReturnType<typeof setTimeout> | undefined

    const armWatchdog = (): void => {
      clearTimeout(watchdog)
      watchdog = setTimeout(() => {
        void reader?.cancel(new Error('SSE heartbeat timeout'))
      }, heartbeatMs)
    }

    try {
      const res = await fetch(req.url, {
        method: req.method ?? 'POST',
        headers: { ...req.headers, Accept: SSE_CONTENT_TYPE, 'Idempotency-Key': idempotencyKey },
        ...(req.body === undefined ? {} : { body: JSON.stringify(req.body) }),
        signal: req.signal,
      })
      if (!res.ok) {
        throw new SseHandshakeError(res.status)
      }
      if (res.headers.get('content-type')?.startsWith(SSE_CONTENT_TYPE) !== true) {
        throw new Error('SSE handshake failed: unexpected content type')
      }
      req.onHeaders?.(res.headers, null)
      if (res.body === null) throw new Error('SSE handshake failed: empty body')

      reader = res.body.getReader()
      if (req.readerSlot) req.readerSlot.current = reader
      try {
        const decoder = new TextDecoder()
        armWatchdog()

        for (;;) {
          const { done, value } = await reader.read()
          if (done) break
          armWatchdog()
          let chunk = decoder.decode(value, { stream: true })
          if (pendingCR) {
            chunk = '\r' + chunk
            pendingCR = false
          }
          if (chunk.endsWith('\r')) {
            pendingCR = true
            chunk = chunk.slice(0, -1)
          }
          // WHATWG SSE lines may end CRLF, LF, or CR: normalize before
          // scanning so CRLF-only peers dispatch instead of buffering
          // forever while the tab grows.
          chunk = chunk.replace(/\r\n|\r/g, '\n')
          streamChars += chunk.length
          if (streamChars > MAX_SSE_STREAM_CHARS) {
            malformed += 1
            req.onMalformed?.(malformed)
            req.onError?.(new Error('SSE stream byte budget exceeded.'))
            return
          }
          buffer += chunk
          let idx = buffer.indexOf('\n\n')
          while (idx >= 0) {
            const frame = buffer.slice(0, idx)
            buffer = buffer.slice(idx + 2)
            if (frame.length > MAX_SSE_FRAME_CHARS) {
              malformed += 1
              req.onMalformed?.(malformed)
            } else {
              const data = parseSseFrame(frame)
              if (data === null) {
                malformed += 1
                req.onMalformed?.(malformed)
              } else if (data === SSE_DONE) {
                req.onDone?.()
                return
              } else {
                delivered += 1
                req.onMessage(data)
              }
            }
            idx = buffer.indexOf('\n\n')
          }
        }
        req.onDone?.()
      } finally {
        try {
          reader.releaseLock()
        } catch {
          // Reader already closed or cancelled; nothing to release.
        }
        if (req.readerSlot) req.readerSlot.current = null
      }
    } catch (error) {
      if (req.signal.aborted) {
        req.onError?.(new Error('Stream aborted.'))
        return
      }
      if (delivered > 0) {
        const incomplete = asError(error)
        if (req.onIncomplete !== undefined) req.onIncomplete(incomplete, delivered)
        else req.onError?.(incomplete)
        return
      }
      if (error instanceof SseHandshakeError && !isRetryableHandshakeStatus(error.status)) {
        req.onError?.(error)
        return
      }
      if (attempt < maxRetries) {
        const delay = backoffDelay(attempt)
        attempt += 1
        req.onRetry?.(attempt)
        try {
          await abortableSleep(delay, req.signal)
        } catch {
          // Aborted mid-wait: report teardown, never a reconnect.
          req.onError?.(new Error('Stream aborted.'))
          return
        }
        await connect()
        return
      }
      req.onError?.(asError(error))
    } finally {
      clearTimeout(watchdog)
    }
  }

  await connect()
}
