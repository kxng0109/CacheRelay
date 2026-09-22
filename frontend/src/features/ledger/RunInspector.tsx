import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { GatewayClient } from '../../shared/api/client.js'
import type { LedgerLogEntry, LedgerReceipt } from '../../shared/api/types.js'
import { InspectorShell } from '../../shared/components/InspectorShell.js'

/**
 * Formats an ISO instant as a short local date-time.
 *
 * @param iso - ISO-8601 instant from the receipt.
 * @returns Short human form, falling back to the raw value when unparseable.
 */
function shortDate(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

/**
 * Ledger run inspector: stat cards, request facts, receipt copies, raw
 * JSON, and prev/next walk across the visible page rows.
 *
 * @remarks Proof-type: recorded. List rows render instantly from the page
 * payload; the twelve-field receipt hydrates on demand and an empty-body
 * `404` reads as a gone receipt. No payload bodies exist on receipts, so
 * the panel states that instead of inventing a transcript.
 *
 * @param props - Visible page rows, selected id, selection and close handlers.
 * @returns The ledger inspector dock.
 */
export function RunInspector({
  rows,
  selected,
  onSelect,
  onClose,
}: {
  rows: LedgerLogEntry[]
  selected: string
  onSelect: (requestId: string | null) => void
  onClose: () => void
}): React.JSX.Element {
  const [copied, setCopied] = useState(false)
  const [markdownCopied, setMarkdownCopied] = useState(false)
  const [copyError, setCopyError] = useState<string | null>(null)

  const found = rows.find((e) => e.requestId === selected)
  const row = found ?? null

  const receipt = useQuery({
    queryKey: ['ledger-receipt', selected],
    queryFn: ({ signal }) => new GatewayClient().ledgerReceipt(selected, { signal }),
    retry: false,
    enabled: row !== null,
  })

  const step = (delta: -1 | 1): void => {
    const current = rows.findIndex((e) => e.requestId === selected)
    const next = current < 0 ? 0 : (current + delta + rows.length) % rows.length
    const target = rows[next]
    if (target !== undefined) onSelect(target.requestId)
  }

  /**
   * Writes receipt text to the clipboard. Absence and rejection surface
   * inline, never throw.
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

  if (row === null) {
    return (
      <InspectorShell title={selected} onClose={onClose}>
        <p className="text-[13px] text-ink-soft dark:text-parchment-soft">
          This receipt left the visible page. Step through the rows to return to it.
        </p>
      </InspectorShell>
    )
  }

  const detail: LedgerReceipt | null = receipt.data ?? null
  const tokens =
    detail === null ? null : (detail.promptTokens ?? 0) + (detail.completionTokens ?? 0)

  return (
    <InspectorShell title={row.requestId} onClose={onClose}>
      <div className="flex flex-wrap items-center gap-2">
        <span className="rounded-full border border-ink/15 px-2 py-0.5 font-mono text-xs dark:border-parchment/15">
          {row.model}
        </span>
        <span className="flex-1" />
        <button
          type="button"
          aria-label="Previous receipt"
          onClick={() => {
            step(-1)
          }}
          className="rounded-md border border-ink/15 px-2 py-1 font-mono text-xs dark:border-parchment/15"
        >
          ←
        </button>
        <button
          type="button"
          aria-label="Next receipt"
          onClick={() => {
            step(1)
          }}
          className="rounded-md border border-ink/15 px-2 py-1 font-mono text-xs dark:border-parchment/15"
        >
          →
        </button>
      </div>
      <dl className="grid grid-cols-3 gap-2">
        <div className="rounded-lg border border-ink/10 p-2 dark:border-parchment/10">
          <dt className="font-mono text-xs text-ink-soft dark:text-parchment-soft">Cost</dt>
          <dd className="font-mono text-sm tnum">
            {row.costUsdMicros === 0 ? 'free' : `${String(row.costUsdMicros)}µ$`}
          </dd>
        </div>
        <div className="rounded-lg border border-ink/10 p-2 dark:border-parchment/10">
          <dt className="font-mono text-xs text-ink-soft dark:text-parchment-soft">Tokens</dt>
          <dd className="font-mono text-sm tnum">{tokens ?? '…'}</dd>
        </div>
        <div className="rounded-lg border border-ink/10 p-2 dark:border-parchment/10">
          <dt className="font-mono text-xs text-ink-soft dark:text-parchment-soft">Duration</dt>
          <dd className="font-mono text-sm tnum">
            {detail === null ? '…' : `${String(detail.durationMs)}ms`}
          </dd>
        </div>
      </dl>
      {receipt.isPending ? (
        <p role="status" className="text-[13px]">
          Loading receipt…
        </p>
      ) : receipt.error instanceof Error ? (
        <p role="alert" className="text-[13px] text-danger dark:text-danger-soft">
          Receipt unavailable: {receipt.error.message}
        </p>
      ) : detail === null ? null : (
        <dl className="space-y-2 text-[13px]">
          <div className="flex justify-between gap-3">
            <dt className="text-ink-soft dark:text-parchment-soft">Owner</dt>
            <dd className="font-mono text-xs break-all">{detail.ownerId}</dd>
          </div>
          <div className="flex justify-between gap-3">
            <dt className="text-ink-soft dark:text-parchment-soft">Provider</dt>
            <dd>{detail.provider}</dd>
          </div>
          <div className="flex justify-between gap-3">
            <dt className="text-ink-soft dark:text-parchment-soft">Cached</dt>
            <dd className="tnum">
              {detail.cached
                ? `yes${detail.cacheTier === null ? '' : ` · ${detail.cacheTier}`}`
                : 'no'}
            </dd>
          </div>
          <div className="flex justify-between gap-3">
            <dt className="text-ink-soft dark:text-parchment-soft">Created</dt>
            <dd className="tnum" title={detail.createdAt}>
              {shortDate(detail.createdAt)}
            </dd>
          </div>
        </dl>
      )}
      <p className="text-[13px] text-ink-soft dark:text-parchment-soft">
        Payload bodies are never logged — receipts carry facts, not transcripts.
      </p>
      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          onClick={() => {
            writeCopy(
              `request ${row.requestId} · model ${row.model} · cost ${String(row.costUsdMicros)}µ$ · ${row.createdAt}`,
              setCopied,
            )
          }}
          className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
        >
          {copied ? 'Copied' : 'Copy receipt [c]'}
        </button>
        <button
          type="button"
          onClick={() => {
            writeCopy(
              `# Receipt ${row.requestId} · ${row.model}\n\n- Cost: ${String(row.costUsdMicros)} µ$\n- Created: ${row.createdAt}\n\n\`\`\`json\n${JSON.stringify(detail ?? row, null, 2)}\n\`\`\``,
              setMarkdownCopied,
            )
          }}
          className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
        >
          {markdownCopied ? 'Copied' : 'Copy markdown'}
        </button>
      </div>
      {copyError === null ? null : (
        <p role="alert" className="text-[13px] text-danger dark:text-danger-soft">
          {copyError}
        </p>
      )}
      {detail === null ? null : (
        <details>
          <summary className="cursor-pointer font-mono text-[13px]">Raw JSON</summary>
          <pre className="mt-2 max-h-64 overflow-auto rounded-md border border-ink/10 p-2 font-mono text-xs whitespace-pre-wrap dark:border-parchment/10">
            {JSON.stringify(detail, null, 2)}
          </pre>
        </details>
      )}
    </InspectorShell>
  )
}
