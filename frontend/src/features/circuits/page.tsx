import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useShallow } from 'zustand/react/shallow'
import { GatewayClient } from '../../shared/api/client.js'
import { resolveApiBase } from '../../shared/api/client.js'
import { toErrorMessage } from '../../shared/api/client.js'
import { useAuthStore } from '../../shared/auth/store.js'

const STATE_META: Record<string, { badge: string; dark: string; label: string }> = {
  CLOSED: {
    badge: 'bg-success/15 text-success',
    dark: 'dark:text-success-soft',
    label: '● Closed',
  },
  OPEN: { badge: 'bg-danger/15 text-danger', dark: 'dark:text-danger-soft', label: '■ Open' },
  HALF_OPEN: { badge: 'bg-warn/15 text-warn', dark: 'dark:text-warn-soft', label: '▲ Half-open' },
}

/**
 * Resolves display metadata for a circuit state, including unknown states a
 * newer backend may introduce.
 *
 * @param state - Raw state string from the gateway.
 * @returns Badge classes (plus dark-mode text class) and an icon+text label;
 * unknown states get an explicit Unknown badge instead of being mislabeled.
 */
function stateMeta(state: string): { badge: string; dark: string; label: string } {
  return STATE_META[state] ?? { badge: '', dark: '', label: '? Unknown' }
}

interface CircuitsBoardProps {
  /** Master admin key; the gate guarantees non-null before mounting. */
  adminKey: string
}

/**
 * Live provider-state board plus force-reset.
 *
 * @remarks Proof-type: live (polls real `/v1/admin/circuits/state`).
 *
 * @param props - The admin key for admin-surface calls.
 * @returns The circuits board.
 */
function CircuitsBoard({ adminKey }: CircuitsBoardProps): React.JSX.Element {
  const [notice, setNotice] = useState<string | null>(null)
  const qc = useQueryClient()

  const query = useQuery({
    queryKey: ['circuits'],
    queryFn: ({ signal }) =>
      new GatewayClient({ token: adminKey, adminKey }).circuitState({ signal }),
    refetchInterval: 5000,
  })

  const reset = async (provider: string): Promise<void> => {
    setNotice(null)
    try {
      const out = await new GatewayClient({ token: adminKey, adminKey }).resetCircuit(provider)
      setNotice(`${out.provider}: ${out.state}`)
      await qc.invalidateQueries({ queryKey: ['circuits'] })
    } catch (e) {
      setNotice(toErrorMessage(e, 'Reset failed.'))
    }
  }

  return (
    <div className="space-y-4">
      {notice === null ? null : (
        <p role="status" className="text-xs">
          {notice}
        </p>
      )}
      {query.isPending ? (
        <p role="status" className="text-sm">
          Loading circuit state…
        </p>
      ) : null}
      {query.error instanceof Error ? (
        <p role="alert" className="text-sm text-danger dark:text-danger-soft">
          {query.error.message}
        </p>
      ) : null}
      {query.data === undefined || query.data.circuits.length === 0 ? (
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          No providers reported. Configure providers in the backend to populate this board.
        </p>
      ) : (
        <table className="w-full text-left text-sm">
          <caption className="sr-only">Provider circuit states</caption>
          <thead>
            <tr>
              <th scope="col">Provider</th>
              <th scope="col">State</th>
              <th scope="col">Last transition</th>
              <th scope="col">
                <span className="sr-only">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {query.data.circuits.map((c) => {
              const meta = stateMeta(c.state)
              return (
                <tr key={c.provider} className="border-t border-ink/10 dark:border-parchment/10">
                  <td className="py-2 font-mono">{c.provider}</td>
                  <td className="py-2">
                    <span className={`rounded px-2 py-1 text-xs tnum ${meta.badge} ${meta.dark}`}>
                      {meta.label}
                    </span>
                  </td>
                  <td className="py-2 text-xs tnum">{c.lastTransitionAt ?? '—'}</td>
                  <td className="py-2 text-right">
                    <button
                      type="button"
                      onClick={() => {
                        void reset(c.provider)
                      }}
                      className="rounded-md border border-ink/15 px-3 py-2 text-xs dark:border-parchment/15"
                    >
                      Reset circuit
                    </button>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}
      <p className="text-xs text-ink-soft dark:text-parchment-soft">
        Base: {resolveApiBase() === '' ? 'same-origin' : resolveApiBase()}
      </p>
    </div>
  )
}

/**
 * Circuit breaker screen: admin-key gate plus the live board.
 *
 * @returns The circuits screen.
 */
export function CircuitsPage(): React.JSX.Element {
  const { adminKey, setAdminKey } = useAuthStore(
    useShallow((s) => ({ adminKey: s.adminKey, setAdminKey: s.setAdminKey })),
  )
  const [keyInput, setKeyInput] = useState(adminKey ?? '')

  if (adminKey === null) {
    return (
      <div className="space-y-4">
        <h1 className="text-xl font-semibold tracking-tight">Circuits</h1>
        <form
          onSubmit={(e) => {
            e.preventDefault()
            setAdminKey(keyInput.length > 0 ? keyInput : null)
          }}
          className="space-y-2 rounded-lg border border-ink/10 p-4 dark:border-parchment/10"
        >
          <label htmlFor="admin-key" className="block text-xs font-medium">
            Master admin key (memory only)
          </label>
          <input
            id="admin-key"
            type="password"
            autoComplete="off"
            value={keyInput}
            onChange={(e) => {
              setKeyInput(e.target.value)
            }}
            className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 text-sm dark:border-parchment/15"
          />
          <button
            type="submit"
            className="rounded-md bg-ink px-4 py-2 text-sm text-paper dark:bg-parchment dark:text-night"
          >
            Unlock circuits
          </button>
        </form>
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold tracking-tight">Circuits</h1>
      <CircuitsBoard adminKey={adminKey} />
    </div>
  )
}
