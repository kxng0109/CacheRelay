import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useShallow } from 'zustand/react/shallow'
import * as z from 'zod/v4'
import { GatewayClient } from '../../shared/api/client.js'
import { toErrorMessage } from '../../shared/api/client.js'
import type { ApiKeyCreated } from '../../shared/api/types.js'
import { useAuthStore } from '../../shared/auth/store.js'

const schema = z.object({
  name: z.string().min(1, 'Name is required'),
  rpmLimit: z.number().min(1).max(100_000),
  dailyQuota: z.number().min(1).max(10_000_000),
  models: z.string().min(1, 'At least one model is required'),
})

type FormData = z.infer<typeof schema>

interface KeysBoardProps {
  /** Master admin key; the gate guarantees non-null before mounting. */
  adminKey: string
}

/**
 * Virtual API key administration: list, single-exposure create, delete.
 *
 * @remarks Proof-type: live (real `/v1/admin/keys` CRUD).
 *
 * @param props - The admin key for admin-surface calls.
 * @returns The keys board.
 */
function KeysBoard({ adminKey }: KeysBoardProps): React.JSX.Element {
  const qc = useQueryClient()
  const [created, setCreated] = useState<ApiKeyCreated | null>(null)
  const [error, setError] = useState<string | null>(null)

  const keys = useQuery({
    queryKey: ['keys'],
    queryFn: ({ signal }) => new GatewayClient({ token: adminKey, adminKey }).listKeys({ signal }),
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
      const out = await new GatewayClient({ token: adminKey, adminKey }).createKey({
        ...d,
        models: d.models
          .split(',')
          .map((m) => m.trim())
          .filter((m) => m.length > 0),
      })
      setCreated(out)
      reset()
      await qc.invalidateQueries({ queryKey: ['keys'] })
    } catch (e) {
      setError(toErrorMessage(e, 'Key creation failed.'))
    }
  }

  const onDelete = async (id: string): Promise<void> => {
    setError(null)
    try {
      await new GatewayClient({ token: adminKey, adminKey }).deleteKey(id)
      await qc.invalidateQueries({ queryKey: ['keys'] })
    } catch (e) {
      setError(toErrorMessage(e, 'Key deletion failed.'))
    }
  }

  return (
    <div className="space-y-4">
      {error === null ? null : (
        <p role="alert" className="text-sm text-danger dark:text-danger-soft">
          {error}
        </p>
      )}
      {created === null ? null : (
        <div role="alert" className="rounded-lg border border-warn/40 p-4">
          <p className="text-sm font-medium">Copy this plaintext now — it is never shown again.</p>
          <p className="mt-1 font-mono text-sm break-all tnum">{created.plaintext}</p>
        </div>
      )}
      <form
        onSubmit={(e) => {
          void handleSubmit(onCreate)(e)
        }}
        className="grid gap-3 rounded-lg border border-ink/10 p-4 sm:grid-cols-2 dark:border-parchment/10"
      >
        <div>
          <label htmlFor="key-name" className="mb-1 block text-xs font-medium">
            Name
          </label>
          <input
            id="key-name"
            {...register('name')}
            className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm dark:border-parchment/15"
          />
          {errors.name === undefined ? null : (
            <p role="alert" className="mt-1 text-xs text-danger dark:text-danger-soft">
              {errors.name.message}
            </p>
          )}
        </div>
        <div>
          <label htmlFor="key-models" className="mb-1 block text-xs font-medium">
            Models (comma-separated)
          </label>
          <input
            id="key-models"
            {...register('models')}
            className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm dark:border-parchment/15"
          />
          {errors.models === undefined ? null : (
            <p role="alert" className="mt-1 text-xs text-danger dark:text-danger-soft">
              {errors.models.message}
            </p>
          )}
        </div>
        <div>
          <label htmlFor="key-rpm" className="mb-1 block text-xs font-medium">
            Requests per minute
          </label>
          <input
            id="key-rpm"
            type="number"
            {...register('rpmLimit', { valueAsNumber: true })}
            className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm tnum dark:border-parchment/15"
          />
          {errors.rpmLimit === undefined ? null : (
            <p role="alert" className="mt-1 text-xs text-danger dark:text-danger-soft">
              {errors.rpmLimit.message}
            </p>
          )}
        </div>
        <div>
          <label htmlFor="key-quota" className="mb-1 block text-xs font-medium">
            Daily quota
          </label>
          <input
            id="key-quota"
            type="number"
            {...register('dailyQuota', { valueAsNumber: true })}
            className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm tnum dark:border-parchment/15"
          />
          {errors.dailyQuota === undefined ? null : (
            <p role="alert" className="mt-1 text-xs text-danger dark:text-danger-soft">
              {errors.dailyQuota.message}
            </p>
          )}
        </div>
        <div className="sm:col-span-2">
          <button
            type="submit"
            disabled={isSubmitting}
            className="rounded-md bg-ember px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
          >
            Create key
          </button>
        </div>
      </form>
      {keys.isPending ? (
        <p role="status" className="text-sm">
          Loading keys…
        </p>
      ) : keys.data === undefined || keys.data.keys.length === 0 ? (
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          No keys yet. Create the first key above.
        </p>
      ) : (
        <table className="w-full text-left text-sm">
          <caption className="sr-only">Virtual API keys</caption>
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">RPM</th>
              <th scope="col">Daily quota</th>
              <th scope="col">Models</th>
              <th scope="col">
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {keys.data.keys.map((k) => (
              <tr key={k.id} className="border-t border-ink/10 dark:border-parchment/10">
                <td className="py-2 font-mono text-xs">{k.name}</td>
                <td className="py-2 tnum">{k.rpmLimit}</td>
                <td className="py-2 tnum">{k.dailyQuota}</td>
                <td className="py-2 text-xs">{k.models.join(', ')}</td>
                <td className="py-2 text-right">
                  <button
                    type="button"
                    onClick={() => {
                      void onDelete(k.id)
                    }}
                    className="rounded-md border border-danger/40 px-3 py-2 text-xs text-danger dark:text-danger-soft"
                  >
                    Delete
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

/**
 * Virtual API key screen: admin-key gate plus the CRUD board.
 *
 * @returns The keys screen.
 */
export function KeysPage(): React.JSX.Element {
  const { adminKey } = useAuthStore(useShallow((s) => ({ adminKey: s.adminKey })))

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold tracking-tight">Keys</h1>
      {adminKey === null ? (
        <p className="text-sm">Unlock the admin key on the Circuits page first.</p>
      ) : (
        <KeysBoard adminKey={adminKey} />
      )}
    </div>
  )
}
