import { Link } from 'react-router'

/**
 * Playful centerpiece: the relay node with its middle tier bar adrift.
 *
 * @remarks The same geometry as the product mark, mid-accident. The
 * illustration is decorative; the page's meaning is carried by the
 * heading and copy, so the graphic names itself only for screen readers.
 *
 * @returns The lost-relay illustration.
 */
function LostRelay(): React.JSX.Element {
  return (
    <svg
      viewBox="0 0 220 140"
      role="img"
      aria-label="The middle tier bar has drifted away from the relay node"
      className="w-56"
    >
      {/* Node, tilted a touch, holding its top and bottom bars. */}
      <g transform="rotate(-3 78 72)">
        <rect
          x="30"
          y="24"
          width="96"
          height="96"
          rx="22"
          fill="none"
          strokeWidth="6"
          className="stroke-ember"
        />
        <rect x="48" y="44" width="42" height="9" rx="4.5" className="fill-ember" />
        <rect x="48" y="88" width="42" height="9" rx="4.5" className="fill-ember" />
      </g>
      {/* Where the middle bar used to sit, a dotted trail out the side. */}
      <path
        d="M 75 70 C 112 72, 128 62, 156 58"
        fill="none"
        strokeWidth="3"
        strokeLinecap="round"
        strokeDasharray="2 8"
        className="stroke-ink-soft dark:stroke-parchment-soft"
      />
      {/* The escapee: same narrow offset bar as the mark, nose up. */}
      <rect
        x="158"
        y="52"
        width="30"
        height="9"
        rx="4.5"
        transform="rotate(18 173 56.5)"
        className="fill-ember"
      />
    </svg>
  )
}

/**
 * Identical missing page for unknown routes and hidden admin screens.
 *
 * @remarks Stealth contract: non-admins must not distinguish "no such
 * route" from "route exists but forbidden". Every guard renders this
 * exact screen, byte-identical to the catch-all, and names no resource.
 * Guests never reach it from a guarded route — they bounce to login
 * first — so both actions below stay safe for every audience.
 *
 * @returns The missing-page screen.
 */
export function NotFound(): React.JSX.Element {
  return (
    <div className="mx-auto flex min-h-[65vh] max-w-md flex-col items-center justify-center gap-5 py-10 text-center">
      <p className="font-mono text-lg font-medium tracking-[0.25em] text-ember">
        <span aria-hidden="true" className="mr-2">
          ❯
        </span>
        404
      </p>
      <LostRelay />
      <h1 className="font-display text-4xl font-medium tracking-tight">Page not found</h1>
      <p className="text-base text-ink-soft dark:text-parchment-soft">
        That address does not lead anywhere in this console.
      </p>
      <div className="flex flex-wrap items-center justify-center gap-2">
        <Link
          to="/"
          className="rounded-md bg-ink px-5 py-2.5 text-sm font-medium text-paper dark:bg-parchment dark:text-night"
        >
          Back to overview
        </Link>
        <Link
          to="/playground"
          className="rounded-md border border-ink/15 px-5 py-2.5 text-sm dark:border-parchment/15"
        >
          Open playground
        </Link>
      </div>
      <p className="font-mono text-[13px] text-ink-soft dark:text-parchment-soft">
        Lost? Press Ctrl+K to jump anywhere.
      </p>
    </div>
  )
}
