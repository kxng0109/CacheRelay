import { useState } from 'react'

/**
 * Facts known about one completed or running completion. Every field is
 * optional except identity: the panel renders an em dash for anything
 * unknown rather than a zero that would read as a measurement.
 */
export interface RunDetail {
  /** Monotonic run number, matches the output block heading. */
  runId: number
  /** Model id the run was sent to. */
  model: string
  /** True while the stream is still open. */
  streaming: boolean
  /** Terminal state, or running while the stream is open. */
  status: 'running' | 'done' | 'error' | 'stopped'
  /** Wall clock milliseconds for static calls, or stream duration. */
  latencyMs?: number
  /** SSE frames received (streaming runs). */
  frames?: number
  /** Cache tier from `X-Cache`, or null for provider backed live runs. */
  cacheTier?: string | null
  /** Semantic similarity score, cache hits only. */
  similarity?: string | null
  /** Entry age in seconds, cache hits only. */
  age?: string | null
  /** Failure message, error runs only. */
  error?: string | null
}

/**
 * Run detail panel: stat tiles, key values, raw JSON, and an honesty
 * notice. Translated from the request detail pattern every major gateway
 * console converges on, rebuilt in terminal voice.
 *
 * @param props - The run facts to present.
 * @returns The detail card for one run.
 */
export function RunDetailPanel({ detail }: { detail: RunDetail }): React.JSX.Element {
  const [copied, setCopied] = useState(false)
  const [markdownCopied, setMarkdownCopied] = useState(false)
  const [copyError, setCopyError] = useState<string | null>(null)

  const latency = detail.latencyMs?.toFixed(0) ?? 'n/a'
  const latencyLabel = detail.latencyMs === undefined ? latency : `${latency} ms`
  const frames = String(detail.frames ?? 'n/a')
  const cache = detail.cacheTier ?? 'live'

  const payload = JSON.stringify(
    {
      run: detail.runId,
      model: detail.model,
      streaming: detail.streaming,
      status: detail.status,
      ...(detail.latencyMs === undefined ? {} : { latencyMs: detail.latencyMs }),
      ...(detail.frames === undefined ? {} : { frames: detail.frames }),
      ...(detail.cacheTier === undefined || detail.cacheTier === null
        ? {}
        : { cache: detail.cacheTier }),
      ...(detail.similarity === null || detail.similarity === undefined
        ? {}
        : { similarity: detail.similarity }),
      ...(detail.age === null || detail.age === undefined ? {} : { ageSeconds: detail.age }),
      ...(detail.error === null || detail.error === undefined ? {} : { error: detail.error }),
    },
    null,
    2,
  )

  /**
   * Copies the detail payload. Byte count confirms what left the page.
   */
  const copyPayload = (): void => {
    writeCopy(payload, setCopied)
  }

  /**
   * Copies the receipt as markdown: heading plus fenced payload, ready to
   * paste into a ticket, report, or model prompt. Facts only — the panel
   * holds no keys, tokens, or secrets.
   */
  const copyMarkdown = (): void => {
    const doc = `# Run ${String(detail.runId)} · ${detail.model} (${detail.status})\n\n\`\`\`json\n${payload}\n\`\`\``
    writeCopy(doc, setMarkdownCopied)
  }

  /**
   * Writes text to the clipboard. Absence surfaces inline, never throws.
   *
   * @param text - Text to copy.
   * @param mark - State setter flipped on success.
   */
  const writeCopy = (text: string, mark: (v: boolean) => void): void => {
    setCopyError(null)
    const clip = navigator.clipboard as Clipboard | undefined
    if (clip === undefined) {
      setCopyError('Copy unavailable in this browser.')
      return
    }
    void clip.writeText(text).then(
      () => {
        mark(true)
      },
      () => {
        setCopyError('Copy failed. Select the text manually.')
      },
    )
  }

  return (
    <section
      aria-label={`Run ${String(detail.runId)} details`}
      className="rounded-xl border border-ink/10 bg-cream p-4 sm:p-5 dark:border-parchment/10 dark:bg-transparent"
    >
      <dl className="grid gap-3 sm:grid-cols-4">
        <div className="min-h-19 rounded-lg border border-ink/10 p-3 dark:border-parchment/10">
          <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">Latency</dt>
          <dd className="font-mono text-lg tnum">{latencyLabel}</dd>
        </div>
        <div className="min-h-19 rounded-lg border border-ink/10 p-3 dark:border-parchment/10">
          <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">Frames</dt>
          <dd className="font-mono text-lg tnum">{frames}</dd>
        </div>
        <div className="min-h-19 rounded-lg border border-ink/10 p-3 dark:border-parchment/10">
          <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">Cache</dt>
          <dd className="max-w-full truncate font-mono text-lg" title={cache}>
            {cache}
          </dd>
        </div>
        <div className="min-h-19 rounded-lg border border-ink/10 p-3 dark:border-parchment/10">
          <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">Status</dt>
          <dd className="font-mono text-lg">{detail.status}</dd>
        </div>
      </dl>
      <dl className="mt-3 space-y-1 font-mono text-xs">
        <div className="flex justify-between gap-3">
          <dt className="text-ink-soft dark:text-parchment-soft">model</dt>
          <dd className="truncate">{detail.model}</dd>
        </div>
        <div className="flex justify-between gap-3">
          <dt className="text-ink-soft dark:text-parchment-soft">streaming</dt>
          <dd>{detail.streaming ? 'true' : 'false'}</dd>
        </div>
        {detail.similarity === null || detail.similarity === undefined ? null : (
          <div className="flex justify-between gap-3">
            <dt className="text-ink-soft dark:text-parchment-soft">similarity</dt>
            <dd className="tnum">{detail.similarity}</dd>
          </div>
        )}
        {detail.age === null || detail.age === undefined ? null : (
          <div className="flex justify-between gap-3">
            <dt className="text-ink-soft dark:text-parchment-soft">age</dt>
            <dd className="tnum">{detail.age}s</dd>
          </div>
        )}
      </dl>
      <details className="mt-3">
        <summary className="cursor-pointer font-mono text-[13px]">Raw JSON</summary>
        <pre className="mt-2 overflow-auto rounded-md border border-ink/10 p-2 font-mono text-xs whitespace-pre-wrap dark:border-parchment/10">
          {payload}
        </pre>
        <div className="mt-2 flex items-center gap-3">
          <button
            type="button"
            onClick={copyPayload}
            className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
          >
            {copied ? 'Copied' : 'Copy'}
          </button>
          <button
            type="button"
            onClick={copyMarkdown}
            className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
          >
            {markdownCopied ? 'Copied' : 'Copy markdown'}
          </button>
          {copyError === null ? null : (
            <p role="alert" className="text-[13px] text-danger dark:text-danger-soft">
              {copyError}
            </p>
          )}
        </div>
      </details>
      <p className="mt-3 text-[13px] text-ink-soft dark:text-parchment-soft">
        Per run cost is not reported by the completion endpoints. Totals live under Overview.
      </p>
    </section>
  )
}
