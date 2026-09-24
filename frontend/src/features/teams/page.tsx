import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useShallow } from 'zustand/react/shallow'
import { ApiError, GatewayClient } from '../../shared/api/client.js'
import { useAuthStore } from '../../shared/auth/store.js'
import { EmptyTrio } from '../../shared/components/EmptyTrio.js'

/**
 * Org team inventory table: mounted only after an org slug applies, so no
 * disabled observer ever lingers.
 *
 * @param props - Applied org slug.
 * @returns Member-count table, trio, or diagnosis.
 */
function OrgInventory({ org }: { org: string }): React.JSX.Element {
  const inventory = useQuery({
    queryKey: ['org-teams', org],
    queryFn: ({ signal }) => new GatewayClient().orgTeams(org, { signal }),
    retry: false,
  })

  if (inventory.isPending) {
    return (
      <p role="status" className="text-sm">
        Loading org teams…
      </p>
    )
  }
  const inventoryError = inventory.error
  if (inventoryError instanceof Error) {
    if (inventoryError instanceof ApiError && inventoryError.status === 404) {
      return (
        <div className="flex min-h-48 flex-col items-center justify-center rounded-xl border border-dashed border-ink/20 bg-cream p-6 text-center dark:border-parchment/20 dark:bg-parchment/5">
          <p role="status" className="font-display text-xl font-medium tracking-tight">
            Unknown org — “{org}” matches 0 orgs
          </p>
          <p className="mx-auto mt-1 max-w-md text-[13px] text-ink-soft dark:text-parchment-soft">
            Check the slug spelling and try again.
          </p>
        </div>
      )
    }
    return (
      <p role="alert" className="text-sm text-danger dark:text-danger-soft">
        {inventoryError.message}
      </p>
    )
  }
  if (inventory.data === undefined || inventory.data.length === 0) {
    return (
      <p role="status" className="text-[13px] text-ink-soft dark:text-parchment-soft">
        “{org}” has no teams yet.
      </p>
    )
  }
  return (
    <table className="w-full text-left text-sm">
      <caption className="sr-only">Teams in {org}</caption>
      <thead>
        <tr className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
          <th scope="col" className="py-2 pr-3 font-medium">
            Team
          </th>
          <th scope="col" className="py-2 pr-3 font-medium">
            IdP group
          </th>
          <th scope="col" className="py-2 text-right font-medium">
            Members
          </th>
        </tr>
      </thead>
      <tbody>
        {inventory.data.map((t) => (
          <tr key={t.teamId} className="border-t border-ink/10 dark:border-parchment/10">
            <td className="py-2 pr-3 text-[13px]">{t.name}</td>
            <td className="max-w-44 truncate py-2 pr-3 font-mono text-[13px]" title={t.idpGroupId}>
              {t.idpGroupId}
            </td>
            <td className="py-2 text-right font-mono text-[13px] tnum">{t.activeMembers}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

/**
 * Teams: my memberships plus the admin org inventory picker.
 *
 * @remarks Proof-type: boundary (what scope refused to leak). Empty is
 * normal — new SSO users land in a least-privilege holding team. Member
 * lists only: team-lead "team usage" has no backend route yet (service
 * exists, route doesn't), so no usage numbers appear here until the route
 * ships.
 *
 * @returns The teams screen.
 */
export function TeamsPage(): React.JSX.Element {
  const isAdmin = useAuthStore(useShallow((s) => s.session?.admin === true))
  const [org, setOrg] = useState('')
  const [appliedOrg, setAppliedOrg] = useState<string | null>(null)
  const [orgError, setOrgError] = useState<string | null>(null)

  const mine = useQuery({
    queryKey: ['my-teams'],
    queryFn: ({ signal }) => new GatewayClient().myTeams({ signal }),
    retry: false,
  })

  /**
   * Applies the org slug to the inventory query (blank = missing param).
   */
  const applyOrg = (): void => {
    const slug = org.trim()
    if (slug === '') {
      setOrgError('Enter an org slug to list its teams.')
      return
    }
    setOrgError(null)
    setAppliedOrg(slug)
  }

  return (
    <div className="space-y-6">
      <div className="space-y-1">
        <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
          <span aria-hidden="true" className="mr-1 text-ember">
            ❯
          </span>
          teams
        </p>
        <h1 className="font-display text-3xl font-medium tracking-tight">Teams</h1>
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          Your team memberships and SSO scope.
        </p>
      </div>
      {mine.isPending ? (
        <p role="status" className="text-sm">
          Loading teams…
        </p>
      ) : mine.error instanceof Error ? (
        <div
          role="alert"
          className="space-y-1 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent"
        >
          <p className="text-sm text-danger dark:text-danger-soft">{mine.error.message}</p>
          <p className="text-[13px] text-ink-soft dark:text-parchment-soft">
            If access vanished suddenly across keys and sessions, contact your admin. Your IdP
            account may be disabled.
          </p>
        </div>
      ) : mine.data === undefined || mine.data.length === 0 ? (
        <EmptyTrio
          title="No team yet"
          cue="New SSO accounts start in a least privilege holding team. Contact your admin to be added to a team."
          action={{ label: 'Redeem an invite', to: '/redeem' }}
        />
      ) : (
        <table className="w-full text-left text-sm">
          <caption className="sr-only">My team memberships</caption>
          <thead>
            <tr className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
              <th scope="col" className="py-2 pr-3 font-medium">
                Team
              </th>
              <th scope="col" className="py-2 pr-3 font-medium">
                Org
              </th>
              <th scope="col" className="py-2 pr-3 font-medium">
                Role
              </th>
              <th scope="col" className="py-2 font-medium">
                Status
              </th>
            </tr>
          </thead>
          <tbody>
            {mine.data.map((m) => (
              <tr key={m.teamId} className="border-t border-ink/10 dark:border-parchment/10">
                <td className="py-2 pr-3 text-[13px]">{m.teamName}</td>
                <td className="py-2 pr-3 font-mono text-[13px] tnum">{m.orgSlug}</td>
                <td className="py-2 pr-3 text-[13px]">
                  <span className="rounded-full border border-ink/15 px-2 py-0.5 font-mono text-xs dark:border-parchment/15">
                    {m.role}
                  </span>
                </td>
                <td className="py-2 text-[13px]">
                  <span className="rounded-full border border-ink/15 px-2 py-0.5 font-mono text-xs dark:border-parchment/15">
                    {m.status}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {isAdmin ? (
        <section aria-label="Org team inventory" className="space-y-3">
          <h2 className="font-display text-xl font-medium tracking-tight">Org inventory</h2>
          <form
            className="flex flex-wrap items-end gap-2"
            onSubmit={(e) => {
              e.preventDefault()
              applyOrg()
            }}
          >
            <div>
              <label htmlFor="teams-org" className="mb-1 block text-[13px] font-medium">
                Org slug
              </label>
              <input
                id="teams-org"
                type="text"
                value={org}
                onChange={(e) => {
                  setOrg(e.target.value)
                }}
                placeholder="acme"
                autoComplete="off"
                className="w-48 rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-[13px] tnum dark:border-parchment/15"
              />
            </div>
            <button
              type="submit"
              className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
            >
              List teams
            </button>
          </form>
          {orgError === null ? null : (
            <p role="alert" className="text-sm text-danger dark:text-danger-soft">
              {orgError}
            </p>
          )}
          {appliedOrg === null ? null : <OrgInventory org={appliedOrg} />}
        </section>
      ) : null}
    </div>
  )
}
