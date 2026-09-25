import { useEffect, useRef, useState } from 'react'
import { parseRateLimit, resolveApiBase } from '../../shared/api/client.js'
import { openSseStream } from '../../shared/sse/client.js'
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
  /** Terminal phase. */
  phase: 'done' | 'error'
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
  const [phase, setPhase] = useState<'streaming' | 'done' | 'error'>('streaming')
  const [error, setError] = useState<string | null>(null)
  const [malformed, setMalformed] = useState(0)
  const [tokens, setTokens] = useState(0)
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
    startRef.current = performance.now()
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
        bufferRef.current += extractPiece(data)
        frames += 1
        setTokens(frames)
        schedule()
      },
      onMalformed: (count) => {
        malformed = count
        setMalformed(count)
      },
      onDone: () => {
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
          })
        })
      },
      onError: (e) => {
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
  }, [token, actAsKey, model, messages, maxRetries, heartbeatMs])

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
              stopStream()
            }}
            className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
          >
            Stop
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
