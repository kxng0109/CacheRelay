import { isSafeUrl } from '../utils/safe-url.js'

/**
 * Outbound link that renders only through the URL safety gate.
 *
 * @remarks Every dynamic `href` renders through {@link isSafeUrl}: unsafe
 * destinations (`javascript:`, `data:`, unparseable input) render as an
 * explanatory note instead of a clickable link, never as a raw anchor.
 *
 * @param props - Destination, styling, and link content.
 * @returns The anchor, or a withholding note for unsafe destinations.
 */
export function SafeOutboundLink({
  href,
  className,
  children,
}: {
  href: string
  className?: string
  children: React.ReactNode
}): React.JSX.Element {
  if (!isSafeUrl(href)) {
    return (
      <p role="note" className={className}>
        Link withheld: unsafe destination.
      </p>
    )
  }
  return (
    <a href={href} target="_blank" rel="noreferrer" className={className}>
      {children}
    </a>
  )
}
