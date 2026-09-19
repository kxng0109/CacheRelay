import { Link } from 'react-router'

/**
 * Identical missing page for unknown routes and hidden admin screens.
 *
 * @remarks Stealth contract: non-admins must not distinguish "no such
 * route" from "route exists but forbidden". Every guard renders this
 * exact screen, byte-identical to the catch-all, and names no resource.
 *
 * @returns The missing-page screen.
 */
export function NotFound(): React.JSX.Element {
  return (
    <div className="space-y-4">
      <h1 className="font-display text-2xl font-medium tracking-tight">Page not found</h1>
      <p className="text-sm text-ink-soft dark:text-parchment-soft">This page does not exist.</p>
      <p className="text-sm">
        <Link to="/" className="underline underline-offset-4">
          Back to overview
        </Link>
      </p>
    </div>
  )
}
