import { useEffect, useRef, useState } from 'react'
import { parseRateLimit, resolveApiBase } from '../../shared/api/client.js'
import { createIdempotencyKey, openSseStream } from '../../shared/sse/client.js'
import { useRateLimitStore } from '../../shared/ratelimit/store.js'
import type { ChatMessage } from '../../shared/api/types.js'

/**
 * Final stream facts reported to the parent when the stream settles.
 * Powers the run detail panel without the parent polling viewer state.
 */
export interface StreamSummary {
  /** SSE frames received. */
  frames: number
  /** Malformed frames skipped. */
  malformed: number
  /** Cache tier from `X-Cache`, or null for provider backed live runs. */
  cacheTier: string | null
  /** Semantic similarity score, cache hits only. */
  similarity: string | null
  /** Entry age in seconds, cache hits only. */
  age: string | null
  /** Wall clock milliseconds from mount to settle. */
  durationMs: number
  /**
   * Terminal phase. `incomplete` means frames arrived before the failure:
   * the partial transcript is kept and nothing was retried automatically.
   * `stopped` means the operator pressed Stop: a first-class neutral
   * outcome, never an error (DEF-06).
   */
  phase: 'done' | 'error' | 'incomplete' | 'stopped'
  /** Failure message, error runs only. */
  error?: string
}

interface SseStreamViewerProps {
  token: string
  /** Owned key hash for act-as-self streams (session JWT supplies auth). */
  actAsKey?: string
  model: string
  messages: ChatMessage[]
  /**
   * Stream tuning overrides. Production uses client defaults (5 retries,
   * 30 s heartbeat); tests pin small values for fast determinism.
   */
  streamOptions?: {
    maxRetries?: number
    heartbeatMs?: number
  }
  /**
   * Settles once with final stream facts. The parent feeds the run detail
   * panel from it; the viewer keeps rendering the transcript itself.
   */
  onSummary?: (summary: StreamSummary) => void
}

/**
 * Extracts incremental text from one SSE `data:` payload.
 *
 * @param data - Raw payload (OpenAI chunk JSON or plain text).
 * @returns The text piece to append.
 */
function extractPiece(data: string): string {
  try {
    const parsed: unknown = JSON.parse(data)
    if (typeof parsed === 'object' && parsed !== null) {
      const choices = (parsed as Record<string, unknown>).choices
      if (Array.isArray(choices) && choices.length > 0) {
        const first = choices[0] as Record<string, unknown>
        const delta = first.delta as Record<string, unknown> | undefined
        const message = first.message as Record<string, unknown> | undefined
        const content = delta?.content ?? message?.content
        if (typeof content === 'string') return content
      }
    }
    return data
  } catch {
    return data
  }
}

/**
 * Transcript ceiling (chars). Past it the viewer stops the stream with a
 * visible notice instead of growing one string plus one giant text node
 * until the tab stalls. The client stream cap stays larger, so truncation
 * always lands here first with a coherent message.
 */
const MAX_TRANSCRIPT_CHARS = 2_097_152

/**
 * Live token stream viewer for chat completions.
 *
 * @remarks
 * Proof-type: live (renders real gateway SSE bytes). The parent mounts one
 * viewer per run via `key`, so mount always means a fresh stream — no state
 * resets inside the effect. Tokens append into a ref and flush on animation
 * frames — no re-render per token, no unbounded arrays. Abort is wired to
 * the Stop button and to unmount.
 *
 * @param props - Bearer token, model, messages, and optional stream tuning.
 * @returns The streaming transcript region with controls and counters.
 */
export function SseStreamViewer({
  token,
  actAsKey,
  model,
  messages,
  streamOptions,
  onSummary,
}: SseStreamViewerProps): React.JSX.Element {
  const [text, setText] = useState('')
  const [phase, setPhase] = useState<'streaming' | 'done' | 'error' | 'incomplete' | 'stopped'>(
    'streaming',
  )
  const [error, setError] = useState<string | null>(null)
  const [malformed, setMalformed] = useState(0)
  const [tokens, setTokens] = useState(0)
  const [retries, setRetries] = useState(0)
  /**
   * Manual-run counter. Bumping it restarts the stream effect with a fresh
   * idempotency key: an operator-confirmed retry is a new billed run, never
   * a replay of the chopped one.
   */
  const [runNonce, setRunNonce] = useState(0)
  const [copied, setCopied] = useState(false)
  const [copiedBytes, setCopiedBytes] = useState(0)
  const [copyError, setCopyError] = useState<string | null>(null)
  /**
   * Cache provenance from response headers. `X-Cache` is present only on
   * cache-hit chat (contract §3.1 swaps provider headers for it); its
   * absence means a provider-backed live response. Similarity and Age ride
   * alongside the tier on hits.
   */
  const [cacheTier, setCacheTier] = useState<string | null>(null)
  const [cacheSimilarity, setCacheSimilarity] = useState<string | null>(null)
  const [cacheAge, setCacheAge] = useState<string | null>(null)
  const bufferRef = useRef('')
  const rafRef = useRef(0)
  const ctrlRef = useRef<AbortController | null>(null)
  const readerRef = useRef<ReadableStreamDefaultReader<Uint8Array> | null>(null)
  const [truncated, setTruncated] = useState(false)
  /**
   * Truncation flag readable inside stream callbacks without retriggering
   * the effect (state alone would go stale in the closure).
   */
  const truncatedRef = useRef(false)
  /**
   * Mount time for stream duration. Reported once in the summary so the
   * detail panel shows a real number without polling viewer state.
   */
  const startRef = useRef(0)
  /** Latest summary callback without retriggering the stream effect. */
  const summaryRef = useRef(onSummary)
  useEffect(() => {
    summaryRef.current = onSummary
  })

  /**
   * User-stop flag readable inside stream callbacks. Set only by the Stop
   * button (never by truncation or unmount, which share `stopStream`):
   * a flagged settle maps to the neutral `stopped` outcome instead of
   * `done` or `error`, so stopping never reads as a failure (DEF-06).
   * Abort still settles the input-known portion — backend hold semantics
   * are unchanged; only the presentation is neutral.
   */
  const stopRequestedRef = useRef(false)

  /**
   * Stops the active stream: cancels the pending reader first (an aborted
   * fetch alone never settles reads that already resolved headers), then
   * aborts the request itself.
   */
  const stopStream = (): void => {
    const reader = readerRef.current
    readerRef.current = null
    if (reader) void reader.cancel(new Error('Stopped by user.'))
    ctrlRef.current?.abort()
  }

  /**
   * User-initiated stop: marks the run stopped, then tears down the
   * stream. Truncation and unmount call `stopStream` directly so their
   * settles keep their own meaning.
   */
  const stopByUser = (): void => {
    stopRequestedRef.current = true
    stopStream()
  }

  /**
   * Restarts the stream as a new billed run after an incomplete stop.
   * Per-run state resets; the effect mints a fresh idempotency key.
   */
  const retryRun = (): void => {
    bufferRef.current = ''
    setText('')
    setPhase('streaming')
    setError(null)
    setMalformed(0)
    setTokens(0)
    setRetries(0)
    stopRequestedRef.current = false
    setRunNonce((n) => n + 1)
  }

  /**
   * Copies the streamed transcript. Clipboard absence (non-secure contexts,
   * denied permission) surfaces as an inline alert, never a throw.
   */
  const copyTranscript = (): void => {
    setCopyError(null)
    // `clipboard` is absent in non-secure contexts and older engines; the
    // DOM type marks it present, so narrow through unknown honestly.
    const clip = navigator.clipboard as Clipboard | undefined
    if (clip === undefined) {
      setCopyError('Copy unavailable in this browser.')
      return
    }
    void clip.writeText(text).then(
      () => {
        setCopied(true)
        setCopiedBytes(new TextEncoder().encode(text).length)
      },
      () => {
        setCopyError('Copy failed. Select the text manually.')
      },
    )
  }

  const { maxRetries, heartbeatMs } = streamOptions ?? {}

  useEffect(() => {
    const ctrl = new AbortController()
    ctrlRef.current = ctrl
    stopRequestedRef.current = false
    startRef.current = performance.now()
    // One key per logical run: internal retries replay it, a manual retry
    // mints a fresh one (see runNonce).
    const idempotencyKey = createIdempotencyKey()
    let frames = 0
    let malformed = 0
    let tier: string | null = null
    let similarity: string | null = null
    let age: string | null = null

    const flush = (): void => {
      const current = bufferRef.current
      setText(current)
    }
    const schedule = (): void => {
      cancelAnimationFrame(rafRef.current)
      rafRef.current = requestAnimationFrame(flush)
    }

    const stop = (fn: () => void): void => {
      cancelAnimationFrame(rafRef.current)
      flush()
      fn()
    }

    /**
     * Settles a user stop as neutral: no error text, no alert, summary
     * phase `stopped` without an error message.
     */
    const settleStopped = (): void => {
      stop(() => {
        setPhase('stopped')
        setError(null)
        summaryRef.current?.({
          frames,
          malformed,
          cacheTier: tier,
          similarity,
          age,
          durationMs: performance.now() - startRef.current,
          phase: 'stopped',
        })
      })
    }

    void openSseStream({
      url: `${resolveApiBase()}/v1/chat/completions`,
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
        ...(actAsKey === undefined || actAsKey.length === 0 ? {} : { 'X-Act-As-Key': actAsKey }),
      },
      body: { model, messages, stream: true },
      signal: ctrl.signal,
      readerSlot: readerRef,
      idempotencyKey,
      onHeaders: (headers, code) => {
        useRateLimitStore.getState().setSnapshot(parseRateLimit(headers, code))
        const headerTier = headers.get('X-Cache')
        if (headerTier !== null) {
          tier = headerTier
          similarity = headers.get('X-CacheRelay-Similarity-Score')
          age = headers.get('Age')
          setCacheTier(headerTier)
          setCacheSimilarity(similarity)
          setCacheAge(age)
        }
      },
      ...(maxRetries === undefined ? {} : { maxRetries }),
      ...(heartbeatMs === undefined ? {} : { heartbeatMs }),
      onMessage: (data) => {
        const piece = extractPiece(data)
        if (bufferRef.current.length + piece.length > MAX_TRANSCRIPT_CHARS) {
          truncatedRef.current = true
          setTruncated(true)
          stopStream()
          return
        }
        bufferRef.current += piece
        frames += 1
        setTokens(frames)
        schedule()
      },
      onMalformed: (count) => {
        malformed = count
        setMalformed(count)
      },
      onRetry: (attempt) => {
        setRetries(attempt)
      },
      onIncomplete: (e, delivered) => {
        stop(() => {
          setPhase('incomplete')
          setError(
            `Stream stopped incomplete after ${String(delivered)} frames. Kept what arrived — retry starts a new billed run. (${e.message})`,
          )
          summaryRef.current?.({
            frames,
            malformed,
            cacheTier: tier,
            similarity,
            age,
            durationMs: performance.now() - startRef.current,
            phase: 'incomplete',
            error: e.message,
          })
        })
      },
      onDone: () => {
        if (stopRequestedRef.current) {
          settleStopped()
          return
        }
        stop(() => {
          setPhase('done')
          summaryRef.current?.({
            frames,
            malformed,
            cacheTier: tier,
            similarity,
            age,
            durationMs: performance.now() - startRef.current,
            phase: 'done',
            ...(truncatedRef.current
              ? { error: 'Output truncated — stream stopped at 2 MB.' }
              : {}),
          })
        })
      },
      onError: (e) => {
        if (stopRequestedRef.current) {
          settleStopped()
          return
        }
        stop(() => {
          setPhase('error')
          setError(e.message)
          summaryRef.current?.({
            frames,
            malformed,
            cacheTier: tier,
            similarity,
            age,
            durationMs: performance.now() - startRef.current,
            phase: 'error',
            error: e.message,
          })
        })
      },
    })

    return () => {
      const reader = readerRef.current
      readerRef.current = null
      ctrlRef.current = null
      if (reader) void reader.cancel(new Error('Stream unmounted.'))
      ctrl.abort()
      cancelAnimationFrame(rafRef.current)
    }
  }, [token, actAsKey, model, messages, maxRetries, heartbeatMs, runNonce])

  return (
    <section
      aria-label="Stream output"
      className="rounded-lg border border-ink/10 p-4 dark:border-parchment/10"
    >
      <div className="mb-2 flex flex-wrap items-center gap-3">
        <p role="status" className="text-[13px]">
          Phase: {phase}
        </p>
        <p className="text-[13px] tnum">Frames: {tokens}</p>
        <p className="text-[13px] tnum">Malformed: {malformed}</p>
        <p className="text-[13px] tnum">Retries: {retries}</p>
        <p className="text-[13px] tnum">
          cache: {cacheTier ?? 'live'}
          {cacheSimilarity === null ? null : ` · sim ${cacheSimilarity}`}
          {cacheAge === null ? null : ` · age ${cacheAge}s`}
        </p>
        <span className="flex-1" />
        <button
          type="button"
          onClick={copyTranscript}
          disabled={text.length === 0}
          className="rounded-md border border-ink/15 px-3 py-2 text-[13px] disabled:cursor-not-allowed disabled:border-ink-soft disabled:text-ink-soft dark:border-parchment/15 dark:disabled:border-parchment-soft dark:disabled:text-parchment-soft"
        >
          {copied ? `Copied ${String(copiedBytes)}B` : 'Copy'}
        </button>
        {phase === 'streaming' ? (
          <button
            type="button"
            onClick={() => {
              stopByUser()
            }}
            className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
          >
            Stop
          </button>
        ) : null}
        {phase === 'incomplete' ? (
          <button
            type="button"
            onClick={() => {
              retryRun()
            }}
            className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
          >
            Retry run
          </button>
        ) : null}
      </div>
      {copyError === null ? null : (
        <p role="alert" className="mb-2 text-[13px] text-danger dark:text-danger-soft">
          {copyError}
        </p>
      )}
      {error === null ? null : (
        <p role="alert" className="mb-2 text-[13px] text-danger dark:text-danger-soft">
          {error}
        </p>
      )}
      {phase === 'stopped' ? (
        <p role="status" className="mb-2 text-[13px] text-ink-soft dark:text-parchment-soft">
          Stopped by user.
        </p>
      ) : null}
      {truncated ? (
        <p role="status" className="mb-2 text-[13px] text-warn dark:text-warn-soft">
          Output truncated — stream stopped at 2 MB. Kept the first 2 MB.
        </p>
      ) : null}
      <div
        role="log"
        aria-live="polite"
        aria-label="Streamed completion"
        className="min-h-32 font-mono text-sm whitespace-pre-wrap"
      >
        {text.length === 0 ? 'No output yet. Run a prompt to start streaming.' : text}
        {phase === 'streaming' && text.length > 0 ? (
          <span aria-hidden="true" className="stream-caret">
            ▍
          </span>
        ) : null}
      </div>
    </section>
  )
}
