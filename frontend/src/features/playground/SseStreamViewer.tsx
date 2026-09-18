import { useEffect, useRef, useState } from 'react'
import { parseRateLimit, resolveApiBase } from '../../shared/api/client.js'
import { openSseStream } from '../../shared/sse/client.js'
import { useRateLimitStore } from '../../shared/ratelimit/store.js'
import type { ChatMessage } from '../../shared/api/types.js'

interface SseStreamViewerProps {
  token: string
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
  model,
  messages,
  streamOptions,
}: SseStreamViewerProps): React.JSX.Element {
  const [text, setText] = useState('')
  const [phase, setPhase] = useState<'streaming' | 'done' | 'error'>('streaming')
  const [error, setError] = useState<string | null>(null)
  const [malformed, setMalformed] = useState(0)
  const [tokens, setTokens] = useState(0)
  const bufferRef = useRef('')
  const rafRef = useRef(0)
  const ctrlRef = useRef<AbortController | null>(null)
  const readerRef = useRef<ReadableStreamDefaultReader<Uint8Array> | null>(null)

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

  const { maxRetries, heartbeatMs } = streamOptions ?? {}

  useEffect(() => {
    const ctrl = new AbortController()
    ctrlRef.current = ctrl

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
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: { model, messages, stream: true },
      signal: ctrl.signal,
      readerSlot: readerRef,
      onHeaders: (headers) => {
        useRateLimitStore.getState().setSnapshot(parseRateLimit(headers))
      },
      ...(maxRetries === undefined ? {} : { maxRetries }),
      ...(heartbeatMs === undefined ? {} : { heartbeatMs }),
      onMessage: (data) => {
        bufferRef.current += extractPiece(data)
        setTokens((n) => n + 1)
        schedule()
      },
      onMalformed: (count) => {
        setMalformed(count)
      },
      onDone: () => {
        stop(() => {
          setPhase('done')
        })
      },
      onError: (e) => {
        stop(() => {
          setPhase('error')
          setError(e.message)
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
  }, [token, model, messages, maxRetries, heartbeatMs])

  return (
    <section
      aria-label="Stream output"
      className="rounded-lg border border-ink/10 p-4 dark:border-parchment/10"
    >
      <div className="mb-2 flex flex-wrap items-center gap-3">
        <p role="status" className="text-xs">
          Phase: {phase}
        </p>
        <p className="text-xs tnum">Frames: {tokens}</p>
        <p className="text-xs tnum">Malformed: {malformed}</p>
        <span className="flex-1" />
        {phase === 'streaming' ? (
          <button
            type="button"
            onClick={() => {
              stopStream()
            }}
            className="rounded-md bg-danger px-3 py-2 text-xs text-white"
          >
            Stop
          </button>
        ) : null}
      </div>
      {error === null ? null : (
        <p role="alert" className="mb-2 text-xs text-danger dark:text-danger-soft">
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
      </div>
    </section>
  )
}
