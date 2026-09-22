import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { GatewayClient } from '../../shared/api/client.js'
import { toErrorMessage } from '../../shared/api/client.js'
import { useToastStore } from '../../shared/toast/store.js'
import { formatShortDate } from '../../shared/utils/format.js'

/**
 * Human-in-the-loop approval queue for gated MCP tool calls.
 *
 * @remarks Proof-type: live (real `/v1/admin/mcp/approvals/*`).
 *
 * @returns The approvals screen.
 */
export function ApprovalsPage(): React.JSX.Element {
  return (
    <div className="space-y-4">
      <h1 className="font-display text-2xl font-medium tracking-tight">Approvals</h1>
      <ApprovalsBoard />
    </div>
  )
}

/**
 * Pending gated tool calls with approve/reject decisions.
 *
 * @remarks Behind the admin route guard; the session Bearer attaches
 * automatically, so no credential prop is needed.
 *
 * @returns The approvals board.
 */
function ApprovalsBoard(): React.JSX.Element {
  const qc = useQueryClient()
  const pushToast = useToastStore((s) => s.push)
  const [busy, setBusy] = useState<ReadonlySet<string>>(new Set())

  const pending = useQuery({
    queryKey: ['hitl-pending'],
    queryFn: ({ signal }) => new GatewayClient().hitlPending({ signal }),
    refetchInterval: 10_000,
  })

  const decide = async (approvalId: string, approved: boolean): Promise<void> => {
    setBusy((prev) => new Set(prev).add(approvalId))
    try {
      await new GatewayClient().decideHitl(approvalId, approved, 'console-operator')
      const at = new Date().toISOString().slice(11, 19)
      pushToast('success', `${approvalId}: ${approved ? 'approved' : 'rejected'} · ${at} UTC.`)
      await qc.invalidateQueries({ queryKey: ['hitl-pending'] })
    } catch (e) {
      pushToast('error', `${approvalId}: ${toErrorMessage(e, 'Decision failed.')}`)
    } finally {
      setBusy((prev) => {
        const next = new Set(prev)
        next.delete(approvalId)
        return next
      })
    }
  }

  return (
    <div className="space-y-4">
      {pending.isPending ? (
        <p role="status" className="text-sm">
          Loading pending approvals…
        </p>
      ) : pending.error instanceof Error ? (
        <p role="alert" className="text-sm text-danger dark:text-danger-soft">
          {pending.error.message}
        </p>
      ) : pending.data === undefined || pending.data.approvals.length === 0 ? (
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          Queue is empty. Gated tool calls will appear here for review.
        </p>
      ) : (
        <ul className="space-y-2">
          {pending.data.approvals.map((a) => {
            const working = busy.has(a.approvalId)
            return (
              <li
                key={a.approvalId}
                className="flex flex-wrap items-center gap-3 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent"
              >
                <div className="min-w-0 flex-1">
                  <p className="font-mono text-[13px]">{a.toolName}</p>
                  <p
                    className="text-[13px] text-ink-soft tnum dark:text-parchment-soft"
                    title={a.requestedAt}
                  >
                    {a.approvalId} · {formatShortDate(a.requestedAt)}
                  </p>
                </div>
                <button
                  type="button"
                  disabled={working}
                  aria-busy={working}
                  onClick={() => {
                    void decide(a.approvalId, true)
                  }}
                  className="rounded-md bg-success px-3 py-2 text-[13px] text-white disabled:cursor-not-allowed disabled:bg-ink-soft disabled:text-paper dark:disabled:bg-parchment-soft dark:disabled:text-night"
                >
                  {working ? 'Working…' : 'Approve'}
                </button>
                <button
                  type="button"
                  disabled={working}
                  aria-busy={working}
                  onClick={() => {
                    void decide(a.approvalId, false)
                  }}
                  className="rounded-md border border-danger/40 px-3 py-2 text-[13px] text-danger disabled:cursor-not-allowed disabled:border-ink-soft disabled:text-ink-soft dark:text-danger-soft dark:disabled:border-parchment-soft dark:disabled:text-parchment-soft"
                >
                  {working ? 'Working…' : 'Reject'}
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
