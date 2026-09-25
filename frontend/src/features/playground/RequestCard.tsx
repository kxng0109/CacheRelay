import { useState } from 'react'
import { resolveApiBase } from '../../shared/api/client.js'

/**
 * Quotes a string for POSIX shells: wraps it in single quotes with every
 * embedded quote escaped as `'\''`. Inside single quotes `$`, backticks,
 * newlines, and `$(...)` stay literal, so a hostile model id pasted into a
 * terminal cannot execute.
 *
 * @param value - Raw text to quote.
 * @returns Shell-quoted text.
 */
function shellQuote(value: string): string {
  return `'${value.replace(/'/g, "'\\''")}'`
}

/**
 * Builds the exact curl an operator can paste into a terminal.
 *
 * @remarks The key stays the `YOUR_KEY` placeholder: memory-only keys never
 * leave the page, not even into a copy buffer. The payload is built with
 * `JSON.stringify` (never interpolation) and shell-quoted; the URL follows
 * the configured API base with a same-origin fallback instead of a
 * hardcoded loopback.
 *
 * @param model - Model id placed into the payload.
 * @returns The curl snippet text.
 */
export function exampleRequest(model: string): string {
  const base = resolveApiBase()
  const origin = base.length > 0 ? base : window.location.origin
  const body = JSON.stringify({ model, messages: [{ role: 'user', content: 'Hello' }] })
  return `curl -s ${origin}/v1/chat/completions -H "Authorization: Bearer YOUR_KEY" -H "Content-Type: application/json" -d ${shellQuote(body)}`
}

/**
 * Request card: the run as a copyable terminal command.
 *
 * @remarks REPL left pane next to the run detail JSON: operators move
 * between console and terminal without retyping. Clipboard absence
 * surfaces inline, never throws.
 *
 * @param props - Model id for the snippet.
 * @returns The request card.
 */
export function RequestCard({ model }: { model: string }): React.JSX.Element {
  const [copied, setCopied] = useState(false)
  const [bytes, setBytes] = useState(0)
  const [error, setError] = useState<string | null>(null)

  /**
   * Copies the snippet. Byte count confirms what left the page.
   */
  const copySnippet = (): void => {
    setError(null)
    const clip = navigator.clipboard as Clipboard | undefined
    if (clip === undefined) {
      setError('Copy unavailable in this browser.')
      return
    }
    const text = exampleRequest(model)
    void clip.writeText(text).then(
      () => {
        setCopied(true)
        setBytes(new TextEncoder().encode(text).length)
      },
      () => {
        setError('Copy failed. Select the text manually.')
      },
    )
  }

  return (
    <section
      aria-label="Run request"
      className="space-y-2 rounded-xl border border-ink/10 bg-cream p-4 sm:p-5 dark:border-parchment/10 dark:bg-transparent"
    >
      <h2 className="font-mono text-[13px] text-ink-soft dark:text-parchment-soft">
        <span aria-hidden="true" className="mr-1 text-ember">
          ❯
        </span>
        request
      </h2>
      <pre className="overflow-auto rounded-md border border-ink/10 p-2 font-mono text-xs whitespace-pre-wrap dark:border-parchment/10">
        {exampleRequest(model)}
      </pre>
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={copySnippet}
          className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
        >
          {copied ? `Copied ${String(bytes)}B` : 'Copy'}
        </button>
        {error === null ? null : (
          <p role="alert" className="text-[13px] text-danger dark:text-danger-soft">
            {error}
          </p>
        )}
      </div>
    </section>
  )
}
