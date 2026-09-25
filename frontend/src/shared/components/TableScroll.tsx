/**
 * Horizontal scroll container for data tables.
 *
 * @remarks At 320 px widths (or 400 % zoom) a full-width table would force
 * the whole page into two-dimensional scrolling (WCAG 2.2 SC 1.4.10).
 * The wrapper confines overflow to the table region; the page itself
 * keeps scrolling in one dimension. Tables keep their own captions, so
 * the wrapper carries no role.
 *
 * @param props - Table content.
 * @returns The scroll container.
 */
export function TableScroll({ children }: { children: React.ReactNode }): React.JSX.Element {
  return <div className="overflow-x-auto">{children}</div>
}
