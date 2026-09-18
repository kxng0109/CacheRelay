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

export interface SseCallbacks {
  /** Invoked once per `data:` payload (excluding `[DONE]`). */
  onMessage: (data: string) => void
  /** Invoked when `[DONE]` arrives or the stream closes cleanly. */
  onDone?: () => void
  /** Invoked when retries are exhausted or the handshake fails fatally. */
  onError?: (error: Error) => void
  /** Invoked per malformed frame with the running total (never fatal). */
  onMalformed?: (count: number) => void
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
 * Opens an SSE stream with bounded retries and a heartbeat watchdog.
 *
 * @param req - Request, callbacks, and tuning (see {@link SseRequest}).
 */
export async function openSseStream(req: SseRequest & SseCallbacks): Promise<void> {
  const maxRetries = req.maxRetries ?? 5
  const heartbeatMs = req.heartbeatMs ?? 30_000
  let attempt = 0
  let malformed = 0

  const connect = async (): Promise<void> => {
    let buffer = ''
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
        headers: { ...req.headers, Accept: SSE_CONTENT_TYPE },
        ...(req.body === undefined ? {} : { body: JSON.stringify(req.body) }),
        signal: req.signal,
      })
      if (!res.ok || res.headers.get('content-type')?.startsWith(SSE_CONTENT_TYPE) !== true) {
        throw new Error(`SSE handshake failed: HTTP ${String(res.status)}`)
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
          buffer += decoder.decode(value, { stream: true })
          let idx = buffer.indexOf('\n\n')
          while (idx >= 0) {
            const frame = buffer.slice(0, idx)
            buffer = buffer.slice(idx + 2)
            const data = parseSseFrame(frame)
            if (data === null) {
              malformed += 1
              req.onMalformed?.(malformed)
            } else if (data === SSE_DONE) {
              req.onDone?.()
              return
            } else {
              req.onMessage(data)
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
      if (attempt < maxRetries) {
        const delay = backoffDelay(attempt)
        attempt += 1
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
