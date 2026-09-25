import { useQuery, useQueryClient } from '@tanstack/react-query'
import { GatewayClient } from '../api/client.js'
import { Select } from '../components/Select.js'

/**
 * Gateway model dropdown. No free text: operators choose from what the
 * gateway actually serves, so mistyped ids cannot be submitted.
 *
 * @remarks The catalog loads only with a gateway key present (pasted key
 * or owned-key act-as selector). Without credentials the control stays
 * disabled with a guide message. A failed catalog keeps the control
 * disabled with a reload action rather than reopening a text field.
 * A value the catalog no longer lists (history reloads) is kept as a
 * labeled option so the stored choice stays visible and submittable.
 * Selection writes through `onSelect` (callers forward to their form
 * state with validation).
 *
 * @param props - Credentials, element id, live value, selection handler.
 * @returns The catalog dropdown plus its loading states.
 */
export function ModelSelect({
  token,
  actAsKey,
  id,
  value,
  onSelect,
  invalid,
}: {
  token: string
  /** Owned key hash for act-as-self reads (session JWT supplies auth). */
  actAsKey?: string
  id: string
  value: string
  onSelect: (value: string) => void
  invalid: boolean
}): React.JSX.Element {
  const qc = useQueryClient()
  const hasCredentials = token.length > 0 || (actAsKey !== undefined && actAsKey.length > 0)
  const catalog = useQuery({
    queryKey: ['model-catalog', token.length > 0, actAsKey ?? null],
    queryFn: ({ signal }) =>
      new GatewayClient({ token }).models({
        signal,
        ...(actAsKey === undefined ? {} : { actAsKey }),
      }),
    enabled: hasCredentials,
    staleTime: 60_000,
    retry: false,
  })
  const ids = catalog.data?.data.map((m) => m.id) ?? []
  const known = ids.includes(value)
  const options =
    value.length > 0 && !known
      ? [
          { value, label: `${value} (saved)` },
          ...ids.map((modelId) => ({ value: modelId, label: modelId })),
        ]
      : ids.map((modelId) => ({ value: modelId, label: modelId }))
  const placeholder = !hasCredentials
    ? 'Paste a key to list models'
    : catalog.isPending
      ? 'Loading models…'
      : catalog.isError
        ? 'Models unavailable'
        : 'Select a model'

  return (
    <div className="space-y-1.5">
      <div className="flex gap-2">
        <div className="min-w-0 flex-1">
          <Select
            id={id}
            label="Model"
            value={value}
            options={options}
            onChange={onSelect}
            placeholder={placeholder}
            disabled={!hasCredentials || catalog.isError}
            invalid={invalid}
          />
        </div>
        {catalog.isError && hasCredentials ? (
          <button
            type="button"
            onClick={() => {
              void qc.invalidateQueries({ queryKey: ['model-catalog'] })
            }}
            className="shrink-0 rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
          >
            Reload
          </button>
        ) : null}
      </div>
      {token.length === 0 && (actAsKey === undefined || actAsKey.length === 0) ? (
        <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
          Models list here once a key is pasted.
        </p>
      ) : null}
    </div>
  )
}
