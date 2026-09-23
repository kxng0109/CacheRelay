import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { GatewayClient } from '../../shared/api/client.js'
import { toErrorMessage } from '../../shared/api/client.js'
import type { ModelAliasRecord, ProviderChainStep } from '../../shared/api/types.js'
import { InspectorShell } from '../../shared/components/InspectorShell.js'
import { Modal } from '../../shared/components/Modal.js'
import { ProviderBoard } from './ProviderBoard.js'

const STRATEGIES = ['SEQUENTIAL', 'RACE'] as const
const MAX_STEPS = 8

/**
 * Empty chain step for the editors. Override starts empty, meaning the
 * requested model passes through untouched.
 */
function blankStep(): ProviderChainStep {
  return { providerName: '', modelOverride: null }
}

/**
 * Chain step editor rows: provider name plus optional override, with
 * add and remove capped at the backend maximum of 8 steps.
 *
 * @param props - Steps, change callback, and id prefix.
 * @returns The editable step list.
 */
function ChainEditor({
  steps,
  onChange,
  idPrefix,
}: {
  steps: ProviderChainStep[]
  onChange: (steps: ProviderChainStep[]) => void
  idPrefix: string
}): React.JSX.Element {
  return (
    <div className="space-y-2">
      {steps.map((step, i) => (
        <div key={i} className="grid gap-2 sm:grid-cols-[1fr_1fr_auto]">
          <div>
            <label
              htmlFor={`${idPrefix}-provider-${String(i)}`}
              className="mb-1 block text-[13px] font-medium"
            >
              Provider {i + 1}
            </label>
            <input
              id={`${idPrefix}-provider-${String(i)}`}
              value={step.providerName}
              autoComplete="off"
              onChange={(e) => {
                onChange(
                  steps.map((s, j) => (j === i ? { ...s, providerName: e.target.value } : s)),
                )
              }}
              placeholder="openai"
              className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-sm dark:border-parchment/15"
            />
          </div>
          <div>
            <label
              htmlFor={`${idPrefix}-override-${String(i)}`}
              className="mb-1 block text-[13px] font-medium"
            >
              Model override (optional)
            </label>
            <input
              id={`${idPrefix}-override-${String(i)}`}
              value={step.modelOverride ?? ''}
              autoComplete="off"
              onChange={(e) => {
                const v = e.target.value.trim()
                onChange(
                  steps.map((s, j) =>
                    j === i ? { ...s, modelOverride: v.length === 0 ? null : v } : s,
                  ),
                )
              }}
              placeholder="e.g. claude-x"
              className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-sm dark:border-parchment/15"
            />
          </div>
          <div className="flex items-end">
            <button
              type="button"
              disabled={steps.length <= 1}
              onClick={() => {
                onChange(steps.filter((_, j) => j !== i))
              }}
              aria-label={`Remove step ${String(i + 1)}`}
              className="rounded-md border border-ink/15 px-3 py-2 text-[13px] disabled:cursor-not-allowed disabled:border-ink-soft disabled:text-ink-soft dark:border-parchment/15 dark:disabled:border-parchment-soft dark:disabled:text-parchment-soft"
            >
              Remove
            </button>
          </div>
        </div>
      ))}
      {steps.length < MAX_STEPS ? (
        <button
          type="button"
          onClick={() => {
            onChange([...steps, blankStep()])
          }}
          className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
        >
          Add step
        </button>
      ) : null}
      <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
        Blank override sends the requested model.
      </p>
    </div>
  )
}

/**
 * Model alias administration: list, create, replace, delete.
 *
 * @remarks Proof-type: live (real `/v1/admin/models` CRUD). File-bound
 * aliases render read-only with no actions; database aliases get replace
 * and delete. Row selection and form state are local UI state.
 *
 * @returns The models board.
 */
function ModelsBoard(): React.JSX.Element {
  const qc = useQueryClient()
  const [filter, setFilter] = useState('')
  const [selected, setSelected] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [replacing, setReplacing] = useState<string | null>(null)
  const [confirming, setConfirming] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [newName, setNewName] = useState('')
  const [newStrategy, setNewStrategy] = useState<string>('SEQUENTIAL')
  const [newChain, setNewChain] = useState<ProviderChainStep[]>([blankStep()])
  const [editStrategy, setEditStrategy] = useState<string>('SEQUENTIAL')
  const [editChain, setEditChain] = useState<ProviderChainStep[]>([blankStep()])
  const createOpener = useRef<HTMLElement | null>(null)

  const query = useQuery({
    queryKey: ['model-aliases'],
    queryFn: ({ signal }) => new GatewayClient().listModelAliases({ signal }),
  })

  const refresh = (): void => {
    void qc.invalidateQueries({ queryKey: ['model-aliases'] })
  }

  const aliases = query.data?.models ?? []
  const queryText = filter.trim().toLowerCase()
  const visible =
    queryText.length === 0
      ? aliases
      : aliases.filter((a) => a.name.toLowerCase().includes(queryText))
  const inspected = aliases.find((a) => a.name === selected) ?? null
  const inspectedEditable = inspected !== null && inspected.source === 'database'
  const fileCount = aliases.filter((a) => a.source === 'file').length

  /**
   * Creates the alias from the create form. Client checks mirror the
   * backend contract (slug name, 1 to 8 non-blank steps); the gateway
   * remains the final validator.
   */
  const onCreate = (): void => {
    setError(null)
    setNotice(null)
    const name = newName.trim().toLowerCase()
    const chain = newChain
      .map((s) => ({ ...s, providerName: s.providerName.trim() }))
      .filter((s) => s.providerName.length > 0)
    if (name.length === 0 || chain.length === 0) {
      setError('Name and at least one provider step are required.')
      return
    }
    void new GatewayClient()
      .createModelAlias({ name, chain, strategy: newStrategy })
      .then((out) => {
        setNotice(`Alias ${out.name} created.`)
        setCreating(false)
        createOpener.current?.focus()
        setNewName('')
        setNewStrategy('SEQUENTIAL')
        setNewChain([blankStep()])
        refresh()
      })
      .catch((e: unknown) => {
        setError(toErrorMessage(e, 'Alias creation failed.'))
      })
  }

  const openCreate = (): void => {
    setError(null)
    createOpener.current =
      document.activeElement instanceof HTMLElement ? document.activeElement : null
    setCreating(true)
  }

  const closeCreate = (): void => {
    setCreating(false)
    createOpener.current?.focus()
  }

  /**
   * Opens the replace editor seeded from the inspected alias.
   *
   * @param alias - Database alias under edit.
   */
  const startReplace = (alias: ModelAliasRecord): void => {
    setError(null)
    setSelected(null)
    setReplacing(alias.name)
    setEditStrategy(alias.strategy)
    setEditChain(alias.chain.length === 0 ? [blankStep()] : alias.chain.map((s) => ({ ...s })))
  }

  /**
   * Replaces the routing plan of the alias under edit.
   */
  const onReplace = (): void => {
    if (replacing === null) return
    setError(null)
    setNotice(null)
    const chain = editChain
      .map((s) => ({ ...s, providerName: s.providerName.trim() }))
      .filter((s) => s.providerName.length > 0)
    if (chain.length === 0) {
      setError('At least one provider step is required.')
      return
    }
    const name = replacing
    void new GatewayClient()
      .updateModelAlias(name, { chain, strategy: editStrategy })
      .then(() => {
        setNotice(`Alias ${name} replaced.`)
        setReplacing(null)
        refresh()
      })
      .catch((e: unknown) => {
        setError(toErrorMessage(e, 'Alias replacement failed.'))
      })
  }

  /**
   * Deletes the confirmed alias.
   *
   * @param name - Alias to delete.
   */
  const onDelete = (name: string): void => {
    setError(null)
    setConfirming(null)
    void new GatewayClient()
      .deleteModelAlias(name)
      .then(() => {
        setNotice(`Alias ${name} deleted.`)
        if (selected === name) setSelected(null)
        refresh()
      })
      .catch((e: unknown) => {
        setError(toErrorMessage(e, 'Alias deletion failed.'))
      })
  }

  return (
    <div className="space-y-6">
      <div className="space-y-4">
        <p className="font-mono text-xs text-ink-soft tnum dark:text-parchment-soft">
          {aliases.length} aliases · {fileCount} file-bound (read-only) ·{' '}
          {aliases.length - fileCount} database-managed
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <label htmlFor="model-filter" className="sr-only">
            Filter aliases
          </label>
          <input
            id="model-filter"
            type="search"
            value={filter}
            placeholder="Filter"
            onChange={(e) => {
              setFilter(e.target.value)
            }}
            className="w-48 rounded-md border border-ink/15 bg-transparent px-3 py-2 text-[13px] dark:border-parchment/15"
          />
          <span className="flex-1" />
          <button
            type="button"
            onClick={openCreate}
            className="rounded-md bg-ink px-4 py-2 text-sm font-medium text-paper dark:bg-parchment dark:text-night"
          >
            New alias
          </button>
        </div>
        {error === null || creating ? null : (
          <p role="alert" className="text-sm text-danger dark:text-danger-soft">
            {error}
          </p>
        )}
        {notice === null ? null : (
          <p role="status" className="text-[13px]">
            {notice}
          </p>
        )}
        {creating ? (
          <Modal
            label="New alias"
            title="New alias"
            subtitle="Map a client name to a provider chain."
            closeLabel="Close new alias"
            onClose={closeCreate}
          >
            {error === null ? null : (
              <p role="alert" className="text-sm text-danger dark:text-danger-soft">
                {error}
              </p>
            )}
            <div className="space-y-3">
              <div className="grid gap-3 sm:grid-cols-2">
                <div>
                  <label htmlFor="model-new-name" className="mb-1 block text-[13px] font-medium">
                    Name (lowercase slug)
                  </label>
                  <input
                    id="model-new-name"
                    value={newName}
                    autoComplete="off"
                    autoFocus
                    onChange={(e) => {
                      setNewName(e.target.value)
                    }}
                    placeholder="fast-gpt"
                    className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-sm dark:border-parchment/15"
                  />
                </div>
                <div>
                  <label
                    htmlFor="model-new-strategy"
                    className="mb-1 block text-[13px] font-medium"
                  >
                    Strategy
                  </label>
                  <select
                    id="model-new-strategy"
                    value={newStrategy}
                    onChange={(e) => {
                      setNewStrategy(e.target.value)
                    }}
                    className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-sm dark:border-parchment/15"
                  >
                    {STRATEGIES.map((s) => (
                      <option key={s} value={s}>
                        {s}
                      </option>
                    ))}
                  </select>
                </div>
              </div>
              <ChainEditor steps={newChain} onChange={setNewChain} idPrefix="model-new" />
              <div className="flex justify-end gap-2">
                <button
                  type="button"
                  onClick={closeCreate}
                  className="rounded-md border border-ink/15 px-4 py-2 text-sm dark:border-parchment/15"
                >
                  Cancel
                </button>
                <button
                  type="button"
                  onClick={onCreate}
                  className="rounded-md bg-ink px-4 py-2 text-sm font-medium text-paper dark:bg-parchment dark:text-night"
                >
                  Create alias
                </button>
              </div>
            </div>
          </Modal>
        ) : null}
        {query.isPending ? (
          <p role="status" className="text-sm">
            Loading aliases…
          </p>
        ) : query.error instanceof Error ? (
          <p role="alert" className="text-sm text-danger dark:text-danger-soft">
            {query.error.message}
          </p>
        ) : visible.length === 0 ? (
          <p className="text-sm text-ink-soft dark:text-parchment-soft">
            {aliases.length === 0
              ? 'No aliases yet. Create the first alias above.'
              : 'No aliases match this filter.'}
          </p>
        ) : (
          <table className="w-full text-left text-sm">
            <caption className="sr-only">Model aliases</caption>
            <thead className="sticky top-0 bg-paper dark:bg-night">
              <tr className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
                <th scope="col" className="py-2 pr-3 font-medium">
                  Name
                </th>
                <th scope="col" className="py-2 pr-3 font-medium">
                  Source
                </th>
                <th scope="col" className="py-2 pr-3 font-medium">
                  Chain
                </th>
                <th scope="col" className="py-2 text-right font-medium">
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {visible.map((a) => {
                const active = a.name === selected
                return (
                  <tr
                    key={a.name}
                    tabIndex={0}
                    aria-selected={active}
                    onClick={() => {
                      setSelected(active ? null : a.name)
                    }}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault()
                        setSelected(active ? null : a.name)
                      }
                    }}
                    className={`cursor-pointer border-t border-ink/10 dark:border-parchment/10 ${
                      active ? 'bg-ink/4 dark:bg-parchment/6' : ''
                    }`}
                  >
                    <td
                      className="max-w-44 truncate py-2 pr-3 font-mono text-[13px]"
                      title={a.name}
                    >
                      {a.name}
                    </td>
                    <td className="py-2 pr-3">
                      <span
                        className={`rounded px-2 py-1 text-[13px] ${
                          a.source === 'file'
                            ? 'bg-ink/8 text-ink-soft dark:bg-parchment/10 dark:text-parchment-soft'
                            : 'bg-success/15 text-success dark:text-success-soft'
                        }`}
                      >
                        ● {a.source}
                      </span>
                    </td>
                    <td
                      className="max-w-56 truncate py-2 pr-3 font-mono text-[13px]"
                      title={a.chain.map((s) => s.providerName).join(' → ')}
                    >
                      {a.chain.length === 0 ? '—' : a.chain.map((s) => s.providerName).join(' → ')}
                    </td>
                    <td className="py-2 text-right">
                      {a.source === 'database' ? (
                        confirming === a.name ? (
                          <span className="inline-flex items-center gap-2 text-[13px]">
                            Delete “{a.name}”?
                            <button
                              type="button"
                              onClick={(e) => {
                                e.stopPropagation()
                                onDelete(a.name)
                              }}
                              className="rounded-md border border-danger/40 px-2 py-1 text-danger dark:text-danger-soft"
                            >
                              Yes
                            </button>
                            <button
                              type="button"
                              onClick={(e) => {
                                e.stopPropagation()
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
                            onClick={(e) => {
                              e.stopPropagation()
                              setConfirming(a.name)
                            }}
                            className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
                          >
                            Delete
                          </button>
                        )
                      ) : (
                        <span
                          className="font-mono text-xs text-ink-soft dark:text-parchment-soft"
                          title="File-bound aliases are read-only"
                        >
                          —
                        </span>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
        {replacing === null ? null : (
          <div className="space-y-3 rounded-xl border border-ink/10 bg-cream p-4 dark:border-parchment/10 dark:bg-transparent">
            <h2 className="font-mono text-sm">
              Replace plan: <span className="font-semibold">{replacing}</span>
            </h2>
            <div>
              <label htmlFor="model-edit-strategy" className="mb-1 block text-[13px] font-medium">
                Strategy
              </label>
              <select
                id="model-edit-strategy"
                value={editStrategy}
                onChange={(e) => {
                  setEditStrategy(e.target.value)
                }}
                className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-sm dark:border-parchment/15"
              >
                {STRATEGIES.map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
            </div>
            <ChainEditor steps={editChain} onChange={setEditChain} idPrefix="model-edit" />
            <div className="flex gap-2">
              <button
                type="button"
                onClick={onReplace}
                className="rounded-md bg-ink px-4 py-2 text-sm font-medium text-paper dark:bg-parchment dark:text-night"
              >
                Replace plan
              </button>
              <button
                type="button"
                onClick={() => {
                  setReplacing(null)
                }}
                className="rounded-md border border-ink/15 px-4 py-2 text-sm dark:border-parchment/15"
              >
                Cancel
              </button>
            </div>
          </div>
        )}
      </div>
      {inspected === null ? (
        <p className="text-[13px] text-ink-soft dark:text-parchment-soft">
          Select a row to inspect an alias.
        </p>
      ) : (
        <InspectorShell
          label="Alias inspector"
          title={inspected.name}
          onClose={() => {
            setSelected(null)
          }}
        >
          <dl className="space-y-2 text-[13px]">
            <div className="flex justify-between gap-3">
              <dt className="text-ink-soft dark:text-parchment-soft">Source</dt>
              <dd>{inspected.source}</dd>
            </div>
            <div className="flex justify-between gap-3">
              <dt className="text-ink-soft dark:text-parchment-soft">Strategy</dt>
              <dd className="font-mono">{inspected.strategy}</dd>
            </div>
            {inspected.chain.map((s, i) => (
              <div key={i} className="flex justify-between gap-3">
                <dt className="text-ink-soft dark:text-parchment-soft">Step {i + 1}</dt>
                <dd className="font-mono">
                  {s.providerName}
                  {s.modelOverride === null ? null : ` → ${s.modelOverride}`}
                </dd>
              </div>
            ))}
          </dl>
          {inspectedEditable ? (
            <button
              type="button"
              onClick={() => {
                startReplace(inspected)
              }}
              className="w-full rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
            >
              Replace plan
            </button>
          ) : (
            <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
              File-bound aliases are read-only.
            </p>
          )}
        </InspectorShell>
      )}
    </div>
  )
}

/**
 * Model alias screen: catalog management behind the admin route guard.
 *
 * @remarks The router renders `NotFound` for non-admins, so operators
 * never see this surface — Run screens offer choose-only dropdowns.
 *
 * @returns The models screen.
 */
export function ModelsPage(): React.JSX.Element {
  return (
    <div className="space-y-4">
      <div className="space-y-1">
        <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
          <span aria-hidden="true" className="mr-1 text-ember">
            ❯
          </span>
          guard
        </p>
        <h1 className="font-display text-3xl font-medium tracking-tight">Models</h1>
        <p className="text-sm text-ink-soft dark:text-parchment-soft">
          Aliases map client names to provider chains. Database aliases are editable. File-bound
          aliases are read-only.
        </p>
      </div>
      <ModelsBoard />
      <ProviderBoard />
    </div>
  )
}
