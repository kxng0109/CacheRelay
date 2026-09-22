import { useQuery } from '@tanstack/react-query'
import { GatewayClient } from '../../shared/api/client.js'

/**
 * Badge classes per provider validation depth.
 *
 * @remarks Handoff mapping: grey for contract-only, blue for reachable,
 * green for live-verified, red for unverified. Unknown strings degrade to
 * the grey unknown badge instead of being mislabeled.
 *
 * @param status - Raw validation status from the gateway.
 * @returns Badge classes plus the icon+text label.
 */
export function validationBadge(status: string): { classes: string; label: string } {
  switch (status) {
    case 'CONTRACT_CHECKED':
      return {
        classes: 'bg-ink/8 text-ink-soft dark:bg-parchment/10 dark:text-parchment-soft',
        label: '● Contract',
      }
    case 'AUTH_REACHABLE':
      return { classes: 'bg-info/15 text-info dark:text-info-soft', label: '● Reachable' }
    case 'LIVE_VERIFIED':
      return { classes: 'bg-success/15 text-success dark:text-success-soft', label: '● Live' }
    case 'UNVERIFIED':
      return { classes: 'bg-danger/15 text-danger dark:text-danger-soft', label: '■ Unverified' }
    default:
      return {
        classes: 'bg-ink/8 text-ink-soft dark:bg-parchment/10 dark:text-parchment-soft',
        label: '? Unknown',
      }
  }
}

/**
 * Upstream provider health: validation depth, key presence, live circuit
 * state, and alias references.
 *
 * @remarks Proof-type: live (real `/v1/admin/providers`, DTO-verified).
 * Key material never crosses — only the boolean. Unknown validation and
 * circuit strings degrade to grey instead of throwing.
 *
 * @returns The providers section.
 */
export function ProviderBoard(): React.JSX.Element {
  const providers = useQuery({
    queryKey: ['providers'],
    queryFn: ({ signal }) => new GatewayClient().listProviders({ signal }),
    retry: false,
  })

  return (
    <section aria-label="Providers" className="space-y-2">
      <h2 className="font-display text-xl font-medium tracking-tight">Providers</h2>
      {providers.isPending ? (
        <p role="status" className="text-sm">
          Loading providers…
        </p>
      ) : providers.error instanceof Error ? (
        <p role="alert" className="text-sm text-danger dark:text-danger-soft">
          {providers.error.message}
        </p>
      ) : providers.data === undefined || providers.data.providers.length === 0 ? (
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          No providers reported. Configure providers in the backend to populate this board.
        </p>
      ) : (
        <table className="w-full text-left text-sm">
          <caption className="sr-only">Upstream provider health</caption>
          <thead className="sticky top-0 bg-paper dark:bg-night">
            <tr className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
              <th scope="col" className="py-2 pr-3 font-medium">
                Provider
              </th>
              <th scope="col" className="py-2 pr-3 font-medium">
                Validation
              </th>
              <th scope="col" className="py-2 pr-3 font-medium">
                Key
              </th>
              <th scope="col" className="py-2 pr-3 font-medium">
                Circuit
              </th>
              <th scope="col" className="py-2 text-right font-medium">
                Aliases
              </th>
            </tr>
          </thead>
          <tbody>
            {providers.data.providers.map((p) => {
              const badge = validationBadge(p.validationStatus)
              return (
                <tr key={p.name} className="border-t border-ink/10 dark:border-parchment/10">
                  <td className="py-2 pr-3 font-mono text-[13px]" title={p.baseUrl ?? undefined}>
                    {p.name}
                  </td>
                  <td className="py-2 pr-3">
                    <span className={`rounded px-2 py-1 text-[13px] tnum ${badge.classes}`}>
                      {badge.label}
                    </span>
                  </td>
                  <td className="py-2 pr-3 text-[13px] text-ink-soft dark:text-parchment-soft">
                    {p.keyConfigured ? 'set' : '—'}
                  </td>
                  <td className="py-2 pr-3 font-mono text-[13px]">{p.circuitState}</td>
                  <td className="py-2 text-right text-[13px] tnum">{p.aliasReferences}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}
    </section>
  )
}
