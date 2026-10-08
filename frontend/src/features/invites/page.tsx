import { useState } from 'react'
import { GatewayClient } from '../../shared/api/client.js'
import { toErrorMessage } from '../../shared/api/client.js'

/**
 * Account UUID shape for optional invite team placement (blank =
 * unplaced). Mirrors the gateway client validation so malformed ids
 * never cause a round trip.
 */
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/**
 * Invite minting: link-only or emailed, behind the admin guard.
 *
 * @remarks Backend truth (`AdminInviteController`): `POST
 * /v1/admin/invites` answers 201 `{ link, emailed }`; the link is
 * always returned even when mailed. Auth denial is stealth 404, never
 * 401. The receipt shows once — the operator copies it, then dismisses.
 *
 * @returns The invites board.
 */
function InvitesBoard(): React.JSX.Element {
  const [email, setEmail] = useState('')
  const [admin, setAdmin] = useState(false)
  const [teamId, setTeamId] = useState('')
  const [link, setLink] = useState<string | null>(null)
  const [emailed, setEmailed] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const mint = (): void => {
    setError(null)
    setNotice(null)
    setLink(null)
    const trimmed = email.trim()
    if (trimmed.length > 0 && !/^\S+@\S+\.\S+$/.test(trimmed)) {
      setError('Invite email must be valid.')
      return
    }
    const team = teamId.trim()
    if (team.length > 0 && !UUID_RE.test(team)) {
      setError('Invite team must be a valid UUID.')
      return
    }
    setBusy(true)
    const client = new GatewayClient()
    void client
      .createInvite({
        email: trimmed.length === 0 ? null : trimmed,
        admin,
        teamId: team.length === 0 ? null : team,
      })
      .then((out) => {
        setLink(out.link)
        setEmailed(out.emailed)
        setNotice('Invite created.')
      })
      .catch((e: unknown) => {
        setError(toErrorMessage(e, 'Invite creation failed.'))
      })
      .finally(() => {
        setBusy(false)
      })
  }

  return (
    <div className="space-y-4">
      <div className="space-y-3">
        <div>
          <label htmlFor="invite-email" className="mb-1 block text-[13px] font-medium">
            Email (optional — blank mints a link-only invite)
          </label>
          <input
            id="invite-email"
            type="email"
            value={email}
            autoComplete="off"
            onChange={(e) => {
              setEmail(e.target.value)
            }}
            placeholder="ops@example.com"
            className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-sm dark:border-parchment/15"
          />
        </div>
        <div>
          <label htmlFor="invite-team" className="mb-1 block text-[13px] font-medium">
            Team placement (optional — UUID, blank leaves the account unplaced)
          </label>
          <input
            id="invite-team"
            type="text"
            value={teamId}
            autoComplete="off"
            spellCheck={false}
            onChange={(e) => {
              setTeamId(e.target.value)
            }}
            placeholder="123e4567-e89b-12d3-a456-426614174000"
            className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-sm dark:border-parchment/15"
          />
          <p className="mt-1 text-[13px] text-ink-soft dark:text-parchment-soft">
            Placement lands ACTIVE on redeem; dangling placements redeem as 404 without consuming.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <label
            htmlFor="invite-admin"
            className="inline-flex min-h-11 cursor-pointer items-center gap-2 text-[13px]"
          >
            <input
              id="invite-admin"
              type="checkbox"
              checked={admin}
              onChange={(e) => {
                setAdmin(e.target.checked)
              }}
              className="size-4 accent-ember"
            />
            Admin invite
          </label>
          <span className="flex-1" />
          <button
            type="button"
            disabled={busy}
            onClick={mint}
            className="rounded-md border border-ink/15 px-3 py-2 text-[13px] disabled:cursor-not-allowed dark:border-parchment/15"
          >
            Mint invite
          </button>
        </div>
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
      {link === null ? null : (
        <div className="space-y-1 rounded-lg border border-ink/10 p-3 dark:border-parchment/10">
          <p className="font-mono text-[13px] wrap-break-word">{link}</p>
          <p className="text-[13px] text-ink-soft dark:text-parchment-soft">
            {emailed ? 'Mailed to the address above.' : 'Link only — no mail sent.'} Copy it now;
            redeeming consumes the token. Links expire 48 hours after minting.
          </p>
          <button
            type="button"
            onClick={() => {
              setLink(null)
            }}
            className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
          >
            Dismiss
          </button>
        </div>
      )}
    </div>
  )
}

/**
 * Invite screen: mint-only behind the admin guard (no inventory endpoint).
 *
 * @returns The invites screen.
 */
export function InvitesPage(): React.JSX.Element {
  return (
    <div className="space-y-4">
      <div className="space-y-1">
        <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
          <span aria-hidden="true" className="mr-1 text-ember">
            ❯
          </span>
          guard
        </p>
        <h1 className="font-display text-3xl font-medium tracking-tight">Invites</h1>
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          Mint single-use onboarding links. Tokens are consumed on redeem. Links expire 48 hours
          after minting.
        </p>
      </div>
      <InvitesBoard />
    </div>
  )
}
