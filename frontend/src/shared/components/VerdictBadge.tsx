import { splitVerdict } from '../api/client.js'

/**
 * Non-blocking screening badge for AUDIT_ONLY vendor verdicts. Renders
 * nothing when the verdict is absent (clean or disabled) or unsplittable.
 * Neutral chrome by design: a screened-but-delivered run is information,
 * not an error state, so it never takes status colors.
 *
 * @param props - Opaque `<vendor>:<reason>` verdict token.
 * @returns The badge, or nothing when there is nothing to show.
 */
export function VerdictBadge({ verdict }: { verdict: string | null }): React.JSX.Element | null {
  if (verdict === null) return null
  const split = splitVerdict(verdict)
  if (split === null) return null
  return (
    <span
      title={`Screened by ${split.vendor} (${split.reason})`}
      className="inline-flex items-center gap-1 rounded-full border border-ink/15 px-2 py-0.5 font-mono text-xs dark:border-parchment/15"
    >
      <span aria-hidden="true">⧉</span>Screened · {split.vendor}
    </span>
  )
}
