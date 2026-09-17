import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useShallow } from 'zustand/react/shallow'
import * as z from 'zod/v4'
import { GatewayClient } from '../../shared/api/client.js'
import { toErrorMessage } from '../../shared/api/client.js'
import { useAuthStore } from '../../shared/auth/store.js'

const schema = z.object({
  name: z.string().min(1, 'Name is required'),
  limitMicros: z.number().min(1, 'Limit must be positive'),
})

type FormData = z.infer<typeof schema>

/**
 * Cache and budget administration: tier stats, purge, budget gauge + create.
 *
 * @remarks Proof-type: live (real `/v1/admin/cache/*` and `/v1/admin/budgets`).
 *
 * @returns The cache and budgets screen.
 */
export function CachePage(): React.JSX.Element {
  const { adminKey } = useAuthStore(useShallow((s) => ({ adminKey: s.adminKey })))

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold tracking-tight">Cache and budgets</h1>
      {adminKey === null ? (
        <p className="text-sm">Unlock the admin key on the Circuits page first.</p>
      ) : (
        <CacheBoard adminKey={adminKey} />
      )}
    </div>
  )
}

interface CacheBoardProps {
  /** Master admin key; the gate guarantees non-null before mounting. */
  adminKey: string
}

/**
 * Tier stats, purge, budget gauges, and budget creation.
 *
 * @param props - The admin key for admin-surface calls.
 * @returns The cache board.
 */
function CacheBoard({ adminKey }: CacheBoardProps): React.JSX.Element {
  const qc = useQueryClient()
  const [notice, setNotice] = useState<string | null>(null)

  const stats = useQuery({
    queryKey: ['cache-stats'],
    queryFn: ({ signal }) =>
      new GatewayClient({ token: adminKey, adminKey }).cacheStats({ signal }),
  })
  const budgets = useQuery({
    queryKey: ['budgets'],
    queryFn: ({ signal }) =>
      new GatewayClient({ token: adminKey, adminKey }).listBudgets({ signal }),
  })

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<FormData>({ resolver: zodResolver(schema), mode: 'onSubmit' })

  const purge = async (): Promise<void> => {
    setNotice(null)
    try {
      await new GatewayClient({ token: adminKey, adminKey }).purgeCache()
      setNotice('Cache purged.')
      await qc.invalidateQueries({ queryKey: ['cache-stats'] })
    } catch (e) {
      setNotice(toErrorMessage(e, 'Purge failed.'))
    }
  }

  const onCreate = async (d: FormData): Promise<void> => {
    setNotice(null)
    try {
      await new GatewayClient({ token: adminKey, adminKey }).createBudget(d)
      reset()
      await qc.invalidateQueries({ queryKey: ['budgets'] })
    } catch (e) {
      setNotice(toErrorMessage(e, 'Budget creation failed.'))
    }
  }

  return (
    <div className="space-y-4">
      {notice === null ? null : (
        <p role="status" className="text-xs">
          {notice}
        </p>
      )}
      {stats.isPending ? (
        <p role="status" className="text-sm">
          Loading cache stats…
        </p>
      ) : stats.error instanceof Error ? (
        <p role="alert" className="text-sm text-danger">
          {stats.error.message}
        </p>
      ) : stats.data === undefined ? null : (
        <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <div className="rounded-lg border border-ink/10 p-3 dark:border-parchment/10">
            <dt className="text-xs opacity-70">L0 fill</dt>
            <dd className="text-lg tnum">
              {stats.data.l0Size}/{stats.data.l0Capacity}
            </dd>
          </div>
          <div className="rounded-lg border border-ink/10 p-3 dark:border-parchment/10">
            <dt className="text-xs opacity-70">Exact entries</dt>
            <dd className="text-lg tnum">{stats.data.exactEntries}</dd>
          </div>
          <div className="rounded-lg border border-ink/10 p-3 dark:border-parchment/10">
            <dt className="text-xs opacity-70">Semantic vectors</dt>
            <dd className="text-lg tnum">{stats.data.semanticVectors}</dd>
          </div>
          <div className="rounded-lg border border-ink/10 p-3 dark:border-parchment/10">
            <dt className="text-xs opacity-70">Redis</dt>
            <dd className="text-lg">{stats.data.redisConfigured ? '● On' : '■ Off'}</dd>
          </div>
        </dl>
      )}
      <button
        type="button"
        onClick={() => {
          void purge()
        }}
        className="rounded-md border border-danger/40 px-4 py-2 text-sm text-danger"
      >
        Purge cache
      </button>
      <h2 className="text-base font-semibold">Budgets</h2>
      {budgets.isPending ? (
        <p role="status" className="text-sm">
          Loading budgets…
        </p>
      ) : budgets.error instanceof Error ? (
        <p role="alert" className="text-sm text-danger">
          {budgets.error.message}
        </p>
      ) : budgets.data === undefined || budgets.data.budgets.length === 0 ? (
        <p className="text-sm opacity-70">No budgets yet. Create the first budget below.</p>
      ) : (
        <ul className="space-y-2">
          {budgets.data.budgets.map((b) => {
            const pct =
              b.limitMicros === 0 ? 0 : Math.min(100, (b.spentMicros / b.limitMicros) * 100)
            return (
              <li
                key={b.id}
                className="rounded-lg border border-ink/10 p-3 dark:border-parchment/10"
              >
                <div className="flex justify-between text-sm">
                  <span>{b.name}</span>
                  <span className="text-xs tnum">{b.remainingMicros} µ$ left</span>
                </div>
                <div
                  role="progressbar"
                  aria-valuenow={Math.round(pct)}
                  aria-valuemin={0}
                  aria-valuemax={100}
                  aria-label={`${b.name} spend`}
                  className="mt-2 h-2 rounded bg-ink/10 dark:bg-parchment/10"
                >
                  <div className="h-2 rounded bg-ember" style={{ width: `${String(pct)}%` }} />
                </div>
              </li>
            )
          })}
        </ul>
      )}
      <form
        onSubmit={(e) => {
          void handleSubmit(onCreate)(e)
        }}
        className="grid gap-3 rounded-lg border border-ink/10 p-4 sm:grid-cols-2 dark:border-parchment/10"
      >
        <div>
          <label htmlFor="budget-name" className="mb-1 block text-xs font-medium">
            Name
          </label>
          <input
            id="budget-name"
            {...register('name')}
            className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm dark:border-parchment/15"
          />
          {errors.name === undefined ? null : (
            <p role="alert" className="mt-1 text-xs text-danger">
              {errors.name.message}
            </p>
          )}
        </div>
        <div>
          <label htmlFor="budget-limit" className="mb-1 block text-xs font-medium">
            Limit (micro-dollars)
          </label>
          <input
            id="budget-limit"
            type="number"
            {...register('limitMicros', { valueAsNumber: true })}
            className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm tnum dark:border-parchment/15"
          />
          {errors.limitMicros === undefined ? null : (
            <p role="alert" className="mt-1 text-xs text-danger">
              {errors.limitMicros.message}
            </p>
          )}
        </div>
        <div className="sm:col-span-2">
          <button
            type="submit"
            disabled={isSubmitting}
            className="rounded-md bg-ember px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
          >
            Create budget
          </button>
        </div>
      </form>
    </div>
  )
}
