import { useRouteError } from 'react-router'

/**
 * Route-level failure screen: a render throw anywhere under the shell
 * lands here instead of unmounting the console into a blank page.
 *
 * @remarks Only the error message renders (never objects or stacks), with
 * a reload affordance. Route errors are discrete failures, so the message
 * uses an alert role.
 *
 * @returns The failure screen with recovery.
 */
export function RouteError(): React.JSX.Element {
  const error = useRouteError()
  const message = error instanceof Error ? error.message : 'Something broke on this screen.'
  return (
    <div className="space-y-3 py-10 text-center">
      <h1 className="font-display text-2xl font-medium tracking-tight">Something broke here</h1>
      <p role="alert" className="text-[13px] text-danger dark:text-danger-soft">
        {message}
      </p>
      <button
        type="button"
        onClick={() => {
          window.location.reload()
        }}
        className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
      >
        Reload screen
      </button>
    </div>
  )
}
