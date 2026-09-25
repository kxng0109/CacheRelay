import { fireEvent, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderApp } from '../../test/utils.js'
import { Select } from './Select.js'

const OPTIONS = [
  { value: 'today', label: 'Today' },
  { value: 'past-7d', label: 'Past 7 days' },
  { value: 'custom', label: 'Custom range' },
]

function picker(props: Partial<Parameters<typeof Select>[0]> = {}) {
  const onChange = vi.fn()
  renderApp(
    <Select
      id="pick"
      label="Range"
      value=""
      options={OPTIONS}
      onChange={onChange}
      placeholder="Pick one"
      {...props}
    />,
  )
  return onChange
}

describe('Select', () => {
  it('opens on click and picks with pointer', async () => {
    const user = userEvent.setup()
    const onChange = picker()
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    await user.click(screen.getByRole('combobox', { name: /range/i }))
    expect(screen.getByRole('listbox')).toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: 'Today' }))
    expect(onChange).toHaveBeenCalledWith('today')
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })

  it('shows the placeholder until a value arrives', () => {
    picker()
    expect(screen.getByRole('combobox', { name: /range/i })).toHaveTextContent('Pick one')
  })

  it('moves with arrows and picks with Enter', async () => {
    const user = userEvent.setup()
    const onChange = picker()
    await user.click(screen.getByRole('combobox', { name: /range/i }))
    await user.keyboard('{ArrowDown}')
    await user.keyboard('{Enter}')
    expect(onChange).toHaveBeenCalledWith('past-7d')
  })

  it('opens on Enter and ArrowUp from the closed trigger', async () => {
    const user = userEvent.setup()
    picker()
    const trigger = screen.getByRole('combobox', { name: /range/i })
    trigger.focus()
    await user.keyboard('{Enter}')
    expect(screen.getByRole('listbox')).toBeInTheDocument()
    await user.keyboard('{Escape}')
    trigger.focus()
    await user.keyboard('{ArrowUp}')
    expect(screen.getByRole('listbox')).toBeInTheDocument()
    await user.keyboard('{Escape}')
    trigger.focus()
    await user.keyboard('{ArrowDown}')
    expect(screen.getByRole('listbox')).toBeInTheDocument()
  })

  it('moves up with ArrowUp inside the open menu', async () => {
    const user = userEvent.setup()
    const onChange = picker()
    await user.click(screen.getByRole('combobox', { name: /range/i }))
    await user.keyboard('{ArrowDown}')
    await user.keyboard('{ArrowUp}')
    await user.keyboard('{Enter}')
    expect(onChange).toHaveBeenCalledWith('today')
  })

  it('ignores keys while disabled', async () => {
    const user = userEvent.setup()
    const onChange = picker({ disabled: true })
    const trigger = screen.getByRole('combobox', { name: /range/i })
    // Disabled buttons take no focus: dispatch at the element so the
    // guard itself is exercised, not the focus system.
    fireEvent.keyDown(trigger, { key: 'Enter' })
    fireEvent.keyDown(trigger, { key: 'ArrowDown' })
    fireEvent.keyDown(trigger, { key: 'Escape' })
    await user.click(trigger)
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    expect(onChange).not.toHaveBeenCalled()
  })

  it('closes on Escape without picking', async () => {
    const user = userEvent.setup()
    const onChange = picker()
    await user.click(screen.getByRole('combobox', { name: /range/i }))
    expect(screen.getByRole('listbox')).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    expect(onChange).not.toHaveBeenCalled()
  })

  it('jumps to ends with Home and End', async () => {
    const user = userEvent.setup()
    const onChange = picker()
    await user.click(screen.getByRole('combobox', { name: /range/i }))
    await user.keyboard('{End}')
    await user.keyboard('{Enter}')
    expect(onChange).toHaveBeenCalledWith('custom')
    await user.click(screen.getByRole('combobox', { name: /range/i }))
    await user.keyboard('{Home}')
    await user.keyboard('{Enter}')
    expect(onChange).toHaveBeenCalledWith('today')
  })

  it('closes on outside pointer without picking', async () => {
    const user = userEvent.setup()
    const onChange = picker()
    await user.click(screen.getByRole('combobox', { name: /range/i }))
    expect(screen.getByRole('listbox')).toBeInTheDocument()
    await user.click(document.body)
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    expect(onChange).not.toHaveBeenCalled()
  })

  it('stays shut when disabled or empty', async () => {
    const user = userEvent.setup()
    picker({ disabled: true })
    await user.click(screen.getByRole('combobox', { name: /range/i }))
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })

  it('renders nothing clickable without options', async () => {
    const user = userEvent.setup()
    picker({ options: [] })
    await user.click(screen.getByRole('combobox', { name: /range/i }))
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })

  it('marks invalid controls for assistive tech', () => {
    picker({ invalid: true })
    expect(screen.getByRole('combobox', { name: /range/i })).toHaveAttribute('aria-invalid', 'true')
  })

  it('opens downward when space below fits', async () => {
    const user = userEvent.setup()
    picker()
    await user.click(screen.getByRole('combobox', { name: /range/i }))
    const menu = screen.getByRole('listbox')
    expect(menu.style.position).toBe('fixed')
    expect(menu.style.top).toBe('4px')
  })

  it('opens upward when the trigger sits near the viewport bottom', async () => {
    const user = userEvent.setup()
    const rect = {
      top: 700,
      bottom: 740,
      left: 0,
      right: 200,
      width: 200,
      height: 40,
      x: 0,
      y: 700,
      toJSON: () => ({}),
    } as DOMRect
    const spy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(rect)
    try {
      picker()
      await user.click(screen.getByRole('combobox', { name: /range/i }))
      expect(screen.getByRole('listbox').style.top).toBe('576px')
    } finally {
      spy.mockRestore()
    }
  })

  it('portals the menu to the body so dialog scroll never clips it', async () => {
    const user = userEvent.setup()
    picker()
    await user.click(screen.getByRole('combobox', { name: /range/i }))
    expect(screen.getByRole('listbox').parentElement).toBe(document.body)
  })

  it('dismisses the menu on viewport scroll so it never detaches', async () => {
    const user = userEvent.setup()
    picker()
    await user.click(screen.getByRole('combobox', { name: /range/i }))
    expect(screen.getByRole('listbox')).toBeInTheDocument()
    fireEvent.scroll(window)
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })

  it('keeps the menu open while scrolling inside it', async () => {
    const user = userEvent.setup()
    picker()
    await user.click(screen.getByRole('combobox', { name: /range/i }))
    const menu = screen.getByRole('listbox')
    fireEvent.scroll(menu)
    expect(screen.getByRole('listbox')).toBeInTheDocument()
  })

  it('ticks the selected value, not the hovered option', async () => {
    const user = userEvent.setup()
    picker({ value: 'past-7d' })
    await user.click(screen.getByRole('combobox', { name: /range/i }))
    const options = screen.getAllByRole('option')
    const selected = options[1]
    const hovered = options[2]
    if (selected === undefined || hovered === undefined) throw new Error('Options missing')
    expect(selected.querySelector('svg')).not.toBeNull()
    await user.hover(hovered)
    expect(selected.querySelector('svg')).not.toBeNull()
    expect(hovered.querySelector('svg')).toBeNull()
  })

  it('jumps to the matching option on printable keys', async () => {
    const user = userEvent.setup()
    const onChange = picker()
    await user.click(screen.getByRole('combobox', { name: /range/i }))
    await user.keyboard('c')
    await user.keyboard('{Enter}')
    expect(onChange).toHaveBeenCalledWith('custom')
  })

  it('accumulates fast keys into one narrowing buffer', async () => {
    const user = userEvent.setup()
    const onChange = picker()
    await user.click(screen.getByRole('combobox', { name: /range/i }))
    // 't' lands on Today; 'c' within the window narrows to 'tc', which
    // matches nothing, so the stop stays on Today instead of Custom.
    await user.keyboard('t')
    await user.keyboard('c')
    await user.keyboard('{Enter}')
    expect(onChange).toHaveBeenCalledWith('today')
  })

  it('resets the buffer after a pause', async () => {
    const user = userEvent.setup()
    const onChange = picker()
    await user.click(screen.getByRole('combobox', { name: /range/i }))
    await user.keyboard('t')
    await new Promise((resolve) => setTimeout(resolve, 600))
    await user.keyboard('c')
    await user.keyboard('{Enter}')
    expect(onChange).toHaveBeenCalledWith('custom')
  }, 10_000)

  it('leaves a closed menu alone on Escape', async () => {
    const user = userEvent.setup()
    const onChange = picker()
    const trigger = screen.getByRole('combobox', { name: /range/i })
    trigger.focus()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    expect(onChange).not.toHaveBeenCalled()
  })

  it('keeps Escape inside an open menu from closing the parent dialog', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    const { Modal } = await import('./Modal.js')
    renderApp(
      <Modal
        label="Parent dialog"
        title="Parent dialog"
        closeLabel="Close dialog"
        onClose={onClose}
      >
        <Select
          id="inner-pick"
          label="Range"
          value=""
          options={OPTIONS}
          onChange={vi.fn()}
          placeholder="Pick one"
        />
      </Modal>,
    )
    await user.click(screen.getByRole('combobox', { name: /range/i }))
    expect(screen.getByRole('listbox')).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    // The dialog never entered its leaving state: Esc stopped at the menu.
    expect(screen.getByRole('dialog', { name: /parent dialog/i })).toHaveClass('translate-y-0')
    expect(onClose).not.toHaveBeenCalled()
  })
})
