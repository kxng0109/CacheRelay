import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { GatewayClient } from '../../shared/api/client.js'
import { toErrorMessage } from '../../shared/api/client.js'
import { AdminUnavailable, isStealth404 } from '../../shared/components/AdminUnavailable.js'
import { EmptyTrio } from '../../shared/components/EmptyTrio.js'
import { TableScroll } from '../../shared/components/TableScroll.js'

/**
 * Self-service account keys: default act-as key plus own-key revoke.
 *
 * @remarks Proof-type: live (real `/v1/me/keys/*` reads and writes).
 * Unknown-or-foreign hashes answer 404 with no oracle; revocation is a
 * terminal tombstone with no undo path. The 401 copy names the session
 * (these routes never take a gateway key).
 *
 * @returns The account screen.
 */
export function AccountPage(): React.JSX.Element {
  const qc = useQueryClient()
  const [confirming, setConfirming] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [problem, setProblem] = useState<string | null>(null)

  const keys = useQuery({
    queryKey: ['my-keys'],
    queryFn: ({ signal }) => new GatewayClient().myKeys({ signal }),
    retry: false,
  })

  const refresh = (): Promise<unknown> => qc.invalidateQueries({ queryKey: ['my-keys'] })

  const onDefault = async (keyId: string): Promise<void> => {
    setNotice(null)
    setProblem(null)
    try {
      await new GatewayClient().setDefaultKey(keyId)
      setNotice('Default key updated.')
      await refresh()
    } catch (e) {
      setProblem(toErrorMessage(e, 'Default key update failed.'))
    }
  }

  const onRevoke = async (keyId: string): Promise<void> => {
    setNotice(null)
    setProblem(null)
    try {
      await new GatewayClient().revokeOwnKey(keyId)
      setConfirming(null)
      setNotice('Key revoked. This cannot be undone.')
      await refresh()
    } catch (e) {
      setProblem(toErrorMessage(e, 'Key revocation failed.'))
    }
  }

  return (
    <div className="space-y-4">
      <div className="space-y-1">
        <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
          <span aria-hidden="true" className="mr-1 text-ember">
            ❯
          </span>
          account
        </p>
        <h1 className="font-display text-3xl font-medium tracking-tight">Account</h1>
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          Your owned keys only. Set the default act-as key or tombstone a compromised one.
        </p>
      </div>
      {notice === null ? null : (
        <p role="status" className="text-[13px]">
          {notice}
        </p>
      )}
      {problem === null ? null : (
        <p role="alert" className="text-[13px] text-danger dark:text-danger-soft">
          {problem}
        </p>
      )}
      {keys.isPending ? (
        <p role="status" className="text-sm">
          Loading owned keys…
        </p>
      ) : keys.error instanceof Error ? (
        isStealth404(keys.error) ? (
          <AdminUnavailable path="/v1/me/keys" status={404} />
        ) : (
          <p role="alert" className="text-sm text-danger dark:text-danger-soft">
            {keys.error.message}
          </p>
        )
      ) : keys.data === undefined || keys.data.keys.length === 0 ? (
        <EmptyTrio
          title="No owned keys"
          cue="Ask an admin for a key, or paste one on the Playground screen."
          action={{ label: 'Open playground', to: '/playground' }}
        />
      ) : (
        <TableScroll>
          <table className="w-full text-left text-sm">
            <caption className="sr-only">Owned API keys</caption>
            <thead className="sticky top-0 bg-paper dark:bg-night">
              <tr className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
                <th scope="col" className="py-2 pr-3 font-medium">
                  Name
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
              {keys.data.keys.map((k) => (
                <tr key={k.keyId} className="border-t border-ink/10 dark:border-parchment/10">
                  <td className="py-2 pr-3 font-mono text-[13px]">{k.name}</td>
                  <td className="py-2 pr-3 text-[13px]">
                    {k.allowedModels.length === 0 ? 'all' : k.allowedModels.join(', ')}
                  </td>
                  <td className="py-2 pr-3 text-[13px]">{k.enabled ? 'enabled' : 'disabled'}</td>
                  <td className="py-2 text-right">
                    <span className="inline-flex gap-2">
                      <button
                        type="button"
                        onClick={() => {
                          void onDefault(k.keyId)
                        }}
                        aria-label={`Use ${k.name} as default`}
                        className="rounded-md border border-ink/15 px-2 py-1 text-[13px] dark:border-parchment/15"
                      >
                        Default
                      </button>
                      {confirming === k.keyId ? (
                        <span className="inline-flex items-center gap-2 text-[13px]">
                          Revoke “{k.name}” forever?
                          <button
                            type="button"
                            onClick={() => {
                              void onRevoke(k.keyId)
                            }}
                            className="rounded-md border border-danger/40 px-2 py-1 text-danger dark:text-danger-soft"
                          >
                            Yes, revoke
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
                            setConfirming(k.keyId)
                          }}
                          aria-label={`Revoke ${k.name}`}
                          className="rounded-md border border-ink/15 px-2 py-1 text-[13px] dark:border-parchment/15"
                        >
                          Revoke
                        </button>
                      )}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </TableScroll>
      )}
    </div>
  )
}
