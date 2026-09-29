import { useState } from 'react'
import { GatewayClient } from '../../shared/api/client.js'
import { toErrorMessage } from '../../shared/api/client.js'

/**
 * Account operations by id: disable/enable plus delete.
 *
 * @remarks Backend truth (`AdminUserController`): there is no user
 * inventory endpoint, so this board is id-driven and states that
 * honestly. Disable takes an explicit flag (absent answers 400);
 * re-enabling never resurrects tombstoned keys. Delete terminally
 * revokes every attached key and clears the default key first.
 *
 * @returns The users board.
 */
function UsersBoard(): React.JSX.Element {
  const [id, setId] = useState('')
  const [confirming, setConfirming] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const trimmed = id.trim()
  const valid =
    /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(trimmed)
  const ready = valid && !busy

  const setDisabled = (disabled: boolean): void => {
    setError(null)
    setNotice(null)
    setBusy(true)
    const client = new GatewayClient()
    void client
      .setUserDisabled(id.trim(), disabled)
      .then(() => {
        setNotice(disabled ? 'Account disabled.' : 'Account re-enabled.')
      })
      .catch((e: unknown) => {
        setError(toErrorMessage(e, 'Account update failed.'))
      })
      .finally(() => {
        setBusy(false)
      })
  }

  const remove = (): void => {
    setError(null)
    setNotice(null)
    setBusy(true)
    const client = new GatewayClient()
    void client
      .deleteUser(id.trim())
      .then(() => {
        setNotice('Account deleted.')
        setConfirming(false)
      })
      .catch((e: unknown) => {
        setError(toErrorMessage(e, 'Account deletion failed.'))
      })
      .finally(() => {
        setBusy(false)
      })
  }

  return (
    <div className="space-y-4">
      <p className="text-sm text-ink-soft dark:text-parchment-soft">
        No user inventory endpoint exists: operate by account UUID from the invite flow. Unknown ids
        answer 404 — check the UUID before retrying.
      </p>
      <div>
        <label htmlFor="users-id" className="mb-1 block text-[13px] font-medium">
          Account id (UUID)
        </label>
        <input
          id="users-id"
          value={id}
          autoComplete="off"
          onChange={(e) => {
            setId(e.target.value)
          }}
          placeholder="123e4567-e89b-12d3-a456-426614174000"
          aria-invalid={id.length > 0 && !valid}
          aria-describedby={id.length > 0 && !valid ? 'users-id-error' : undefined}
          className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-sm dark:border-parchment/15"
        />
        {id.length > 0 && !valid ? (
          <p
            id="users-id-error"
            role="alert"
            className="mt-1 text-[13px] text-danger dark:text-danger-soft"
          >
            Account id must be a valid UUID from the invite flow.
          </p>
        ) : null}
      </div>
      <div className="flex flex-wrap gap-2">
        <button
          type="button"
          disabled={!ready}
          onClick={() => {
            setDisabled(true)
          }}
          className="rounded-md border border-ink/15 px-3 py-2 text-[13px] disabled:cursor-not-allowed dark:border-parchment/15"
        >
          Disable account
        </button>
        <button
          type="button"
          disabled={!ready}
          onClick={() => {
            setDisabled(false)
          }}
          className="rounded-md border border-ink/15 px-3 py-2 text-[13px] disabled:cursor-not-allowed dark:border-parchment/15"
        >
          Re-enable account
        </button>
        {confirming ? (
          <span className="inline-flex items-center gap-2 text-[13px]">
            Deleting revokes every attached key and clears the default key. Continue?
            <button
              type="button"
              onClick={remove}
              className="rounded-md border border-danger/40 px-2 py-1 text-danger dark:text-danger-soft"
            >
              Yes, delete
            </button>
            <button
              type="button"
              onClick={() => {
                setConfirming(false)
              }}
              className="rounded-md border border-ink/15 px-2 py-1 dark:border-parchment/15"
            >
              No
            </button>
          </span>
        ) : (
          <button
            type="button"
            disabled={!ready}
            onClick={() => {
              setConfirming(true)
            }}
            className="rounded-md border border-ink/15 px-3 py-2 text-[13px] disabled:cursor-not-allowed dark:border-parchment/15"
          >
            Delete account
          </button>
        )}
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
    </div>
  )
}

/**
 * Account screen: id-driven operations behind the admin guard.
 *
 * @returns The users screen.
 */
export function UsersPage(): React.JSX.Element {
  return (
    <div className="space-y-4">
      <div className="space-y-1">
        <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
          <span aria-hidden="true" className="mr-1 text-ember">
            ❯
          </span>
          guard
        </p>
        <h1 className="font-display text-3xl font-medium tracking-tight">Users</h1>
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          Disable, re-enable, or delete accounts. Deletion is irreversible.
        </p>
      </div>
      <UsersBoard />
    </div>
  )
}
