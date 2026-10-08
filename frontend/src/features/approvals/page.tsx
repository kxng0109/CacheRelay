import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { GatewayClient } from '../../shared/api/client.js'
import { toErrorMessage } from '../../shared/api/client.js'
import { AdminUnavailable, isStealth404 } from '../../shared/components/AdminUnavailable.js'
import { useAuthStore } from '../../shared/auth/store.js'
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
 * automatically, so no credential prop is needed. Identity is the path
 * token id (never a separate approval id). Reviewers inspect decrypted
 * tool args before deciding, may attach a rationale, and the server
 * `{ status, tokenId, message }` receipt drives the toast.
 *
 * @returns The approvals board.
 */
function ApprovalsBoard(): React.JSX.Element {
  const qc = useQueryClient()
  const pushToast = useToastStore((s) => s.push)
  const username = useAuthStore((s) => s.session?.username ?? '')
  const [busy, setBusy] = useState<ReadonlySet<string>>(new Set())
  const [expanded, setExpanded] = useState<string | null>(null)
  const [reasons, setReasons] = useState<Record<string, string>>({})

  const pending = useQuery({
    queryKey: ['hitl-pending'],
    queryFn: ({ signal }) => new GatewayClient().hitlPending({ signal }),
    refetchInterval: 10_000,
  })

  const decide = async (tokenId: string, approved: boolean): Promise<void> => {
    setBusy((prev) => new Set(prev).add(tokenId))
    try {
      const reason = reasons[tokenId]?.trim()
      const receipt = await new GatewayClient().decideHitl(tokenId, approved, {
        ...(reason === undefined || reason.length === 0 ? {} : { reason }),
        ...(username.length === 0 ? {} : { decidedBy: username }),
      })
      pushToast('success', `${receipt.tokenId}: ${receipt.message}`)
      await qc.invalidateQueries({ queryKey: ['hitl-pending'] })
    } catch (e) {
      pushToast('error', `${tokenId}: ${toErrorMessage(e, 'Decision failed.')}`)
    } finally {
      setBusy((prev) => {
        const next = new Set(prev)
        next.delete(tokenId)
        return next
      })
    }
  }

  return (
    <div className="space-y-4">
      <p className="text-[13px] text-ink-soft dark:text-parchment-soft">
        Approvals hold 5 minutes. Rejections stand for 24 hours and repeat rejects stay silent.
      </p>
      {pending.isPending ? (
        <p role="status" className="text-sm">
          Loading pending approvals…
        </p>
      ) : pending.error instanceof Error ? (
        isStealth404(pending.error) ? (
          <AdminUnavailable path="/v1/admin/mcp/approvals/pending" status={404} />
        ) : (
          <p role="alert" className="text-sm text-danger dark:text-danger-soft">
            {pending.error.message}
          </p>
        )
      ) : pending.data === undefined || pending.data.approvals.length === 0 ? (
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          Queue is empty. Gated tool calls will appear here for review.
        </p>
      ) : (
        <ul className="space-y-2">
          {pending.data.approvals.map((a) => {
            const working = busy.has(a.tokenId)
            const open = expanded === a.tokenId
            return (
              <li
                key={a.tokenId}
                className="space-y-2 rounded-lg border border-ink/10 bg-cream p-3 dark:border-parchment/10 dark:bg-transparent"
              >
                <div className="flex flex-wrap items-center gap-3">
                  <div className="min-w-0 flex-1">
                    <p className="font-mono text-[13px]">{a.toolName}</p>
                    <p
                      className="text-[13px] text-ink-soft tnum dark:text-parchment-soft"
                      title={a.createdAt}
                    >
                      {a.serverName} · {a.keyName} · expires {formatShortDate(a.expiresAt)}
                    </p>
                    <p
                      className="truncate font-mono text-xs text-ink-soft dark:text-parchment-soft"
                      title={a.tokenId}
                    >
                      {a.tokenId}
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={() => {
                      setExpanded(open ? null : a.tokenId)
                    }}
                    aria-expanded={open}
                    className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
                  >
                    {open ? 'Hide args' : 'Inspect args'}
                  </button>
                  <button
                    type="button"
                    disabled={working}
                    aria-busy={working}
                    onClick={() => {
                      void decide(a.tokenId, true)
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
                      void decide(a.tokenId, false)
                    }}
                    className="rounded-md border border-danger/40 px-3 py-2 text-[13px] text-danger disabled:cursor-not-allowed disabled:border-ink-soft disabled:text-ink-soft dark:text-danger-soft dark:disabled:border-parchment-soft dark:disabled:text-parchment-soft"
                  >
                    {working ? 'Working…' : 'Reject'}
                  </button>
                </div>
                <div>
                  <label
                    htmlFor={`approve-reason-${a.tokenId}`}
                    className="mb-1 block text-[13px] font-medium"
                  >
                    Rationale (optional, stored with the decision)
                  </label>
                  <input
                    id={`approve-reason-${a.tokenId}`}
                    value={reasons[a.tokenId] ?? ''}
                    autoComplete="off"
                    onChange={(e) => {
                      const value = e.target.value
                      setReasons((prev) => ({ ...prev, [a.tokenId]: value }))
                    }}
                    placeholder="Looks safe"
                    className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm dark:border-parchment/15"
                  />
                </div>
                {open ? <ApprovalArgs tokenId={a.tokenId} /> : null}
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}

/**
 * Decrypted tool arguments for one pending approval.
 *
 * @remarks Sealed values arrive decrypted for review; undecryptable
 * values render as a placeholder — never a throw, never a blank.
 *
 * @param props - Approval token id to hydrate.
 * @returns The args viewer.
 */
function ApprovalArgs({ tokenId }: { tokenId: string }): React.JSX.Element {
  const detail = useQuery({
    queryKey: ['hitl-detail', tokenId],
    queryFn: ({ signal }) => new GatewayClient().hitlDetail(tokenId, { signal }),
    retry: false,
  })
  if (detail.isPending) {
    return (
      <p role="status" className="text-[13px]">
        Loading invocation args…
      </p>
    )
  }
  if (detail.error instanceof Error) {
    return (
      <p role="alert" className="text-[13px] text-danger dark:text-danger-soft">
        {detail.error.message}
      </p>
    )
  }
  const sealed = JSON.stringify(detail.data ?? null).includes('***undecryptable***')
  return (
    <>
      {sealed ? (
        <p role="alert" className="text-[13px] text-danger dark:text-danger-soft">
          Args are undecryptable. Do not approve without out-of-band verification.
        </p>
      ) : null}
      <pre className="overflow-x-auto rounded-md bg-ink/4 p-3 font-mono text-xs whitespace-pre-wrap dark:bg-parchment/6">
        {JSON.stringify(detail.data ?? 'No args returned.', null, 2)}
      </pre>
    </>
  )
}
