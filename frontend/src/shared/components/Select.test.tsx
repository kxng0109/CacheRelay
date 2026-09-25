import { screen } from '@testing-library/react'
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
})
