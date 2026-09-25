import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { renderApp } from '../../test/utils.js'
import { ShortcutSheet } from './ShortcutSheet.js'

describe('ShortcutSheet', () => {
  it('stays hidden until opened', () => {
    renderApp(<ShortcutSheet open={false} onClose={vi.fn()} />)
    expect(screen.queryByRole('dialog', { name: /keyboard shortcuts/i })).not.toBeInTheDocument()
  })

  it('traps focus, closes on Escape, and returns focus', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    function Wrapper(): React.JSX.Element {
      const [open, setOpen] = useState(false)
      return (
        <>
          <button
            type="button"
            onClick={() => {
              setOpen(true)
            }}
          >
            opener
          </button>
          {open ? (
            <ShortcutSheet
              open
              onClose={() => {
                onClose()
                setOpen(false)
              }}
            />
          ) : null}
        </>
      )
    }
    renderApp(<Wrapper />)
    await user.click(screen.getByRole('button', { name: /opener/i }))
    const dialog = await screen.findByRole('dialog', { name: /keyboard shortcuts/i })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    // The Close control autofocuses; Tab wraps on the single stop.
    expect(screen.getByRole('button', { name: /close shortcuts/i })).toHaveFocus()
    await user.tab()
    expect(screen.getByRole('button', { name: /close shortcuts/i })).toHaveFocus()
    await user.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledTimes(1)
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: /keyboard shortcuts/i })).not.toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: /opener/i })).toHaveFocus()
  })

  it('closes on backdrop click', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    renderApp(<ShortcutSheet open onClose={onClose} />)
    await screen.findByRole('dialog', { name: /keyboard shortcuts/i })
    const backdrop = screen.getByRole('dialog', { name: /keyboard shortcuts/i }).parentElement
    if (!(backdrop instanceof HTMLElement)) throw new Error('Backdrop not found')
    await user.click(backdrop)
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
