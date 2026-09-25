import { useEffect, useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useShallow } from 'zustand/react/shallow'
import { GatewayClient } from '../api/client.js'
import { useAuthStore } from '../auth/store.js'
import { Select } from './Select.js'
import type { OwnedKey } from '../api/types.js'

/**
 * Key source for Run screens: owned account keys or a pasted key.
 */
export type KeySource = 'account' | 'paste'

interface KeySourcePickerProps {
  /** Controlled source. */
  source: KeySource
  /** Source change handler. */
  onSourceChange: (source: KeySource) => void
  /** Selected owned key hash (account mode). */
  selectedKeyId: string
  /** Owned key selection handler. */
  onSelectKeyId: (keyId: string) => void
  /** Prefix scoping element ids. */
  idPrefix: string
}

/**
 * Key-source toggle plus owned-key dropdown for Run screens.
 *
 * @remarks Session users choose between their owned keys (never displayed,
 * resolved server-side via `X-Act-As-Key`) and a pasted key. Guests see
 * nothing here; parents render the paste input alone. Owned keys load
 * from `GET /v1/me/keys` once per mount; the first enabled key
 * auto-selects. Failures and empty inventories render muted guidance
 * with paste as the escape hatch, never red alarms and never blocks.
 *
 * @param props - Controlled source, selection, and id prefix.
 * @returns The toggle plus dropdown, or null for guests.
 */
export function KeySourcePicker({
  source,
  onSourceChange,
  selectedKeyId,
  onSelectKeyId,
  idPrefix,
}: KeySourcePickerProps): React.JSX.Element | null {
  const session = useAuthStore(useShallow((s) => s.session))
  const owned = useQuery({
    queryKey: ['my-keys'],
    queryFn: ({ signal }) => new GatewayClient().myKeys({ signal }),
    enabled: session !== null,
    staleTime: 60_000,
    retry: false,
  })
  const keys: OwnedKey[] = useMemo(() => owned.data?.keys ?? [], [owned.data])

  useEffect(() => {
    if (source !== 'account' || selectedKeyId !== '' || keys.length === 0) return
    const first = keys.find((k) => k.enabled) ?? keys[0]
    if (first !== undefined) onSelectKeyId(first.keyId)
  }, [source, selectedKeyId, keys, onSelectKeyId])

  if (session === null) return null
  const groupId = `${idPrefix}-key-source`
  return (
    <div className="space-y-2">
      <fieldset>
        <legend className="mb-1 block text-[13px] font-medium">Key source</legend>
        <div className="flex gap-2" role="radiogroup" aria-label="Key source">
          {(
            [
              { value: 'account', label: 'Account key' },
              { value: 'paste', label: 'Paste a key' },
            ] as const
          ).map((o) => (
            <label
              key={o.value}
              className={`flex cursor-pointer items-center gap-2 rounded-md border px-3 py-2 text-[13px] ${
                source === o.value
                  ? 'border-ink bg-ink/4 font-medium dark:border-parchment dark:bg-parchment/6'
                  : 'border-ink/15 dark:border-parchment/15'
              }`}
            >
              <input
                type="radio"
                name={groupId}
                value={o.value}
                checked={source === o.value}
                onChange={() => {
                  onSourceChange(o.value)
                }}
                className="accent-ember"
              />
              {o.label}
            </label>
          ))}
        </div>
      </fieldset>
      {source !== 'account' ? null : owned.isPending ? (
        <Select
          id={`${idPrefix}-owned-key`}
          label="Owned key"
          value=""
          options={[]}
          onChange={() => undefined}
          placeholder="Loading keys…"
          disabled
        />
      ) : owned.error instanceof Error ? (
        <p role="alert" className="text-[13px] text-ink-soft dark:text-parchment-soft">
          Owned keys unavailable ({owned.error.message}). Paste a key instead.
        </p>
      ) : keys.length === 0 ? (
        <p role="status" className="text-[13px] text-ink-soft dark:text-parchment-soft">
          No owned keys yet. Paste a key instead, or ask an admin for one.
        </p>
      ) : (
        <Select
          id={`${idPrefix}-owned-key`}
          label="Owned key"
          value={selectedKeyId}
          options={keys.map((k) => ({
            value: k.keyId,
            label: `${k.name}${k.enabled ? '' : ' (disabled)'}`,
          }))}
          onChange={onSelectKeyId}
          placeholder="Select an owned key"
        />
      )}
    </div>
  )
}
