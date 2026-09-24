import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import * as z from 'zod/v4'
import { GatewayClient } from '../../shared/api/client.js'
import { toErrorMessage } from '../../shared/api/client.js'
import type { ApiKeyCreated } from '../../shared/api/types.js'
import { EmptyTrio } from '../../shared/components/EmptyTrio.js'
import { InspectorShell } from '../../shared/components/InspectorShell.js'
import { Modal } from '../../shared/components/Modal.js'
import { formatCount, formatShortDate } from '../../shared/utils/format.js'

const schema = z.object({
  ownerId: z.string().min(1, 'Owner is required'),
  ownerUserId: z.uuid('Owner account must be a valid UUID from the invite flow'),
  name: z.string().min(1, 'Name is required'),
  rpmLimit: z.number().min(0).max(100_000),
  tpmLimit: z.number().min(0).max(10_000_000),
  models: z.string(),
})

type FormData = z.infer<typeof schema>

/**
 * Copies text to the clipboard, reporting success honestly.
 *
 * @param text - Plaintext to copy (key material or IDs only).
 * @returns True when the platform clipboard accepted the write.
 */
async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    return false
  }
}

/**
 * Virtual API key administration: list, single-exposure create, delete.
 *
 * @remarks Proof-type: live (real `/v1/admin/keys` CRUD, shapes
 * live-verified). Register layout: header (count + filter + New key),
 * single-exposure reveal, creation modal, dense table with
 * row-expand inspector, header-docked errors. Row selection and modal
 * state are local UI state.
 *
 * @param props - The admin key for admin-surface calls.
 * @returns The keys board.
 */
function KeysBoard(): React.JSX.Element {
  const qc = useQueryClient()
  const [created, setCreated] = useState<ApiKeyCreated | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [filter, setFilter] = useState('')
  const [creating, setCreating] = useState(false)
  const createOpener = useRef<HTMLElement | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [confirming, setConfirming] = useState<string | null>(null)
  const [confirmingRevoke, setConfirmingRevoke] = useState<string | null>(null)
  const [copied, setCopied] = useState<string | null>(null)
  /** Key ids tombstoned in this session. The list endpoint exposes no
   * revoked flag, so a successful revoke marks the row terminal locally. */
  const [revokedIds, setRevokedIds] = useState<ReadonlySet<string>>(new Set())

  const keys = useQuery({
    queryKey: ['keys'],
    queryFn: ({ signal }) => new GatewayClient().listKeys({ signal }),
  })

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<FormData>({ resolver: zodResolver(schema), mode: 'onSubmit' })

  const onCreate = async (d: FormData): Promise<void> => {
    setError(null)
    setCreated(null)
    try {
      const out = await new GatewayClient().createKey({
        ownerId: d.ownerId,
        ownerUserId: d.ownerUserId,
        name: d.name,
        rpmLimit: d.rpmLimit,
        tpmLimit: d.tpmLimit,
        allowedModels: d.models
          .split(',')
          .map((m) => m.trim())
          .filter((m) => m.length > 0),
      })
      setCreated(out)
      reset()
      closeCreate()
      await qc.invalidateQueries({ queryKey: ['keys'] })
    } catch (e) {
      setError(toErrorMessage(e, 'Key creation failed.'))
    }
  }

  const openCreate = (): void => {
    setError(null)
    createOpener.current =
      document.activeElement instanceof HTMLElement ? document.activeElement : null
    setCreating(true)
  }

  const closeCreate = (): void => {
    setCreating(false)
    createOpener.current?.focus()
  }

  const onToggleEnabled = async (keyId: string, enabled: boolean): Promise<void> => {
    setError(null)
    try {
      await new GatewayClient().setKeyEnabled(keyId, enabled)
      await qc.invalidateQueries({ queryKey: ['keys'] })
    } catch (e) {
      setError(toErrorMessage(e, 'Key update failed.'))
    }
  }

  const onRevoke = async (keyId: string): Promise<void> => {
    setError(null)
    try {
      await new GatewayClient().revokeKey(keyId)
      setRevokedIds((prev) => new Set(prev).add(keyId))
      setConfirmingRevoke(null)
      await qc.invalidateQueries({ queryKey: ['keys'] })
    } catch (e) {
      setError(toErrorMessage(e, 'Key revocation failed.'))
    }
  }

  const onDelete = async (keyId: string): Promise<void> => {
    setError(null)
    try {
      await new GatewayClient().deleteKey(keyId)
      setConfirming(null)
      if (selected === keyId) setSelected(null)
      await qc.invalidateQueries({ queryKey: ['keys'] })
    } catch (e) {
      setError(toErrorMessage(e, 'Key deletion failed.'))
    }
  }

  const onCopy = (target: string, text: string): void => {
    void copyText(text).then((ok) => {
      if (ok) setCopied(target)
    })
  }

  const rows = keys.data?.keys ?? []
  const query = filter.trim().toLowerCase()
  const visible = rows.filter(
    (k) =>
      query.length === 0 ||
      k.name.toLowerCase().includes(query) ||
      k.allowedModels.some((m) => m.toLowerCase().includes(query)),
  )
  const enabledCount = rows.filter((k) => k.enabled).length
  const inspected = rows.find((k) => k.keyId === selected) ?? null

  return (
    <div className="space-y-6">
      <div className="space-y-4">
        <div className="space-y-1">
          <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
            <span aria-hidden="true" className="mr-1 text-ember">
              ❯
            </span>
            guard
          </p>
          <h1 className="font-display text-3xl font-medium tracking-tight">Keys</h1>
          <p className="text-sm text-ink-soft dark:text-parchment-soft">
            One key per team or service. Limits and models scope what each key can spend.
          </p>
          <p className="font-mono text-xs text-ink-soft tnum dark:text-parchment-soft">
            {rows.length} keys · {enabledCount} enabled · plaintext shows once at creation
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <span className="flex-1" />
          <label htmlFor="key-filter" className="sr-only">
            Filter keys
          </label>
          <input
            id="key-filter"
            type="search"
            value={filter}
            placeholder="Filter"
            onChange={(e) => {
              setFilter(e.target.value)
            }}
            className="w-48 rounded-md border border-ink/15 bg-transparent px-3 py-2 text-[13px] dark:border-parchment/15"
          />
          <button
            type="button"
            onClick={openCreate}
            className="rounded-md bg-ink px-4 py-2 text-sm font-medium text-paper dark:bg-parchment dark:text-night"
          >
            New key
          </button>
        </div>
        {error === null || creating ? null : (
          <p role="alert" className="text-sm text-danger dark:text-danger-soft">
            {error}
          </p>
        )}
        {created === null ? null : (
          <div
            role="alert"
            className="space-y-2 rounded-lg border border-warn/40 bg-cream p-4 dark:bg-transparent"
          >
            <p className="text-sm font-medium">Copy this plaintext now. It is never shown again.</p>
            <p className="font-mono text-sm break-all tnum">{created.key}</p>
            <div className="flex gap-2">
              <button
                type="button"
                onClick={() => {
                  onCopy('reveal', created.key)
                }}
                className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
              >
                {copied === 'reveal' ? 'Copied' : 'Copy'}
              </button>
              <button
                type="button"
                onClick={() => {
                  setCreated(null)
                  setCopied(null)
                }}
                className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
              >
                Dismiss
              </button>
            </div>
          </div>
        )}
        {creating ? (
          <Modal
            label="New key"
            title="New key"
            subtitle="One key per team or service. Plaintext shows once."
            closeLabel="Close new key"
            wide
            onClose={closeCreate}
          >
            {error === null ? null : (
              <p role="alert" className="text-sm text-danger dark:text-danger-soft">
                {error}
              </p>
            )}
            <form
              onSubmit={(e) => {
                void handleSubmit(onCreate)(e)
              }}
              aria-label="Create key"
              className="grid gap-3 sm:grid-cols-2"
            >
              <div>
                <label htmlFor="key-owner" className="mb-1 block text-[13px] font-medium">
                  Owner
                </label>
                <input
                  id="key-owner"
                  {...register('ownerId')}
                  autoComplete="off"
                  autoFocus
                  className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm dark:border-parchment/15"
                />
                {errors.ownerId === undefined ? null : (
                  <p role="alert" className="mt-1 text-[13px] text-danger dark:text-danger-soft">
                    {errors.ownerId.message}
                  </p>
                )}
              </div>
              <div>
                <label htmlFor="key-owner-user" className="mb-1 block text-[13px] font-medium">
                  Owner account UUID (from the invite flow)
                </label>
                <input
                  id="key-owner-user"
                  {...register('ownerUserId')}
                  autoComplete="off"
                  spellCheck={false}
                  placeholder="123e4567-e89b-12d3-a456-426614174000"
                  className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-sm dark:border-parchment/15"
                />
                {errors.ownerUserId === undefined ? null : (
                  <p role="alert" className="mt-1 text-[13px] text-danger dark:text-danger-soft">
                    {errors.ownerUserId.message}
                  </p>
                )}
                {error !== null && /owner/i.test(error) ? (
                  <p role="alert" className="mt-1 text-[13px] text-danger dark:text-danger-soft">
                    {error}
                  </p>
                ) : null}
              </div>
              <div>
                <label htmlFor="key-name" className="mb-1 block text-[13px] font-medium">
                  Name
                </label>
                <input
                  id="key-name"
                  {...register('name')}
                  autoComplete="off"
                  className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm dark:border-parchment/15"
                />
                {errors.name === undefined ? null : (
                  <p role="alert" className="mt-1 text-[13px] text-danger dark:text-danger-soft">
                    {errors.name.message}
                  </p>
                )}
              </div>
              <div>
                <label htmlFor="key-models" className="mb-1 block text-[13px] font-medium">
                  Models (comma-separated, empty means all)
                </label>
                <input
                  id="key-models"
                  {...register('models')}
                  className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm dark:border-parchment/15"
                />
              </div>
              <div>
                <label htmlFor="key-rpm" className="mb-1 block text-[13px] font-medium">
                  Requests per minute (0 means unlimited)
                </label>
                <input
                  id="key-rpm"
                  type="number"
                  min={0}
                  {...register('rpmLimit', { valueAsNumber: true })}
                  className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm tnum dark:border-parchment/15"
                />
                {errors.rpmLimit === undefined ? null : (
                  <p role="alert" className="mt-1 text-[13px] text-danger dark:text-danger-soft">
                    {errors.rpmLimit.message}
                  </p>
                )}
              </div>
              <div>
                <label htmlFor="key-tpm" className="mb-1 block text-[13px] font-medium">
                  Tokens per minute (0 means unlimited)
                </label>
                <input
                  id="key-tpm"
                  type="number"
                  min={0}
                  {...register('tpmLimit', { valueAsNumber: true })}
                  className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm tnum dark:border-parchment/15"
                />
                {errors.tpmLimit === undefined ? null : (
                  <p role="alert" className="mt-1 text-[13px] text-danger dark:text-danger-soft">
                    {errors.tpmLimit.message}
                  </p>
                )}
              </div>
              <div className="flex justify-end gap-2 sm:col-span-2">
                <button
                  type="button"
                  onClick={closeCreate}
                  className="rounded-md border border-ink/15 px-4 py-2 text-sm dark:border-parchment/15"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={isSubmitting}
                  className="rounded-md bg-ink px-4 py-2 text-sm font-medium text-paper disabled:cursor-not-allowed disabled:bg-ink-soft disabled:text-paper dark:bg-parchment dark:text-night dark:disabled:bg-parchment-soft dark:disabled:text-night"
                >
                  Create key
                </button>
              </div>
            </form>
          </Modal>
        ) : null}
        {keys.isPending ? (
          <p role="status" className="text-sm">
            Loading keys…
          </p>
        ) : keys.error instanceof Error ? (
          <p role="alert" className="text-sm text-danger dark:text-danger-soft">
            {keys.error.message}
          </p>
        ) : keys.data === undefined || visible.length === 0 ? (
          rows.length === 0 ? (
            <EmptyTrio
              title="No keys yet"
              cue="One key per team or service. Plaintext shows once at creation only."
              action={{ label: 'New key', onClick: openCreate }}
            />
          ) : (
            <p className="text-sm text-ink-soft dark:text-parchment-soft">
              No keys match this filter.
            </p>
          )
        ) : (
          <table className="w-full text-left text-sm">
            <caption className="sr-only">Virtual API keys</caption>
            <thead className="sticky top-0 bg-paper dark:bg-night">
              <tr className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
                <th scope="col" className="py-2 pr-3 font-medium">
                  Name
                </th>
                <th scope="col" className="py-2 pr-3 text-right font-medium">
                  RPM
                </th>
                <th scope="col" className="py-2 pr-3 text-right font-medium">
                  TPM
                </th>
                <th scope="col" className="py-2 pr-3 font-medium">
                  Models
                </th>
                <th scope="col" className="py-2 pr-3 font-medium">
                  State
                </th>
                <th scope="col" className="py-2 text-right font-medium">
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {visible.map((k) => (
                <tr
                  key={k.keyId}
                  tabIndex={0}
                  aria-selected={k.keyId === selected}
                  onClick={() => {
                    setSelected(k.keyId === selected ? null : k.keyId)
                  }}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault()
                      setSelected(k.keyId === selected ? null : k.keyId)
                    }
                  }}
                  className={`cursor-pointer border-t border-ink/10 dark:border-parchment/10 ${
                    k.keyId === selected ? 'bg-ink/4 dark:bg-parchment/6' : ''
                  }`}
                >
                  <td className="py-2 pr-3 font-mono text-[13px]">{k.name}</td>
                  <td className="py-2 pr-3 text-right text-[13px] tnum">
                    {k.rpmLimit === 0 ? 'unlimited' : formatCount(k.rpmLimit)}
                  </td>
                  <td className="py-2 pr-3 text-right text-[13px] tnum">
                    {k.tpmLimit === 0 ? 'unlimited' : formatCount(k.tpmLimit)}
                  </td>
                  <td className="max-w-48 truncate py-2 pr-3 text-[13px]">
                    {k.allowedModels.length === 0 ? 'all' : k.allowedModels.join(', ')}
                  </td>
                  <td className="py-2 pr-3">
                    <span
                      className={`rounded px-2 py-1 text-[13px] ${
                        k.enabled
                          ? 'bg-success/15 text-success dark:text-success-soft'
                          : 'bg-warn/15 text-warn dark:text-warn-soft'
                      }`}
                    >
                      ● {k.enabled ? 'enabled' : 'disabled'}
                    </span>
                  </td>
                  <td className="py-2 text-right">
                    {confirming === k.keyId ? (
                      <span className="inline-flex items-center gap-2 text-[13px]">
                        Delete “{k.name}”?
                        <button
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation()
                            void onDelete(k.keyId)
                          }}
                          className="rounded-md border border-danger/40 px-2 py-1 text-danger dark:text-danger-soft"
                        >
                          Yes
                        </button>
                        <button
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation()
                            setConfirming(null)
                          }}
                          className="rounded-md border border-ink/15 px-2 py-1 dark:border-parchment/15"
                        >
                          No
                        </button>
                      </span>
                    ) : (
                      <button
                        type="button"
                        onClick={(e) => {
                          e.stopPropagation()
                          setConfirming(k.keyId)
                        }}
                        className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
                      >
                        Delete
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {inspected === null ? (
          <p className="text-[13px] text-ink-soft dark:text-parchment-soft">
            Select a row to inspect a key.
          </p>
        ) : (
          <InspectorShell
            label="Key inspector"
            title={inspected.name}
            onClose={() => {
              setSelected(null)
            }}
          >
            <dl className="space-y-2 text-[13px]">
              <div className="flex justify-between gap-3">
                <dt className="text-ink-soft dark:text-parchment-soft">Key ID</dt>
                <dd className="font-mono text-xs break-all">{inspected.keyId}</dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-ink-soft dark:text-parchment-soft">Owner</dt>
                <dd>{inspected.ownerId}</dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-ink-soft dark:text-parchment-soft">Account</dt>
                <dd className="font-mono text-xs break-all">{inspected.ownerUsername ?? 'n/a'}</dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-ink-soft dark:text-parchment-soft">RPM / TPM</dt>
                <dd className="tnum">
                  {inspected.rpmLimit === 0 ? 'unlimited' : formatCount(inspected.rpmLimit)} /{' '}
                  {inspected.tpmLimit === 0 ? 'unlimited' : formatCount(inspected.tpmLimit)}
                </dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-ink-soft dark:text-parchment-soft">Models</dt>
                <dd>
                  {inspected.allowedModels.length === 0
                    ? 'all'
                    : inspected.allowedModels.join(', ')}
                </dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-ink-soft dark:text-parchment-soft">Providers</dt>
                <dd>
                  {inspected.allowedProviders.length === 0
                    ? 'all'
                    : inspected.allowedProviders.join(', ')}
                </dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-ink-soft dark:text-parchment-soft">State</dt>
                <dd>
                  ●{' '}
                  {revokedIds.has(inspected.keyId)
                    ? 'revoked · terminal'
                    : inspected.enabled
                      ? 'enabled'
                      : 'disabled'}
                </dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-ink-soft dark:text-parchment-soft">Created</dt>
                <dd className="tnum" title={inspected.createdAt}>
                  {formatShortDate(inspected.createdAt)}
                </dd>
              </div>
            </dl>
            <p className="text-[13px] text-ink-soft dark:text-parchment-soft">
              Plaintext shows once at creation only. Key IDs are safe to copy.
            </p>
            {revokedIds.has(inspected.keyId) ? (
              <p className="text-[13px] text-danger dark:text-danger-soft">
                Revoked this session. This is terminal and cannot be undone.
              </p>
            ) : (
              <button
                type="button"
                onClick={() => {
                  void onToggleEnabled(inspected.keyId, !inspected.enabled)
                }}
                className="w-full rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
              >
                {inspected.enabled ? 'Disable key' : 'Enable key'}
              </button>
            )}
            <button
              type="button"
              onClick={() => {
                onCopy(inspected.keyId, inspected.keyId)
              }}
              className="w-full rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
            >
              {copied === inspected.keyId ? 'Copied' : 'Copy key ID'}
            </button>
            {revokedIds.has(inspected.keyId) ? null : confirmingRevoke === inspected.keyId ? (
              <span className="flex items-center gap-2 text-[13px]">
                Revoke “{inspected.name}” forever?
                <button
                  type="button"
                  onClick={() => {
                    void onRevoke(inspected.keyId)
                  }}
                  className="rounded-md border border-danger/40 px-2 py-1 text-danger dark:text-danger-soft"
                >
                  Yes, revoke
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setConfirmingRevoke(null)
                  }}
                  className="rounded-md border border-ink/15 px-2 py-1 dark:border-parchment/15"
                >
                  No
                </button>
              </span>
            ) : (
              <button
                type="button"
                onClick={() => {
                  setConfirmingRevoke(inspected.keyId)
                }}
                className="w-full rounded-md border border-danger/40 px-3 py-2 text-[13px] text-danger dark:text-danger-soft"
              >
                Revoke key…
              </button>
            )}
          </InspectorShell>
        )}
      </div>
    </div>
  )
}

/**
 * Virtual API key screen: CRUD board behind the admin route guard.
 *
 * @returns The keys screen.
 */
export function KeysPage(): React.JSX.Element {
  return (
    <div className="space-y-4">
      <KeysBoard />
    </div>
  )
}
