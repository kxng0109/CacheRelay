import { fireEvent, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderApp } from '../../test/utils.js'
import { WindowPicker } from './WindowPicker.js'

async function pickOption(user: ReturnType<typeof userEvent.setup>, label: string) {
  await user.click(screen.getByRole('combobox', { name: /range/i }))
  await user.click(screen.getByRole('option', { name: label }))
}

describe('WindowPicker', () => {
  it('defaults to the past week and applies presets immediately', async () => {
    const user = userEvent.setup()
    const onApply = vi.fn()
    renderApp(<WindowPicker idPrefix="w" submitLabel="Apply" onApply={onApply} />)
    expect(screen.getByRole('combobox', { name: /range/i })).toHaveTextContent('Past 7 days')
    expect(screen.queryByLabelText(/^from$/i)).not.toBeInTheDocument()
    await pickOption(user, 'Past 30 days')
    expect(onApply).toHaveBeenCalledTimes(1)
    const bounds = onApply.mock.calls[0]?.[0] as { from: string; to: string }
    expect(bounds.from.endsWith('T00:00:00Z')).toBe(true)
    expect(bounds.to.endsWith('T23:59:59Z')).toBe(true)
    expect(Date.parse(bounds.to) - Date.parse(bounds.from)).toBeLessThanOrEqual(30 * 86_400_000)
  })

  it('reveals custom dates and validates before applying', async () => {
    const user = userEvent.setup()
    const onApply = vi.fn()
    renderApp(<WindowPicker idPrefix="w" submitLabel="Apply" onApply={onApply} />)
    await pickOption(user, 'Custom range')
    expect(screen.getByLabelText(/^from$/i)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText(/^from$/i), { target: { value: '2026-09-24' } })
    fireEvent.change(screen.getByLabelText(/^to$/i), { target: { value: '2026-09-01' } })
    await user.click(screen.getByRole('button', { name: /^apply$/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/cannot be after/i)
    expect(onApply).not.toHaveBeenCalled()
  })

  it('applies valid custom windows with the submit label', async () => {
    const user = userEvent.setup()
    const onApply = vi.fn()
    renderApp(<WindowPicker idPrefix="w" submitLabel="Inspect" onApply={onApply} />)
    await pickOption(user, 'Custom range')
    fireEvent.change(screen.getByLabelText(/^from$/i), { target: { value: '2026-09-20' } })
    fireEvent.change(screen.getByLabelText(/^to$/i), { target: { value: '2026-09-24' } })
    await user.click(screen.getByRole('button', { name: /^inspect$/i }))
    expect(onApply).toHaveBeenCalledTimes(1)
    expect(onApply).toHaveBeenCalledWith({
      from: '2026-09-20T00:00:00Z',
      to: '2026-09-24T23:59:59Z',
    })
  })

  it('applies blank custom dates as backend defaults', async () => {
    const user = userEvent.setup()
    const onApply = vi.fn()
    renderApp(<WindowPicker idPrefix="w" submitLabel="Apply" onApply={onApply} />)
    await pickOption(user, 'Custom range')
    await user.click(screen.getByRole('button', { name: /^apply$/i }))
    expect(onApply).toHaveBeenCalledTimes(1)
    expect(onApply).toHaveBeenCalledWith({})
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('re-applies preset bounds from the submit button', async () => {
    const user = userEvent.setup()
    const onApply = vi.fn()
    renderApp(<WindowPicker idPrefix="w" submitLabel="Apply" onApply={onApply} />)
    await user.click(screen.getByRole('button', { name: /^apply$/i }))
    expect(onApply).toHaveBeenCalledTimes(1)
    const bounds = onApply.mock.calls[0]?.[0] as { from: string; to: string }
    expect(bounds.from.endsWith('T00:00:00Z')).toBe(true)
    expect(bounds.to.endsWith('T23:59:59Z')).toBe(true)
  })
})
