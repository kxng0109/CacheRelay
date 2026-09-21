/**
 * CacheRelay mark: a relay node holding three tier bars, the middle one
 * offset like a baton pass. Enclosure keeps it from reading as a menu at
 * small sizes; the offset bar carries the relay idea without literal
 * arrows or bolts.
 *
 * @remarks Single color via `currentColor` so it follows both themes and
 * any surrounding text. Strokes stay on whole pixels at 16px.
 *
 * @param props - Pixel size and accessible label.
 * @returns The mark as an inline SVG.
 */
export function CacheRelayMark({
  size = 16,
  label = 'CacheRelay',
}: {
  size?: number
  label?: string
}): React.JSX.Element {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" fill="none" role="img" aria-label={label}>
      <rect x="3.5" y="3.5" width="25" height="25" rx="7" stroke="currentColor" strokeWidth="2.5" />
      <rect x="9" y="10.5" width="14" height="3" rx="1.5" fill="currentColor" />
      <rect x="13" y="14.75" width="10" height="3" rx="1.5" fill="currentColor" />
      <rect x="9" y="19" width="14" height="3" rx="1.5" fill="currentColor" />
    </svg>
  )
}
