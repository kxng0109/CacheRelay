import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useShallow } from 'zustand/react/shallow'
import { GatewayClient } from '../../shared/api/client.js'
import { useAuthStore } from '../../shared/auth/store.js'

/**
 * Human-in-the-loop approval queue for gated MCP tool calls.
 *
 * @remarks Proof-type: live (real `/v1/admin/mcp/approvals/*`).
 *
 * @returns The approvals screen.
 */
export function ApprovalsPage(): React.JSX.Element {
  const { adminKey } = useAuthStore(useShallow((s) => ({ adminKey: s.adminKey })))
  const qc = useQueryClient()
  const [notice, setNotice] = useState<string | null>(null)

  const pending = useQuery({
    queryKey: ['hitl-pending', adminKey !== null],
    queryFn: ({ signal }) =>
      new GatewayClient({ token: adminKey ?? '', adminKey }).hitlPending({ signal }),
    enabled: adminKey !== null,
    refetchInterval: 10_000,
  })

  if (adminKey === null)
    return <p className="text-sm">Unlock the admin key on the Circuits page first.</p>

  const decide = async (approvalId: string, approved: boolean): Promise<void> => {
    setNotice(null)
    try {
      await new GatewayClient({ token: adminKey, adminKey }).decideHitl(
        approvalId,
        approved,
        'console-operator',
      )
      setNotice(`${approvalId}: ${approved ? 'approved' : 'rejected'}.`)
      await qc.invalidateQueries({ queryKey: ['hitl-pending'] })
    } catch (e) {
      setNotice(e instanceof Error ? e.message : 'Decision failed.')
    }
  }

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold tracking-tight">Approvals</h1>
      {notice === null ? null : (
        <p role="status" className="text-xs">
          {notice}
        </p>
      )}
      {pending.isPending ? (
        <p role="status" className="text-sm">
          Loading pending approvals…
        </p>
      ) : pending.error instanceof Error ? (
        <p role="alert" className="text-sm text-danger">
          {pending.error.message}
        </p>
      ) : pending.data === undefined || pending.data.approvals.length === 0 ? (
        <p className="text-sm opacity-70">
          Queue is empty. Gated tool calls will appear here for review.
        </p>
      ) : (
        <ul className="space-y-2">
          {pending.data.approvals.map((a) => (
            <li
              key={a.approvalId}
              className="flex flex-wrap items-center gap-3 rounded-lg border border-ink/10 p-3 dark:border-parchment/10"
            >
              <div className="min-w-0 flex-1">
                <p className="font-mono text-xs">{a.toolName}</p>
                <p className="text-xs tnum opacity-60">
                  {a.approvalId} · {a.requestedAt}
                </p>
              </div>
              <button
                type="button"
                onClick={() => {
                  void decide(a.approvalId, true)
                }}
                className="rounded-md bg-success px-3 py-2 text-xs text-white"
              >
                Approve
              </button>
              <button
                type="button"
                onClick={() => {
                  void decide(a.approvalId, false)
                }}
                className="rounded-md border border-danger/40 px-3 py-2 text-xs text-danger"
              >
                Reject
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
