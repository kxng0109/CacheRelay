import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useShallow } from 'zustand/react/shallow'
import { ApiError, GatewayClient } from '../../shared/api/client.js'
import { toErrorMessage } from '../../shared/api/client.js'
import type { OrgTeam } from '../../shared/api/types.js'
import { useAuthStore } from '../../shared/auth/store.js'
import { EmptyTrio } from '../../shared/components/EmptyTrio.js'
import { Select } from '../../shared/components/Select.js'
import { TableScroll } from '../../shared/components/TableScroll.js'

/**
 * Account UUID shape shared with the gateway client: trimmed before
 * testing so surrounding whitespace never causes a round trip.
 */
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/**
 * Local teams read `local:<slug>` group ids; anything else is
 * sync-owned and rejects every write with 409.
 *
 * @param team - Team row from the inventory.
 * @returns True for locally managed teams.
 */
function isLocalTeam(team: OrgTeam): boolean {
  return team.idpGroupId.startsWith('local:')
}

/**
 * Per-team membership and lifecycle actions inside the org inventory.
 * Assign and revoke are idempotent — safe to retry after a failure.
 *
 * @param props - Team row plus owning org slug for cache invalidation.
 * @returns Action controls, or the sync-owned note for mapped teams.
 */
function TeamRowActions({ team, org }: { team: OrgTeam; org: string }): React.JSX.Element {
  const qc = useQueryClient()
  const [renaming, setRenaming] = useState(false)
  const [renameValue, setRenameValue] = useState('')
  const [memberId, setMemberId] = useState('')
  const [role, setRole] = useState('MEMBER')
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  if (!isLocalTeam(team)) {
    return (
      <span className="inline-flex items-center gap-1 rounded-full border border-ink/15 px-2 py-0.5 font-mono text-xs dark:border-parchment/15">
        <span aria-hidden="true">⧉</span>Sync-owned
      </span>
    )
  }

  const unassigned = team.name.toLowerCase() === 'unassigned'

  const onRename = async (): Promise<void> => {
    setError(null)
    setNotice(null)
    if (renameValue.trim().length === 0) {
      setError('Team display name is required.')
      return
    }
    setBusy(true)
    try {
      await new GatewayClient().renameTeam(team.teamId, { displayName: renameValue.trim() })
      setNotice('Team renamed.')
      setRenaming(false)
      setRenameValue('')
      await qc.invalidateQueries({ queryKey: ['org-teams', org] })
    } catch (e) {
      setError(toErrorMessage(e, 'Team rename failed. Sync-owned teams reject writes (409).'))
    } finally {
      setBusy(false)
    }
  }

  const onDelete = async (): Promise<void> => {
    setError(null)
    setNotice(null)
    setBusy(true)
    try {
      await new GatewayClient().deleteTeam(team.teamId)
      setNotice('Team deleted.')
      await qc.invalidateQueries({ queryKey: ['org-teams', org] })
    } catch (e) {
      setError(
        toErrorMessage(
          e,
          'Team deletion failed. Clear members and pending invites first; sync-owned and Unassigned teams can’t be deleted.',
        ),
      )
    } finally {
      setBusy(false)
    }
  }

  const onAssign = async (): Promise<void> => {
    setError(null)
    setNotice(null)
    if (!UUID_RE.test(memberId.trim())) {
      setError('Member account must be a valid UUID.')
      return
    }
    setBusy(true)
    try {
      const out = await new GatewayClient().assignMember(team.teamId, memberId.trim(), { role })
      setNotice(`Member assigned — ${out.status}.`)
      await qc.invalidateQueries({ queryKey: ['org-teams', org] })
    } catch (e) {
      setError(toErrorMessage(e, 'Member assignment failed.'))
    } finally {
      setBusy(false)
    }
  }

  const onRevoke = async (): Promise<void> => {
    setError(null)
    setNotice(null)
    if (!UUID_RE.test(memberId.trim())) {
      setError('Member account must be a valid UUID.')
      return
    }
    setBusy(true)
    try {
      const out = await new GatewayClient().revokeMember(team.teamId, memberId.trim())
      setNotice(`Member revoked — ${out.status}.`)
      await qc.invalidateQueries({ queryKey: ['org-teams', org] })
    } catch (e) {
      setError(toErrorMessage(e, 'Member revocation failed.'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-2">
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          disabled={busy}
          aria-label={`Rename team ${team.name}`}
          onClick={() => {
            setError(null)
            setRenaming((v) => !v)
          }}
          className="rounded-md border border-ink/15 px-2 py-1 text-xs disabled:cursor-not-allowed dark:border-parchment/15"
        >
          Rename
        </button>
        <button
          type="button"
          disabled={busy || unassigned}
          aria-label={`Delete team ${team.name}`}
          title={unassigned ? 'The per-org Unassigned team can’t be deleted.' : 'Delete this team'}
          onClick={() => {
            void onDelete()
          }}
          className="rounded-md border border-ink/15 px-2 py-1 text-xs disabled:cursor-not-allowed disabled:opacity-50 dark:border-parchment/15"
        >
          Delete
        </button>
      </div>
      {renaming ? (
        <div className="flex flex-wrap items-end gap-2">
          <div>
            <label
              htmlFor={`team-rename-${team.teamId}`}
              className="mb-1 block text-xs font-medium"
            >
              New display name for team {team.name}
            </label>
            <input
              id={`team-rename-${team.teamId}`}
              type="text"
              value={renameValue}
              onChange={(e) => {
                setRenameValue(e.target.value)
              }}
              autoComplete="off"
              className="w-40 rounded-md border border-ink/15 bg-transparent px-2 py-1 text-[13px] dark:border-parchment/15"
            />
          </div>
          <button
            type="button"
            disabled={busy}
            aria-label={`Save team name ${team.name}`}
            onClick={() => {
              void onRename()
            }}
            className="rounded-md border border-ink/15 px-2 py-1 text-xs disabled:cursor-not-allowed dark:border-parchment/15"
          >
            Save
          </button>
        </div>
      ) : null}
      <div className="flex flex-wrap items-end gap-2">
        <div>
          <label htmlFor={`member-id-${team.teamId}`} className="mb-1 block text-xs font-medium">
            Member account UUID for {team.name}
          </label>
          <input
            id={`member-id-${team.teamId}`}
            type="text"
            value={memberId}
            onChange={(e) => {
              setMemberId(e.target.value)
            }}
            placeholder="123e4567-e89b-12d3-a456-426614174000"
            autoComplete="off"
            spellCheck={false}
            className="w-64 rounded-md border border-ink/15 bg-transparent px-2 py-1 font-mono text-xs dark:border-parchment/15"
          />
        </div>
        <Select
          id={`member-role-${team.teamId}`}
          label={`Member role for ${team.name}`}
          value={role}
          options={[
            { value: 'MEMBER', label: 'MEMBER' },
            { value: 'LEAD', label: 'LEAD' },
          ]}
          onChange={setRole}
        />
        <button
          type="button"
          disabled={busy}
          aria-label={`Assign member to ${team.name}`}
          onClick={() => {
            void onAssign()
          }}
          className="rounded-md border border-ink/15 px-2 py-1 text-xs disabled:cursor-not-allowed dark:border-parchment/15"
        >
          Assign
        </button>
        <button
          type="button"
          disabled={busy}
          aria-label={`Revoke member from ${team.name}`}
          onClick={() => {
            void onRevoke()
          }}
          className="rounded-md border border-ink/15 px-2 py-1 text-xs disabled:cursor-not-allowed dark:border-parchment/15"
        >
          Revoke
        </button>
      </div>
      {notice === null ? null : (
        <p role="status" className="text-xs">
          {notice}
        </p>
      )}
      {error === null ? null : (
        <p role="alert" className="text-xs text-danger dark:text-danger-soft">
          {error}
        </p>
      )}
    </div>
  )
}

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
    <TableScroll>
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
            <th scope="col" className="py-2 pr-3 text-right font-medium">
              Members
            </th>
            <th scope="col" className="py-2 font-medium">
              Actions
            </th>
          </tr>
        </thead>
        <tbody>
          {inventory.data.map((t) => (
            <tr key={t.teamId} className="border-t border-ink/10 dark:border-parchment/10">
              <td className="py-2 pr-3 text-[13px]">{t.name}</td>
              <td
                className="max-w-44 truncate py-2 pr-3 font-mono text-[13px]"
                title={t.idpGroupId}
              >
                {t.idpGroupId}
              </td>
              <td className="py-2 pr-3 text-right font-mono text-[13px] tnum">{t.activeMembers}</td>
              <td className="py-2 text-[13px]">
                <TeamRowActions team={t} org={org} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </TableScroll>
  )
}

/**
 * Local org administration: list, create, rename, delete, plus per-org
 * team creation. Delete requires an empty org (409 otherwise); the slug
 * is immutable — renames carry the display name only.
 *
 * @returns The org management section.
 */
function OrgsManager(): React.JSX.Element {
  const qc = useQueryClient()
  const orgsQuery = useQuery({
    queryKey: ['orgs'],
    queryFn: ({ signal }) => new GatewayClient().listOrgs({ signal }),
    retry: false,
  })
  const [slug, setSlug] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [renaming, setRenaming] = useState<string | null>(null)
  const [renameValue, setRenameValue] = useState('')
  const [teamFor, setTeamFor] = useState<string | null>(null)
  const [teamName, setTeamName] = useState('')

  const onCreate = async (): Promise<void> => {
    setError(null)
    setNotice(null)
    setBusy(true)
    try {
      await new GatewayClient().createOrg({ slug, displayName })
      setNotice('Org created.')
      setSlug('')
      setDisplayName('')
      await qc.invalidateQueries({ queryKey: ['orgs'] })
    } catch (e) {
      setError(toErrorMessage(e, 'Org creation failed.'))
    } finally {
      setBusy(false)
    }
  }

  const onRename = async (id: string): Promise<void> => {
    setError(null)
    setNotice(null)
    if (renameValue.trim().length === 0) {
      setError('Org display name is required.')
      return
    }
    setBusy(true)
    try {
      await new GatewayClient().renameOrg(id, { displayName: renameValue.trim() })
      setNotice('Org renamed.')
      setRenaming(null)
      setRenameValue('')
      await qc.invalidateQueries({ queryKey: ['orgs'] })
    } catch (e) {
      setError(toErrorMessage(e, 'Org rename failed.'))
    } finally {
      setBusy(false)
    }
  }

  const onDelete = async (id: string): Promise<void> => {
    setError(null)
    setNotice(null)
    setBusy(true)
    try {
      await new GatewayClient().deleteOrg(id)
      setNotice('Org deleted.')
      await qc.invalidateQueries({ queryKey: ['orgs'] })
    } catch (e) {
      setError(
        toErrorMessage(e, 'Org deletion failed. The org must be empty — delete its teams first.'),
      )
    } finally {
      setBusy(false)
    }
  }

  const onCreateTeam = async (orgId: string): Promise<void> => {
    setError(null)
    setNotice(null)
    if (teamName.trim().length === 0) {
      setError('Team name is required.')
      return
    }
    setBusy(true)
    try {
      await new GatewayClient().createTeam(orgId, { name: teamName.trim() })
      setNotice('Team created.')
      setTeamFor(null)
      setTeamName('')
      await qc.invalidateQueries({ queryKey: ['orgs'] })
    } catch (e) {
      setError(toErrorMessage(e, 'Team creation failed.'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-label="Local org management" className="space-y-3">
      <h2 className="font-display text-xl font-medium tracking-tight">Orgs</h2>
      <p className="text-[13px] text-ink-soft dark:text-parchment-soft">
        Slugs normalize server-side (trim + lowercase, a-z0-9-, up to 64 chars) and never change
        after creation. Deleting an org requires it to be empty.
      </p>
      <div className="flex flex-wrap items-end gap-2">
        <div>
          <label htmlFor="org-slug" className="mb-1 block text-[13px] font-medium">
            New org slug
          </label>
          <input
            id="org-slug"
            type="text"
            value={slug}
            onChange={(e) => {
              setSlug(e.target.value)
            }}
            placeholder="globex"
            autoComplete="off"
            className="w-44 rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-[13px] tnum dark:border-parchment/15"
          />
        </div>
        <div>
          <label htmlFor="org-display-name" className="mb-1 block text-[13px] font-medium">
            New org display name
          </label>
          <input
            id="org-display-name"
            type="text"
            value={displayName}
            onChange={(e) => {
              setDisplayName(e.target.value)
            }}
            placeholder="Globex"
            autoComplete="off"
            className="w-44 rounded-md border border-ink/15 bg-transparent px-3 py-2 text-[13px] dark:border-parchment/15"
          />
        </div>
        <button
          type="button"
          disabled={busy}
          onClick={() => {
            void onCreate()
          }}
          className="rounded-md border border-ink/15 px-3 py-2 text-[13px] disabled:cursor-not-allowed dark:border-parchment/15"
        >
          Create org
        </button>
      </div>
      {notice === null ? null : (
        <p role="status" className="text-[13px]">
          {notice}
        </p>
      )}
      {error === null ? null : (
        <p role="alert" className="text-[13px] text-danger dark:text-danger-soft">
          {error}
        </p>
      )}
      {orgsQuery.isPending ? (
        <p role="status" className="text-sm">
          Loading orgs…
        </p>
      ) : orgsQuery.error instanceof Error ? (
        <p role="alert" className="text-sm text-danger dark:text-danger-soft">
          {orgsQuery.error.message}
        </p>
      ) : orgsQuery.data === undefined || orgsQuery.data.length === 0 ? (
        <p role="status" className="text-[13px] text-ink-soft dark:text-parchment-soft">
          No orgs yet — create the first one above.
        </p>
      ) : (
        <ul className="space-y-2">
          {orgsQuery.data.map((o) => (
            <li
              key={o.id}
              className="space-y-2 rounded-lg border border-ink/10 p-3 dark:border-parchment/10"
            >
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-mono text-[13px] tnum">{o.slug}</span>
                <span className="text-[13px]">{o.displayName}</span>
                <span
                  className="max-w-56 truncate font-mono text-xs text-ink-soft dark:text-parchment-soft"
                  title={o.id}
                >
                  {o.id}
                </span>
                <span className="flex-1" />
                <button
                  type="button"
                  disabled={busy}
                  aria-label={`Rename org ${o.slug}`}
                  onClick={() => {
                    setError(null)
                    setRenaming(renaming === o.id ? null : o.id)
                    setRenameValue('')
                  }}
                  className="rounded-md border border-ink/15 px-2 py-1 text-xs disabled:cursor-not-allowed dark:border-parchment/15"
                >
                  Rename
                </button>
                <button
                  type="button"
                  disabled={busy}
                  aria-label={`Delete org ${o.slug}`}
                  onClick={() => {
                    void onDelete(o.id)
                  }}
                  className="rounded-md border border-ink/15 px-2 py-1 text-xs disabled:cursor-not-allowed dark:border-parchment/15"
                >
                  Delete
                </button>
                <button
                  type="button"
                  disabled={busy}
                  aria-label={`New team in ${o.slug}`}
                  onClick={() => {
                    setError(null)
                    setTeamFor(teamFor === o.id ? null : o.id)
                    setTeamName('')
                  }}
                  className="rounded-md border border-ink/15 px-2 py-1 text-xs disabled:cursor-not-allowed dark:border-parchment/15"
                >
                  New team
                </button>
              </div>
              {renaming === o.id ? (
                <div className="flex flex-wrap items-end gap-2">
                  <div>
                    <label
                      htmlFor={`org-rename-${o.id}`}
                      className="mb-1 block text-xs font-medium"
                    >
                      New display name for {o.slug}
                    </label>
                    <input
                      id={`org-rename-${o.id}`}
                      type="text"
                      value={renameValue}
                      onChange={(e) => {
                        setRenameValue(e.target.value)
                      }}
                      autoComplete="off"
                      className="w-44 rounded-md border border-ink/15 bg-transparent px-2 py-1 text-[13px] dark:border-parchment/15"
                    />
                  </div>
                  <button
                    type="button"
                    disabled={busy}
                    aria-label={`Save org name ${o.slug}`}
                    onClick={() => {
                      void onRename(o.id)
                    }}
                    className="rounded-md border border-ink/15 px-2 py-1 text-xs disabled:cursor-not-allowed dark:border-parchment/15"
                  >
                    Save
                  </button>
                </div>
              ) : null}
              {teamFor === o.id ? (
                <div className="flex flex-wrap items-end gap-2">
                  <div>
                    <label htmlFor={`team-name-${o.id}`} className="mb-1 block text-xs font-medium">
                      Team name for {o.slug}
                    </label>
                    <input
                      id={`team-name-${o.id}`}
                      type="text"
                      value={teamName}
                      onChange={(e) => {
                        setTeamName(e.target.value)
                      }}
                      autoComplete="off"
                      className="w-44 rounded-md border border-ink/15 bg-transparent px-2 py-1 text-[13px] dark:border-parchment/15"
                    />
                  </div>
                  <button
                    type="button"
                    disabled={busy}
                    aria-label={`Create team in ${o.slug}`}
                    onClick={() => {
                      void onCreateTeam(o.id)
                    }}
                    className="rounded-md border border-ink/15 px-2 py-1 text-xs disabled:cursor-not-allowed dark:border-parchment/15"
                  >
                    Create team
                  </button>
                </div>
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </section>
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
        <TableScroll>
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
        </TableScroll>
      )}
      {isAdmin ? <OrgsManager /> : null}
      {isAdmin ? (
        <section aria-label="Org team inventory" className="space-y-3">
          <h2 className="font-display text-xl font-medium tracking-tight">Org inventory</h2>
          <p className="text-[13px] text-ink-soft dark:text-parchment-soft">
            Assign and revoke are idempotent — safe to retry. Sync-owned teams reject writes with
            409; the per-org Unassigned team can’t be deleted.
          </p>
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
