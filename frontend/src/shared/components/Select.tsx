import { useEffect, useId, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
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
  /** Error node id for `aria-describedby` when invalid. */
  describedBy?: string
}

/**
 * Terminal-styled dropdown replacing every native select.
 *
 * @remarks One look everywhere: input-matching border and radius, mono
 * value text, ember chevron (the native arrow renders OS-chrome that
 * breaks both themes), flat hairline menu, ember tick on the selected
 * option (never the hovered one). Full keyboard contract: opens on
 * Enter/Space/ArrowDown, arrows move, printable keys jump, Enter/Space
 * picks, Esc closes topmost-first (an open menu stops Esc so a parent
 * dialog never closes underneath it), click-outside closes, focus never
 * leaves the trigger. ARIA `listbox` pattern with `aria-expanded` and
 * `aria-activedescendant`.
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
  describedBy,
}: SelectProps): React.JSX.Element {
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(0)
  const [dropUp, setDropUp] = useState(false)
  const [anchor, setAnchor] = useState<DOMRect | null>(null)
  const rootRef = useRef<HTMLDivElement | null>(null)
  const menuRef = useRef<HTMLUListElement | null>(null)
  // Type-ahead buffer: printable keys accumulate briefly so "cu" narrows
  // past "c" alone; the timer resets it back to single-key jumps.
  const typeRef = useRef('')
  const typeTimer = useRef<number | null>(null)
  const listId = useId()
  const selected = options.find((o) => o.value === value) ?? null

  useEffect(() => {
    return () => {
      if (typeTimer.current !== null) window.clearTimeout(typeTimer.current)
    }
  }, [])

  useEffect(() => {
    if (!open) return
    const onPointerDown = (e: PointerEvent): void => {
      const target = e.target as Node
      if (
        rootRef.current?.contains(target) === false &&
        menuRef.current?.contains(target) === false
      ) {
        setOpen(false)
      }
    }
    document.addEventListener('pointerdown', onPointerDown)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
    }
  }, [open])

  useEffect(() => {
    if (!open) return
    // The menu is fixed to the viewport, so scrolls outside it (dialog
    // panels, the page) or resizes would detach it: dismiss instead of
    // chasing layout. Scrolls inside the menu itself keep it open.
    const close = (e: Event): void => {
      const inside =
        e.target instanceof Node &&
        (menuRef.current?.contains(e.target) === true ||
          rootRef.current?.contains(e.target) === true)
      if (!inside) {
        setOpen(false)
      }
    }
    window.addEventListener('scroll', close, true)
    window.addEventListener('resize', close)
    return () => {
      window.removeEventListener('scroll', close, true)
      window.removeEventListener('resize', close)
    }
  }, [open])

  const menuHeight = Math.min(options.length * 40, 256)
  const menuWidth = Math.min(Math.max(anchor?.width ?? 160, 160), 384)
  const menuStyle: React.CSSProperties =
    anchor === null
      ? {}
      : {
          position: 'fixed',
          top: dropUp ? Math.max(8, anchor.top - menuHeight - 4) : anchor.bottom + 4,
          left: Math.max(8, Math.min(anchor.left, window.innerWidth - menuWidth - 8)),
          minWidth: anchor.width,
        }

  /**
   * Opens the menu with the selected (or first) option active. Flips
   * upward when the viewport space below the trigger cannot fit the
   * menu, so dialogs with edge scrolling never clip the list.
   */
  const openMenu = (): void => {
    if (disabled || options.length === 0) return
    const index = Math.max(
      0,
      options.findIndex((o) => o.value === value),
    )
    setActive(index)
    const rect = rootRef.current?.getBoundingClientRect() ?? null
    setAnchor(rect)
    if (rect === null) {
      setDropUp(false)
    } else {
      const needed = Math.min(options.length * 40, 256)
      const below = window.innerHeight - rect.bottom
      setDropUp(below < needed && rect.top > below)
    }
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
      // Topmost-first Esc ladder: an open menu consumes the key so a
      // parent dialog never closes underneath it; a closed picker lets
      // the key bubble to the overlay.
      if (open) {
        e.stopPropagation()
        setOpen(false)
      }
    } else if (e.key === 'Home' && open) {
      e.preventDefault()
      setActive(0)
    } else if (e.key === 'End' && open) {
      e.preventDefault()
      setActive(options.length - 1)
    } else if (e.key.length === 1 && open) {
      // Printable-key jump: cycle from after the current stop through
      // labels starting with the buffered text (case-insensitive).
      e.preventDefault()
      if (typeTimer.current !== null) window.clearTimeout(typeTimer.current)
      const next = `${typeRef.current}${e.key}`.toLowerCase()
      typeRef.current = next
      typeTimer.current = window.setTimeout(() => {
        typeRef.current = ''
      }, 500)
      const from = (active + 1) % options.length
      for (let step = 0; step < options.length; step += 1) {
        const candidate = options[(from + step) % options.length]
        if (candidate?.label.toLowerCase().startsWith(next) === true) {
          setActive((from + step) % options.length)
          break
        }
      }
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
        aria-describedby={describedBy}
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
        <span
          className={`min-w-0 flex-1 truncate text-left ${selected === null ? 'text-ink-soft dark:text-parchment-soft' : ''}`}
        >
          {selected === null ? placeholder : selected.label}
        </span>
        <ChevronDown size={16} aria-hidden="true" className="shrink-0 text-ember" />
      </button>
      {open && !disabled && anchor !== null
        ? createPortal(
            <ul
              ref={menuRef}
              role="listbox"
              id={listId}
              aria-label={label}
              style={menuStyle}
              className="z-60 max-h-64 w-max max-w-[min(24rem,calc(100vw-3rem))] overflow-y-auto rounded-md border border-ink/15 bg-cream py-1 dark:border-parchment/15 dark:bg-night"
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
                  className={`flex cursor-pointer items-center gap-2 overflow-hidden px-3 py-2 font-mono text-sm whitespace-nowrap ${
                    i === active ? 'bg-ink/6 dark:bg-parchment/8' : ''
                  }`}
                >
                  <span aria-hidden="true" className="w-4 shrink-0 text-ember">
                    {o.value === value ? <Check size={14} /> : null}
                  </span>
                  <span className="truncate" title={o.label}>
                    {o.label}
                  </span>
                </li>
              ))}
            </ul>,
            document.body,
          )
        : null}
    </div>
  )
}
