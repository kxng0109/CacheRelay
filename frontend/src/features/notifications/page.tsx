import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { GatewayClient } from '../../shared/api/client.js'
import { toErrorMessage } from '../../shared/api/client.js'
import { AdminUnavailable, isStealth404 } from '../../shared/components/AdminUnavailable.js'
import { Select } from '../../shared/components/Select.js'
import { TableScroll } from '../../shared/components/TableScroll.js'

const CHANNELS = ['email', 'teams', 'slack', 'webhook'] as const

/**
 * Alert subscription administration: scope-scoped list, create, delete.
 *
 * @remarks Backend truth (`AdminNotificationController`): list requires
 * `scope` (missing answers 400, never "list all"); create answers 201;
 * delete answers 204 even for unknown ids (idempotent no-op, safe to
 * retry). Secrets travel by reference only: `secretRef` names an
 * environment variable, never a secret value.
 *
 * @returns The notifications board.
 */
function NotificationsBoard(): React.JSX.Element {
  const qc = useQueryClient()
  const [scope, setScope] = useState('')
  const [appliedScope, setAppliedScope] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [target, setTarget] = useState('')
  const [channel, setChannel] = useState<string>('webhook')
  const [secretRef, setSecretRef] = useState('')
  const [severity, setSeverity] = useState('warning')
  const [confirming, setConfirming] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const query = useQuery({
    queryKey: ['notifications', appliedScope],
    queryFn: ({ signal }) => new GatewayClient().listNotifications(appliedScope ?? '', { signal }),
    enabled: appliedScope !== null,
    retry: false,
  })

  const refresh = (): void => {
    void qc.invalidateQueries({ queryKey: ['notifications', appliedScope] })
  }

  const onCreate = (): void => {
    setError(null)
    setNotice(null)
    const client = new GatewayClient()
    void client
      .createNotification({
        scope: (appliedScope ?? '').trim(),
        channel,
        target: target.trim(),
        secretRef: secretRef.trim().length === 0 ? null : secretRef.trim(),
        minSeverity: severity,
      })
      .then(() => {
        setNotice('Subscription created.')
        setCreating(false)
        setTarget('')
        setSecretRef('')
        refresh()
      })
      .catch((e: unknown) => {
        setError(toErrorMessage(e, 'Subscription creation failed.'))
      })
  }

  const onDelete = (id: string): void => {
    setError(null)
    setNotice(null)
    const deleter = new GatewayClient()
    void deleter
      .deleteNotification(id)
      .then(() => {
        setNotice('Subscription deleted.')
        setConfirming(null)
        refresh()
      })
      .catch((e: unknown) => {
        setError(toErrorMessage(e, 'Subscription deletion failed.'))
      })
  }

  const rows = query.data?.notifications ?? []

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <label htmlFor="notif-scope" className="sr-only">
          Alert scope
        </label>
        <input
          id="notif-scope"
          type="search"
          value={scope}
          placeholder="Scope (e.g. budgets)"
          onChange={(e) => {
            setScope(e.target.value)
          }}
          className="w-48 rounded-md border border-ink/15 bg-transparent px-3 py-2 text-[13px] dark:border-parchment/15"
        />
        <button
          type="button"
          onClick={() => {
            setAppliedScope(scope.trim())
          }}
          disabled={scope.trim().length === 0}
          className="rounded-md border border-ink/15 px-3 py-2 text-[13px] disabled:cursor-not-allowed dark:border-parchment/15"
        >
          List
        </button>
        <span className="flex-1" />
        {appliedScope === null ? null : (
          <button
            type="button"
            onClick={() => {
              setError(null)
              setCreating(true)
            }}
            className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
          >
            New subscription
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
      {appliedScope === null ? (
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          Enter a scope to list subscriptions.
        </p>
      ) : query.isPending ? (
        <p role="status" className="text-sm">
          Loading subscriptions…
        </p>
      ) : query.error instanceof Error ? (
        isStealth404(query.error) ? (
          <AdminUnavailable path="/v1/admin/notifications" status={404} />
        ) : (
          <p role="alert" className="text-sm text-danger dark:text-danger-soft">
            {query.error.message}
          </p>
        )
      ) : rows.length === 0 ? (
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          No subscriptions for scope “{appliedScope}”.
        </p>
      ) : (
        <TableScroll>
          <table className="w-full text-left text-sm">
            <caption className="sr-only">Alert subscriptions</caption>
            <thead className="sticky top-0 bg-paper dark:bg-night">
              <tr className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
                <th scope="col" className="py-2 pr-3 font-medium">
                  Target
                </th>
                <th scope="col" className="py-2 pr-3 font-medium">
                  Channel
                </th>
                <th scope="col" className="py-2 pr-3 font-medium">
                  Min severity
                </th>
                <th scope="col" className="py-2 pr-3 font-medium">
                  Secret ref
                </th>
                <th scope="col" className="py-2 text-right font-medium">
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((n) => (
                <tr key={n.id} className="border-t border-ink/10 dark:border-parchment/10">
                  <td
                    className="max-w-64 truncate py-2 pr-3 font-mono text-[13px]"
                    title={n.target}
                  >
                    {n.target}
                  </td>
                  <td className="py-2 pr-3 text-[13px]">{n.channel}</td>
                  <td className="py-2 pr-3 text-[13px]">{n.minSeverity}</td>
                  <td className="py-2 pr-3 font-mono text-[13px]">{n.secretRef ?? '—'}</td>
                  <td className="py-2 text-right">
                    {confirming === n.id ? (
                      <span className="inline-flex items-center gap-2 text-[13px]">
                        Delete?
                        <button
                          type="button"
                          onClick={() => {
                            onDelete(n.id)
                          }}
                          className="rounded-md border border-danger/40 px-2 py-1 text-danger dark:text-danger-soft"
                        >
                          Yes
                        </button>
                        <button
                          type="button"
                          onClick={() => {
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
                        onClick={() => {
                          setConfirming(n.id)
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
        </TableScroll>
      )}
      {creating ? (
        <div className="space-y-2 rounded-lg border border-ink/10 p-3 dark:border-parchment/10">
          <div>
            <label htmlFor="notif-target" className="mb-1 block text-[13px] font-medium">
              Target
            </label>
            <input
              id="notif-target"
              value={target}
              autoComplete="off"
              onChange={(e) => {
                setTarget(e.target.value)
              }}
              placeholder="https://ops.example.com/hook or ops@example.com"
              className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-sm dark:border-parchment/15"
            />
          </div>
          <div className="grid gap-2 sm:grid-cols-3">
            <div>
              <Select
                id="notif-channel"
                label="Channel"
                value={channel}
                options={CHANNELS.map((c) => ({ value: c, label: c }))}
                onChange={setChannel}
              />
            </div>
            <div>
              <Select
                id="notif-severity"
                label="Min severity"
                value={severity}
                options={[
                  { value: 'warning', label: 'warning' },
                  { value: 'critical', label: 'critical' },
                ]}
                onChange={setSeverity}
              />
              <p className="mt-1 text-xs text-ink-soft dark:text-parchment-soft">
                Warning gets every alert. Critical gets critical alerts only.
              </p>
            </div>
            <div>
              <label htmlFor="notif-secret" className="mb-1 block text-[13px] font-medium">
                Secret ref (env name, optional)
              </label>
              <input
                id="notif-secret"
                value={secretRef}
                autoComplete="off"
                onChange={(e) => {
                  setSecretRef(e.target.value)
                }}
                placeholder="OPS_HOOK_SECRET"
                className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-sm dark:border-parchment/15"
              />
            </div>
          </div>
          <div className="flex gap-2">
            <button
              type="button"
              onClick={onCreate}
              disabled={target.trim().length === 0}
              className="rounded-md border border-ink/15 px-3 py-2 text-[13px] disabled:cursor-not-allowed dark:border-parchment/15"
            >
              Create
            </button>
            <button
              type="button"
              onClick={() => {
                setCreating(false)
              }}
              className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
            >
              Cancel
            </button>
          </div>
        </div>
      ) : null}
    </div>
  )
}

/**
 * Webhook probe: one test alert through the admin receiver.
 *
 * @remarks Backend truth (`AdminAlertWebhookController`): counted and
 * logged only, no human delivery. The `{ received }` receipt proves
 * the path is wired.
 *
 * @returns The probe section.
 */
function WebhookProbe(): React.JSX.Element {
  const [status, setStatus] = useState<string | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const probe = (): void => {
    setStatus(null)
    setProblem(null)
    setBusy(true)
    const client = new GatewayClient()
    void client
      .sendAlertProbe([
        { labels: { alertname: 'ConsoleProbe' }, annotations: { summary: 'Console test alert' } },
      ])
      .then((out) => {
        setStatus(`Webhook received ${String(out.received)} alert${out.received === 1 ? '' : 's'}.`)
      })
      .catch((e: unknown) => {
        setProblem(toErrorMessage(e, 'Webhook probe failed.'))
      })
      .finally(() => {
        setBusy(false)
      })
  }

  return (
    <div className="space-y-2">
      <h2 className="text-base font-semibold">Webhook probe</h2>
      <p className="text-sm text-ink-soft dark:text-parchment-soft">
        Sends one test alert. Counted and logged only — no human delivery.
      </p>
      <button
        type="button"
        disabled={busy}
        onClick={probe}
        className="rounded-md border border-ink/15 px-3 py-2 text-[13px] disabled:cursor-not-allowed dark:border-parchment/15"
      >
        Send test alert
      </button>
      {status === null ? null : (
        <p role="status" className="text-[13px]">
          {status}
        </p>
      )}
      {problem === null ? null : (
        <p role="alert" className="text-[13px] text-danger dark:text-danger-soft">
          {problem}
        </p>
      )}
    </div>
  )
}

/**
 * Alert subscription screen: scope-scoped list behind the admin guard.
 *
 * @returns The notifications screen.
 */
export function NotificationsPage(): React.JSX.Element {
  return (
    <div className="space-y-4">
      <div className="space-y-1">
        <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
          <span aria-hidden="true" className="mr-1 text-ember">
            ❯
          </span>
          guard
        </p>
        <h1 className="font-display text-3xl font-medium tracking-tight">Notifications</h1>
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          Alert subscriptions per scope. Secrets are referenced by environment name, never stored.
          Detection runs every 60 seconds with dispatch every 30 seconds. Expect about 90 seconds
          from trip to glass.
        </p>
      </div>
      <NotificationsBoard />
      <WebhookProbe />
    </div>
  )
}
