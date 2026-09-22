import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import * as z from 'zod/v4'
import { GatewayClient } from '../../shared/api/client.js'
import { formatBytes, formatMicros } from '../../shared/utils/format.js'
import { toErrorMessage } from '../../shared/api/client.js'

const schema = z.object({
  level: z.string().min(1, 'Level is required'),
  subjectId: z.string().min(1, 'Subject is required'),
  minuteMicros: z.number().min(0),
  monthMicros: z.number().min(0),
  webhookUrl: z.string().optional(),
})

type FormData = z.infer<typeof schema>

/**
 * Cache and budget administration: tier configuration, purge, budget caps.
 *
 * @remarks Proof-type: live (real `/v1/admin/cache/*` and `/v1/admin/budgets`).
 *
 * @returns The cache and budgets screen.
 */
export function CachePage(): React.JSX.Element {
  return (
    <div className="space-y-4">
      <CacheBoard />
    </div>
  )
}

/**
 * Tier configuration, purge, budget caps, and budget creation.
 *
 * @remarks Behind the admin route guard; the session Bearer attaches
 * automatically, so no credential prop is needed. The stats endpoint
 * reports configuration flags (scopes, caps, tier and guard switches),
 * never live fill counters — unknowns render as em dashes.
 *
 * @returns The cache board.
 */
function CacheBoard(): React.JSX.Element {
  const qc = useQueryClient()
  const [notice, setNotice] = useState<string | null>(null)
  const [confirmingPurge, setConfirmingPurge] = useState(false)
  const [purgeScope, setPurgeScope] = useState('')
  const [budgetsCopied, setBudgetsCopied] = useState(false)
  const [budgetsCopyError, setBudgetsCopyError] = useState<string | null>(null)

  const stats = useQuery({
    queryKey: ['cache-stats'],
    queryFn: ({ signal }) => new GatewayClient().cacheStats({ signal }),
  })
  const budgets = useQuery({
    queryKey: ['budgets'],
    queryFn: ({ signal }) => new GatewayClient().listBudgets({ signal }),
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
      const scope = purgeScope.trim()
      const out = await new GatewayClient().purgeCache(scope.length === 0 ? undefined : scope)
      setConfirmingPurge(false)
      setNotice(`Cache purged (${out.evictedScope}).`)
      await qc.invalidateQueries({ queryKey: ['cache-stats'] })
    } catch (e) {
      setNotice(toErrorMessage(e, 'Purge failed.'))
    }
  }

  const onCreate = async (d: FormData): Promise<void> => {
    setNotice(null)
    try {
      const webhook = d.webhookUrl?.trim()
      await new GatewayClient().createBudget({
        level: d.level,
        subjectId: d.subjectId,
        minuteMicros: d.minuteMicros,
        monthMicros: d.monthMicros,
        ...(webhook ? { webhookUrl: webhook } : {}),
      })
      reset()
      await qc.invalidateQueries({ queryKey: ['budgets'] })
    } catch (e) {
      setNotice(toErrorMessage(e, 'Budget creation failed.'))
    }
  }

  return (
    <div className="space-y-4">
      <div className="space-y-1">
        <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
          <span aria-hidden="true" className="mr-1 text-ember">
            ❯
          </span>
          guard
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <h1 className="font-display text-3xl font-medium tracking-tight">Cache and budgets</h1>
          {stats.data === undefined ? null : (
            <span className="rounded-full border border-ink/15 px-2 py-0.5 font-mono text-xs tnum dark:border-parchment/15">
              cache {stats.data.enabled ? 'on' : 'off'} · l1 redis{' '}
              {stats.data.l1RedisEnabled ? 'on' : 'off'}
            </span>
          )}
          <span className="flex-1" />
          <button
            type="button"
            onClick={() => {
              void qc.invalidateQueries({ queryKey: ['cache-stats'] })
            }}
            className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
          >
            Refresh
          </button>
          <button
            type="button"
            onClick={() => {
              setConfirmingPurge((c) => !c)
            }}
            className="rounded-md border border-danger/40 px-3 py-2 text-[13px] text-danger dark:text-danger-soft"
          >
            Purge…
          </button>
        </div>
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          Three tiers answer before providers do. Budgets cap spend per key, team, or org and alert
          through webhooks.
        </p>
      </div>
      {confirmingPurge ? (
        <div className="space-y-2 rounded-lg border border-danger/40 bg-cream p-4 dark:bg-transparent">
          <p className="text-sm font-medium">Purge the entire cache? This cannot be undone.</p>
          <div className="flex flex-wrap items-center gap-2">
            <label htmlFor="purge-scope" className="sr-only">
              Owner scope (empty means global)
            </label>
            <input
              id="purge-scope"
              value={purgeScope}
              placeholder="Owner scope, empty means global"
              onChange={(e) => {
                setPurgeScope(e.target.value)
              }}
              className="w-64 rounded-md border border-ink/15 bg-transparent px-3 py-2 text-[13px] dark:border-parchment/15"
            />
            <button
              type="button"
              onClick={() => {
                void purge()
              }}
              className="rounded-md border border-danger/40 px-3 py-2 text-[13px] text-danger dark:text-danger-soft"
            >
              Purge now
            </button>
            <button
              type="button"
              onClick={() => {
                setConfirmingPurge(false)
              }}
              className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
            >
              Cancel
            </button>
          </div>
        </div>
      ) : null}
      {notice === null ? null : (
        <p role="status" className="text-[13px]">
          {notice}
        </p>
      )}
      {stats.isPending ? (
        <p role="status" className="text-sm">
          Loading cache config…
        </p>
      ) : stats.error instanceof Error ? (
        <p role="alert" className="text-sm text-danger dark:text-danger-soft">
          {stats.error.message}
        </p>
      ) : stats.data === undefined ? null : (
        <dl className="grid grid-cols-3 gap-3">
          <div className="min-h-19 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
            <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">State</dt>
            <dd className="font-mono text-lg tnum">
              {stats.data.enabled ? 'on' : 'off'} · {stats.data.defaultScope}
            </dd>
            <dd className="mt-1 text-xs text-ink-soft dark:text-parchment-soft">
              Default scope for new entries.
            </dd>
          </div>
          <div className="min-h-19 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
            <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">L0 cap</dt>
            <dd className="font-mono text-lg tnum">{formatBytes(stats.data.l0MaxBytes)}</dd>
            <dd className="mt-1 text-xs text-ink-soft dark:text-parchment-soft">
              TTL {stats.data.l0InMemoryTtlSeconds}s in memory.
            </dd>
          </div>
          <div className="min-h-19 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent">
            <dt className="text-[13px] text-ink-soft dark:text-parchment-soft">Tiers</dt>
            <dd className="font-mono text-lg tnum">
              l1 {stats.data.l1RedisEnabled ? 'on' : 'off'} · l2{' '}
              {stats.data.l2SemanticEnabled ? 'on' : 'off'}
            </dd>
            <dd className="mt-1 text-xs text-ink-soft dark:text-parchment-soft">
              {stats.data.embeddingModel} @ {stats.data.similarityThreshold} · guards{' '}
              {stats.data.polarityGuardEnabled ? 'on' : 'off'}/
              {stats.data.entityGuardEnabled ? 'on' : 'off'}.
            </dd>
          </div>
        </dl>
      )}
      <div className="space-y-1">
        <div className="flex flex-wrap items-baseline gap-2">
          <h2 className="text-base font-semibold">Budgets</h2>
          <span className="flex-1" />
          {budgets.data === undefined || budgets.data.budgets.length === 0 ? null : (
            <button
              type="button"
              onClick={() => {
                setBudgetsCopyError(null)
                const clip = navigator.clipboard as Clipboard | undefined
                if (clip === undefined) {
                  setBudgetsCopyError('Copy unavailable in this browser.')
                  return
                }
                const rows = budgets.data.budgets.map(
                  (b) =>
                    `| ${b.subjectId} | ${b.level} | ${String(b.minuteMicros)} | ${String(b.monthMicros)} |`,
                )
                const doc = `# Spend budgets\n\n| Subject | Level | Minute (µ$) | Month (µ$) |\n| --- | --- | --- | --- |\n${rows.join('\n')}`
                void clip.writeText(doc).then(
                  () => {
                    setBudgetsCopied(true)
                  },
                  () => {
                    setBudgetsCopyError('Copy failed. Select the text manually.')
                  },
                )
              }}
              className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
            >
              {budgetsCopied ? 'Copied' : 'Copy markdown'}
            </button>
          )}
        </div>
        {budgetsCopyError === null ? null : (
          <p role="alert" className="text-[13px] text-danger dark:text-danger-soft">
            {budgetsCopyError}
          </p>
        )}
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          Caps in micro dollars per minute and per month. Zero means no cap. Breaches alert through
          the webhook.
        </p>
      </div>
      {budgets.isPending ? (
        <p role="status" className="text-sm">
          Loading budgets…
        </p>
      ) : budgets.error instanceof Error ? (
        <p role="alert" className="text-sm text-danger dark:text-danger-soft">
          {budgets.error.message}
        </p>
      ) : budgets.data === undefined || budgets.data.budgets.length === 0 ? (
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          No budgets yet. Create the first budget below.
        </p>
      ) : (
        <table className="w-full text-left text-sm">
          <caption className="sr-only">Spend budgets</caption>
          <thead className="sticky top-0 bg-paper dark:bg-night">
            <tr className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
              <th scope="col" className="py-2 pr-3 font-medium">
                Subject
              </th>
              <th scope="col" className="py-2 pr-3 font-medium">
                Level
              </th>
              <th scope="col" className="py-2 pr-3 text-right font-medium">
                Minute (µ$)
              </th>
              <th scope="col" className="py-2 text-right font-medium">
                Month (µ$)
              </th>
            </tr>
          </thead>
          <tbody>
            {budgets.data.budgets.map((b) => (
              <tr key={b.id} className="border-t border-ink/10 dark:border-parchment/10">
                <td className="py-2 pr-3 font-mono text-[13px]">{b.subjectId}</td>
                <td className="py-2 pr-3 text-[13px]">{b.level}</td>
                <td className="py-2 pr-3 text-right text-[13px] tnum">
                  {b.minuteMicros === 0 ? (
                    <span className="text-ink-soft dark:text-parchment-soft">no cap</span>
                  ) : (
                    formatMicros(b.minuteMicros)
                  )}
                </td>
                <td className="py-2 text-right text-[13px] tnum">
                  {b.monthMicros === 0 ? (
                    <span className="text-ink-soft dark:text-parchment-soft">no cap</span>
                  ) : (
                    formatMicros(b.monthMicros)
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <form
        onSubmit={(e) => {
          void handleSubmit(onCreate)(e)
        }}
        className="grid gap-3 rounded-lg border border-ink/10 bg-cream p-4 sm:grid-cols-2 dark:border-parchment/10 dark:bg-transparent"
      >
        <div>
          <label htmlFor="budget-level" className="mb-1 block text-[13px] font-medium">
            Level
          </label>
          <input
            id="budget-level"
            {...register('level')}
            placeholder="KEY, TEAM, or ORG"
            className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm dark:border-parchment/15"
          />
          {errors.level === undefined ? null : (
            <p role="alert" className="mt-1 text-[13px] text-danger dark:text-danger-soft">
              {errors.level.message}
            </p>
          )}
        </div>
        <div>
          <label htmlFor="budget-subject" className="mb-1 block text-[13px] font-medium">
            Subject
          </label>
          <input
            id="budget-subject"
            {...register('subjectId')}
            placeholder="Key hex, owner, or scope"
            className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm dark:border-parchment/15"
          />
          {errors.subjectId === undefined ? null : (
            <p role="alert" className="mt-1 text-[13px] text-danger dark:text-danger-soft">
              {errors.subjectId.message}
            </p>
          )}
        </div>
        <div>
          <label htmlFor="budget-minute" className="mb-1 block text-[13px] font-medium">
            Minute cap (µ$, 0 means none)
          </label>
          <input
            id="budget-minute"
            type="number"
            {...register('minuteMicros', { valueAsNumber: true })}
            className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm tnum dark:border-parchment/15"
          />
          {errors.minuteMicros === undefined ? null : (
            <p role="alert" className="mt-1 text-[13px] text-danger dark:text-danger-soft">
              {errors.minuteMicros.message}
            </p>
          )}
        </div>
        <div>
          <label htmlFor="budget-month" className="mb-1 block text-[13px] font-medium">
            Month cap (µ$, 0 means none)
          </label>
          <input
            id="budget-month"
            type="number"
            {...register('monthMicros', { valueAsNumber: true })}
            className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm tnum dark:border-parchment/15"
          />
          {errors.monthMicros === undefined ? null : (
            <p role="alert" className="mt-1 text-[13px] text-danger dark:text-danger-soft">
              {errors.monthMicros.message}
            </p>
          )}
        </div>
        <div>
          <label htmlFor="budget-webhook" className="mb-1 block text-[13px] font-medium">
            Webhook URL (optional)
          </label>
          <input
            id="budget-webhook"
            type="url"
            {...register('webhookUrl')}
            placeholder="https://ops.example.com/hook"
            className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm dark:border-parchment/15"
          />
          {errors.webhookUrl === undefined ? null : (
            <p role="alert" className="mt-1 text-[13px] text-danger dark:text-danger-soft">
              {errors.webhookUrl.message}
            </p>
          )}
        </div>
        <div className="sm:col-span-2">
          <button
            type="submit"
            disabled={isSubmitting}
            className="rounded-md bg-ink px-4 py-2 text-sm font-medium text-paper disabled:cursor-not-allowed disabled:bg-ink-soft disabled:text-paper dark:bg-parchment dark:text-night dark:disabled:bg-parchment-soft dark:disabled:text-night"
          >
            Create budget
          </button>
        </div>
      </form>
    </div>
  )
}
