import { useEffect, useId, useRef, useState } from 'react'
import { Check, ChevronDown } from 'lucide-react'

/**
 * One option in the shared dropdown.
 */
export interface SelectOption {
  /** Submitted value, unique within the list. */
  value: string
  /** Visible label. */
  label: string
}

interface SelectProps {
  /** Element id for the label association. */
  id: string
  /** Visible label text. */
  label: string
  /** Currently selected value (`''` renders the placeholder). */
  value: string
  /** Options in display order. */
  options: readonly SelectOption[]
  /** Selection handler. */
  onChange: (value: string) => void
  /** Placeholder text for the empty value. */
  placeholder?: string
  /** Disabled state (greyed, unfocusable via keyboard? still focusable, not operable). */
  disabled?: boolean
  /** Invalid state (aria-invalid ring). */
  invalid?: boolean
}

/**
 * Terminal-styled dropdown replacing every native select.
 *
 * @remarks One look everywhere: input-matching border and radius, mono
 * value text, ember chevron (the native arrow renders OS-chrome that
 * breaks both themes), flat hairline menu, ember tick on the active
 * option. Full keyboard contract: opens on Enter/Space/ArrowDown,
 * arrows move, Enter/Space picks, Esc closes, click-outside closes,
 * focus never leaves the trigger. ARIA `listbox` pattern with
 * `aria-expanded` and `aria-activedescendant`.
 *
 * @param props - Label, value, options, and handlers.
 * @returns The dropdown.
 */
export function Select({
  id,
  label,
  value,
  options,
  onChange,
  placeholder = 'Select…',
  disabled = false,
  invalid = false,
}: SelectProps): React.JSX.Element {
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const rootRef = useRef<HTMLDivElement | null>(null)
  const listId = useId()
  const selected = options.find((o) => o.value === value) ?? null

  useEffect(() => {
    if (!open) return
    const onPointerDown = (e: PointerEvent): void => {
      if (rootRef.current !== null && !rootRef.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('pointerdown', onPointerDown)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
    }
  }, [open])

  /**
   * Opens the menu with the selected (or first) option active.
   */
  const openMenu = (): void => {
    if (disabled || options.length === 0) return
    const index = Math.max(
      0,
      options.findIndex((o) => o.value === value),
    )
    setActive(index)
    setOpen(true)
  }

  /**
   * Handles trigger keydown: the full open/move/pick/close contract.
   *
   * @param e - React keyboard event on the trigger.
   */
  const onTriggerKeyDown = (e: React.KeyboardEvent<HTMLButtonElement>): void => {
    if (disabled || options.length === 0) return
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      if (open) {
        setActive((a) => (a + 1) % options.length)
      } else {
        openMenu()
      }
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      if (open) {
        setActive((a) => (a - 1 + options.length) % options.length)
      } else {
        openMenu()
      }
    } else if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      if (open) {
        const option = options[active]
        if (option !== undefined) {
          onChange(option.value)
          setOpen(false)
        }
      } else {
        openMenu()
      }
    } else if (e.key === 'Escape') {
      setOpen(false)
    } else if (e.key === 'Home' && open) {
      e.preventDefault()
      setActive(0)
    } else if (e.key === 'End' && open) {
      e.preventDefault()
      setActive(options.length - 1)
    }
  }

  return (
    <div ref={rootRef} className="relative">
      <label htmlFor={id} className="mb-1 block text-[13px] font-medium">
        {label}
      </label>
      <button
        type="button"
        id={id}
        role="combobox"
        aria-expanded={open}
        aria-controls={listId}
        aria-activedescendant={open ? `${listId}-option-${String(active)}` : undefined}
        aria-invalid={invalid}
        disabled={disabled}
        onClick={() => {
          if (options.length === 0) return
          if (open) {
            setOpen(false)
          } else {
            openMenu()
          }
        }}
        onKeyDown={onTriggerKeyDown}
        className={`flex w-full items-center justify-between gap-2 rounded-md border border-ink/15 bg-transparent px-3 py-2 font-mono text-sm disabled:cursor-not-allowed disabled:border-ink-soft disabled:text-ink-soft dark:border-parchment/15 dark:disabled:border-parchment-soft dark:disabled:text-parchment-soft ${
          invalid ? 'border-danger dark:border-danger-soft' : ''
        }`}
      >
        <span className={selected === null ? 'text-ink-soft dark:text-parchment-soft' : ''}>
          {selected === null ? placeholder : selected.label}
        </span>
        <ChevronDown size={16} aria-hidden="true" className="shrink-0 text-ember" />
      </button>
      {open && !disabled ? (
        <ul
          role="listbox"
          id={listId}
          aria-label={label}
          className="absolute inset-x-0 top-full z-30 mt-1 max-h-64 overflow-y-auto rounded-md border border-ink/15 bg-cream py-1 dark:border-parchment/15 dark:bg-night"
        >
          {options.map((o, i) => (
            <li
              key={o.value}
              id={`${listId}-option-${String(i)}`}
              role="option"
              aria-selected={o.value === value}
              onMouseEnter={() => {
                setActive(i)
              }}
              onClick={() => {
                onChange(o.value)
                setOpen(false)
              }}
              className={`flex cursor-pointer items-center gap-2 px-3 py-2 font-mono text-sm ${
                i === active ? 'bg-ink/6 dark:bg-parchment/8' : ''
              }`}
            >
              <span aria-hidden="true" className="w-4 shrink-0 text-ember">
                {i === active ? <Check size={14} /> : null}
              </span>
              {o.label}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  )
}
