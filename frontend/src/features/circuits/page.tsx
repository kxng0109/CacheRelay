import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { GatewayClient, resolveApiBase } from '../../shared/api/client.js'
import { InspectorShell } from '../../shared/components/InspectorShell.js'
import { toErrorMessage } from '../../shared/api/client.js'

const STATE_META: Record<string, { badge: string; dark: string; label: string }> = {
  CLOSED: {
    badge: 'bg-success/15 text-success',
    dark: 'dark:text-success-soft',
    label: '● Closed',
  },
  OPEN: {
    badge: 'bg-danger/15 text-danger',
    dark: 'dark:text-danger-soft',
    label: '■ Open',
  },
  HALF_OPEN: {
    badge: 'bg-warn/15 text-warn',
    dark: 'dark:text-warn-soft',
    label: '▲ Half-open',
  },
}

/**
 * Resolves display metadata for a circuit state, including unknown states a
 * newer backend may introduce.
 *
 * @param state - Raw state string from the gateway.
 * @returns Badge classes (plus dark-mode text class) and an icon+text
 * label; unknown states get an explicit Unknown badge instead of being
 * mislabeled.
 */
function stateMeta(state: string): { badge: string; dark: string; label: string } {
  return STATE_META[state] ?? { badge: '', dark: '', label: '? Unknown' }
}

/**
 * Resolves the large switchboard word for a circuit state.
 *
 * @remarks Takes a plain string (not the `CircuitSnapshot` union) so a
 * backend state added tomorrow renders its own name instead of inheriting
 * a wrong word.
 *
 * @param state - Raw state string from the gateway.
 * @returns The display word for the panel readout.
 */
function stateWord(state: string): string {
  if (state === 'CLOSED') return 'Flowing'
  if (state === 'OPEN') return 'Tripped'
  if (state === 'HALF_OPEN') return 'Probing'
  return state
}

/**
 * One line consequence for a circuit state. Operators should know what
 * each state does to traffic without opening docs.
 *
 * @param state - Raw state string from the gateway.
 * @returns The consequence sentence.
 */
function stateConsequence(state: string): string {
  if (state === 'CLOSED') return 'Requests flow normally.'
  if (state === 'OPEN') return 'Requests fail fast until cooldown ends.'
  if (state === 'HALF_OPEN') return 'One trial request decides the next state.'
  return 'Unknown state. Treat traffic as suspect until confirmed.'
}

/**
 * Live provider-state board plus force-reset.
 *
 * @remarks Proof-type: live (polls real `/v1/admin/circuits`). The route
 * guard guarantees an admin session, so no credential prop is needed —
 * the client attaches the session Bearer automatically.
 *
 * @returns The circuits board.
 */
function CircuitsBoard(): React.JSX.Element {
  const [notice, setNotice] = useState<string | null>(null)
  const [filter, setFilter] = useState('')
  const [segment, setSegment] = useState<'all' | 'CLOSED' | 'OPEN' | 'HALF_OPEN' | 'unknown'>('all')
  const [selected, setSelected] = useState<string | null>(null)
  const qc = useQueryClient()

  const query = useQuery({
    queryKey: ['circuits'],
    queryFn: ({ signal }) => new GatewayClient().circuitState({ signal }),
    refetchInterval: 5000,
  })

  const reset = async (provider: string): Promise<void> => {
    setNotice(null)
    try {
      const out = await new GatewayClient().resetCircuit(provider)
      setNotice(`${out.provider}: ${out.state}`)
      await qc.invalidateQueries({ queryKey: ['circuits'] })
    } catch (e) {
      setNotice(toErrorMessage(e, 'Reset failed.'))
    }
  }

  const circuits = query.data?.circuits ?? []
  const queryText = filter.trim().toLowerCase()
  const visible = circuits.filter((c) => {
    // Widened to string: the gateway may introduce states newer than the
    // generated union, and those must land in `unknown`, never mislabeled.
    const state: string = c.state
    const dimension =
      state === 'CLOSED' || state === 'OPEN' || state === 'HALF_OPEN' ? state : 'unknown'
    if (segment !== 'all' && dimension !== segment) return false
    if (queryText.length === 0) return true
    return c.provider.toLowerCase().includes(queryText) || c.state.toLowerCase().includes(queryText)
  })
  const inspected = circuits.find((c) => c.provider === selected) ?? null
  const flowing = circuits.filter((c) => c.state === 'CLOSED').length
  const tripped = circuits.filter((c) => c.state === 'OPEN').length
  const probing = circuits.filter((c) => c.state === 'HALF_OPEN').length

  return (
    <div className="grid gap-6 lg:grid-cols-[1fr_280px]">
      <div className="space-y-4">
        <p className="font-mono text-xs text-ink-soft tnum dark:text-parchment-soft">
          {circuits.length} providers · {flowing} flowing · {tripped} tripped · {probing} probing ·
          refreshes every 5s
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <label htmlFor="circuit-filter" className="sr-only">
            Filter circuits
          </label>
          <input
            id="circuit-filter"
            type="search"
            value={filter}
            placeholder="Filter"
            onChange={(e) => {
              setFilter(e.target.value)
            }}
            className="w-48 rounded-md border border-ink/15 bg-transparent px-3 py-2 text-[13px] dark:border-parchment/15"
          />
          <div role="group" aria-label="State filter" className="flex gap-1">
            {(['all', 'CLOSED', 'OPEN', 'HALF_OPEN', 'unknown'] as const).map((s) => (
              <button
                key={s}
                type="button"
                aria-pressed={segment === s}
                onClick={() => {
                  setSegment(s)
                }}
                className={`rounded-md p-2 font-mono text-xs ${
                  segment === s
                    ? 'bg-ink text-paper dark:bg-parchment dark:text-night'
                    : 'text-ink-soft dark:text-parchment-soft'
                }`}
              >
                {s === 'all'
                  ? 'all'
                  : s === 'HALF_OPEN'
                    ? 'half'
                    : s === 'unknown'
                      ? '?'
                      : s.toLowerCase()}
              </button>
            ))}
          </div>
          <span className="flex-1" />
          <button
            type="button"
            onClick={() => {
              void qc.invalidateQueries({ queryKey: ['circuits'] })
            }}
            className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
          >
            Refresh
          </button>
        </div>
        {notice === null ? null : (
          <p role="status" className="text-[13px]">
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
        {!query.isPending && !(query.error instanceof Error) && visible.length === 0 ? (
          <p className="text-sm text-ink-soft dark:text-parchment-soft">
            {circuits.length === 0
              ? 'No providers reported. Configure providers in the backend to populate this board.'
              : 'No circuits match this filter.'}
          </p>
        ) : null}
        {visible.length === 0 ? null : (
          <table className="w-full text-left text-sm">
            <caption className="sr-only">Provider circuit states</caption>
            <thead className="sticky top-0 bg-paper dark:bg-night">
              <tr className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
                <th scope="col" className="py-2 pr-3 font-medium">
                  Provider
                </th>
                <th scope="col" className="py-2 pr-3 font-medium">
                  State
                </th>
                <th scope="col" className="py-2 pr-3 font-medium">
                  Signal
                </th>
                <th scope="col" className="py-2 pr-3 text-right font-medium">
                  Failures
                </th>
                <th scope="col" className="py-2 pr-3 text-right font-medium">
                  Cooldown (ms)
                </th>
                <th scope="col" className="py-2 text-right font-medium">
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {visible.map((c) => {
                const meta = stateMeta(c.state)
                const active = c.provider === selected
                return (
                  <tr
                    key={c.provider}
                    tabIndex={0}
                    aria-selected={active}
                    onClick={() => {
                      setSelected(active ? null : c.provider)
                    }}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault()
                        setSelected(active ? null : c.provider)
                      }
                    }}
                    className={`cursor-pointer border-t border-ink/10 dark:border-parchment/10 ${
                      active ? 'bg-ink/4 dark:bg-parchment/6' : ''
                    }`}
                  >
                    <td
                      className="max-w-44 truncate py-2 pr-3 font-mono text-[13px]"
                      title={c.provider}
                    >
                      {c.provider}
                    </td>
                    <td className="py-2 pr-3">
                      <span
                        className={`rounded px-2 py-1 text-[13px] tnum ${meta.badge} ${meta.dark}`}
                      >
                        {meta.label}
                      </span>
                    </td>
                    <td className="py-2 pr-3 font-display text-lg tnum">{stateWord(c.state)}</td>
                    <td className="py-2 pr-3 text-right text-[13px] tnum">{c.failures}</td>
                    <td className="py-2 pr-3 text-right text-[13px] tnum">
                      {c.cooldownMsRemaining}
                    </td>
                    <td className="py-2 text-right">
                      <button
                        type="button"
                        onClick={(e) => {
                          e.stopPropagation()
                          void reset(c.provider)
                        }}
                        className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
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
        <p className="text-[13px] text-ink-soft dark:text-parchment-soft">
          Base: {resolveApiBase() === '' ? 'same-origin' : resolveApiBase()}
        </p>
      </div>
      <aside aria-label="Circuit inspector" className="space-y-3">
        {inspected === null ? (
          <p className="text-[13px] text-ink-soft dark:text-parchment-soft">
            Select a row to inspect a circuit.
          </p>
        ) : (
          <InspectorShell
            title={inspected.provider}
            onClose={() => {
              setSelected(null)
            }}
          >
            <p className="font-display text-3xl font-medium tracking-tight tnum">
              {stateWord(inspected.state)}
            </p>
            <p className="text-[13px] text-ink-soft dark:text-parchment-soft">
              {stateConsequence(inspected.state)}
            </p>
            <dl className="space-y-2 text-[13px]">
              <div className="flex justify-between gap-3">
                <dt className="text-ink-soft dark:text-parchment-soft">State</dt>
                <dd className="tnum">{stateMeta(inspected.state).label}</dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-ink-soft dark:text-parchment-soft">Failures</dt>
                <dd className="tnum">{inspected.failures}</dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-ink-soft dark:text-parchment-soft">Cooldown (ms)</dt>
                <dd className="tnum">{inspected.cooldownMsRemaining}</dd>
              </div>
              <div className="flex justify-between gap-3">
                <dt className="text-ink-soft dark:text-parchment-soft">Half-open probe</dt>
                <dd className="tnum">{inspected.halfOpenProbe ? 'in flight' : '—'}</dd>
              </div>
            </dl>
            <button
              type="button"
              onClick={() => {
                void reset(inspected.provider)
              }}
              className="w-full rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
            >
              Reset circuit
            </button>
          </InspectorShell>
        )}
      </aside>
    </div>
  )
}

/**
 * Circuit breaker screen: live board behind the admin route guard.
 *
 * @remarks The router renders `NotFound` for non-admins, so no unlock
 * form lives here — master-key entry is terminal-only by design.
 *
 * @returns The circuits screen.
 */
export function CircuitsPage(): React.JSX.Element {
  return (
    <div className="space-y-4">
      <div className="space-y-1">
        <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
          <span aria-hidden="true" className="mr-1 text-ember">
            ❯
          </span>
          guard
        </p>
        <h1 className="font-display text-3xl font-medium tracking-tight">Circuits</h1>
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          Breakers per provider. Tripped circuits fail fast until cooldown ends.
        </p>
      </div>
      <CircuitsBoard />
    </div>
  )
}
