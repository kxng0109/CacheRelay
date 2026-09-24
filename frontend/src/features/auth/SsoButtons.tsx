import { resolveSsoProviders, ssoAuthorizationUrl } from '../../shared/api/client.js'

/**
 * SSO entry buttons driven by public client config.
 *
 * @remarks Providers come from the non-secret `VITE_SSO_PROVIDERS`
 * allow-list (Spring registration ids). SSO cannot run when nothing is
 * configured: the control renders greyed-out and disabled with the
 * hover message, never a dead link.
 *
 * @returns Provider links, or the disabled not-enabled control.
 */
export function SsoButtons(): React.JSX.Element {
  const providers = resolveSsoProviders()
  if (providers.length === 0) {
    return (
      <div className="space-y-1">
        <button
          type="button"
          disabled
          title="SSO not enabled"
          className="w-full cursor-not-allowed rounded-md border border-ink/15 px-4 py-2 text-sm text-ink-soft dark:border-parchment/15 dark:text-parchment-soft"
        >
          Continue with SSO
        </button>
        <p className="text-center font-mono text-xs text-ink-soft dark:text-parchment-soft">
          SSO not enabled
        </p>
      </div>
    )
  }
  return (
    <div className="space-y-2">
      {providers.map((p) => (
        <a
          key={p}
          href={ssoAuthorizationUrl(p)}
          className="block w-full rounded-md border border-ink/15 px-4 py-2 text-center text-sm dark:border-parchment/15"
        >
          Continue with {p}
        </a>
      ))}
    </div>
  )
}
