import { resolveSsoProviders, ssoAuthorizationUrl } from '../../shared/api/client.js'

/**
 * SSO entry buttons driven by public client config.
 *
 * @remarks Providers come from the non-secret `VITE_SSO_PROVIDERS`
 * allow-list (Spring registration ids). SSO cannot run when nothing is
 * configured: a single muted line says so, never a dead button.
 *
 * @returns Provider links, or the muted not-enabled line.
 */
export function SsoButtons(): React.JSX.Element {
  const providers = resolveSsoProviders()
  if (providers.length === 0) {
    return (
      <p className="text-[13px] text-ink-soft dark:text-parchment-soft">
        Single sign-on is not enabled on this gateway.
      </p>
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
