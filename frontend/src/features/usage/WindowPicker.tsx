import { useState } from 'react'
import { Select } from '../../shared/components/Select.js'
import { PRESETS, presetBounds, validateWindow, type PresetId } from './window.js'

interface WindowPickerProps {
  /** Prefix scoping element ids when two pickers share a screen. */
  idPrefix: string
  /** Submit button label (`Apply`, `Inspect`). */
  submitLabel: string
  /** Receives validated bounds on every preset pick and custom apply. */
  onApply: (bounds: { from?: string; to?: string }) => void
}

/**
 * Preset window picker with a custom-range escape hatch.
 *
 * @remarks One control for both dashboard screens: a preset dropdown
 * defaulting to the past 7 days (matching the server trailing default)
 * plus From/To date inputs behind `Custom range`. Picking a preset
 * applies immediately; custom dates validate through the shared
 * 90-day ceiling before applying. Every preset resolves inside that
 * ceiling, so the picker can never propose a rejected window.
 *
 * @param props - Id prefix, submit label, and apply handler.
 * @returns The picker form.
 */
export function WindowPicker({
  idPrefix,
  submitLabel,
  onApply,
}: WindowPickerProps): React.JSX.Element {
  const [preset, setPreset] = useState<PresetId>('past-7d')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [error, setError] = useState<string | null>(null)

  /**
   * Applies a preset immediately (custom only reveals its inputs).
   *
   * @param id - Picked preset.
   */
  const pick = (id: PresetId): void => {
    setPreset(id)
    setError(null)
    if (id === 'custom') return
    const bounds = presetBounds(id)
    onApply({ from: bounds.fromIso, to: bounds.toIso })
  }

  /**
   * Validates the custom dates and applies them to the query.
   */
  const applyCustom = (): void => {
    const checked = validateWindow(from, to)
    setError(checked.error)
    if (checked.error !== null) return
    onApply({
      ...(checked.fromIso === undefined ? {} : { from: checked.fromIso }),
      ...(checked.toIso === undefined ? {} : { to: checked.toIso }),
    })
  }

  const rangeId = `${idPrefix}-range`
  const showCustom = preset === 'custom'

  /**
   * Submits from the button or Enter: custom dates validate first,
   * presets simply re-apply their bounds.
   */
  const submit = (): void => {
    if (preset === 'custom') {
      applyCustom()
      return
    }
    setError(null)
    const bounds = presetBounds(preset)
    onApply({ from: bounds.fromIso, to: bounds.toIso })
  }

  return (
    <form
      className="flex flex-wrap items-end gap-2"
      onSubmit={(e) => {
        e.preventDefault()
        submit()
      }}
    >
      <Select
        id={rangeId}
        label="Range"
        value={preset}
        options={PRESETS.map((p) => ({ value: p.id, label: p.label }))}
        onChange={(v) => {
          pick(v as PresetId)
        }}
      />
      {showCustom ? (
        <>
          <div>
            <label htmlFor={`${idPrefix}-from`} className="mb-1 block text-[13px] font-medium">
              From
            </label>
            <input
              id={`${idPrefix}-from`}
              type="date"
              value={from}
              onChange={(e) => {
                setFrom(e.target.value)
              }}
              className="rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-[13px] tnum dark:border-parchment/15"
            />
          </div>
          <div>
            <label htmlFor={`${idPrefix}-to`} className="mb-1 block text-[13px] font-medium">
              To
            </label>
            <input
              id={`${idPrefix}-to`}
              type="date"
              value={to}
              onChange={(e) => {
                setTo(e.target.value)
              }}
              className="rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-[13px] tnum dark:border-parchment/15"
            />
          </div>
        </>
      ) : null}
      <button
        type="submit"
        className="rounded-md border border-ink/15 px-3 py-2 text-[13px] dark:border-parchment/15"
      >
        {submitLabel}
      </button>
      {error === null ? null : (
        <p role="alert" className="text-sm text-danger dark:text-danger-soft">
          {error}
        </p>
      )}
    </form>
  )
}
