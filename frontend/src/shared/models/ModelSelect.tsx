import { useQuery, useQueryClient } from '@tanstack/react-query'
import type { UseFormRegisterReturn } from 'react-hook-form'
import { GatewayClient } from '../api/client.js'

/**
 * Gateway model dropdown. No free text: operators choose from what the
 * gateway actually serves, so mistyped ids cannot be submitted.
 *
 * @remarks The catalog loads only with a gateway key present. Without a
 * key the control stays disabled with a guide message. A failed catalog
 * keeps the control disabled with a reload action rather than reopening
 * a text field. A value the catalog no longer lists (history reloads) is
 * kept as a labeled option so the stored choice stays visible and
 * submittable.
 *
 * @param props - Gateway key, element id, form registration, live value.
 * @returns The catalog dropdown plus its loading states.
 */
export function ModelSelect({
  token,
  id,
  registration,
  value,
  invalid,
}: {
  token: string
  id: string
  registration: UseFormRegisterReturn
  value: string
  invalid: boolean
}): React.JSX.Element {
  const qc = useQueryClient()
  const catalog = useQuery({
    queryKey: ['model-catalog', token.length > 0],
    queryFn: ({ signal }) => new GatewayClient({ token }).models({ signal }),
    enabled: token.length > 0,
    staleTime: 60_000,
    retry: false,
  })
  const ids = catalog.data?.data.map((m) => m.id) ?? []
  const known = ids.includes(value)
  const options = value.length > 0 && !known ? [value, ...ids] : ids

  return (
    <div className="space-y-1.5">
      <div className="flex gap-2">
        <select
          id={id}
          {...registration}
          aria-invalid={invalid}
          disabled={token.length === 0 || catalog.isError}
          className="w-full rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-sm disabled:cursor-not-allowed disabled:border-ink-soft disabled:text-ink-soft dark:border-parchment/15 dark:disabled:border-parchment-soft dark:disabled:text-parchment-soft"
        >
          <option value="">
            {token.length === 0
              ? 'Paste a key to list models'
              : catalog.isPending
                ? 'Loading models…'
                : catalog.isError
                  ? 'Models unavailable'
                  : 'Select a model'}
          </option>
          {options.map((modelId) => (
            <option key={modelId} value={modelId}>
              {modelId}
              {modelId === value && !known ? ' (saved)' : null}
            </option>
          ))}
        </select>
        {catalog.isError && token.length > 0 ? (
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
      {token.length === 0 ? (
        <p className="font-mono text-xs text-ink-soft dark:text-parchment-soft">
          Models list here once a key is pasted.
        </p>
      ) : null}
    </div>
  )
}
