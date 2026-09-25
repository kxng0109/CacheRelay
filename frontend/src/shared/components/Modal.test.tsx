import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { renderApp } from '../../test/utils.js'
import { Modal } from './Modal.js'

function renderModal(onClose: () => void = vi.fn()) {
  return renderApp(
    <Modal
      label="Confirm action"
      title="Confirm action"
      closeLabel="Close dialog"
      onClose={onClose}
    >
      <p>body</p>
    </Modal>,
  )
}

describe('Modal', () => {
  it('renders the dialog with its heading', () => {
    renderModal()
    expect(screen.getByRole('dialog', { name: /confirm action/i })).toBeInTheDocument()
    expect(screen.getByText('body')).toBeInTheDocument()
  })

  it('renders wide on demand', () => {
    renderApp(
      <Modal
        label="Wide dialog"
        title="Wide dialog"
        closeLabel="Close dialog"
        onClose={vi.fn()}
        wide
      >
        <p>body</p>
      </Modal>,
    )
    expect(screen.getByRole('dialog', { name: /wide dialog/i })).toHaveClass('max-w-2xl')
  })

  it('plays the exit transition on close button before closing', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    renderModal(onClose)
    // Portalled overlay: query the document, not the render container.
    const panel = document.querySelector('[role="dialog"]')
    await waitFor(() => {
      expect(panel).toHaveClass('translate-y-0')
    })
    await user.click(screen.getByRole('button', { name: /close dialog/i }))
    expect(panel).toHaveClass('translate-y-2')
    expect(onClose).not.toHaveBeenCalled()
    await waitFor(() => {
      expect(onClose).toHaveBeenCalledTimes(1)
    })
  })

  it('dismisses on backdrop click', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    renderModal(onClose)
    // Portalled overlay: the backdrop is the dialog's parent in `body`.
    const backdrop = screen.getByRole('dialog').parentElement
    if (!(backdrop instanceof HTMLElement)) throw new Error('Backdrop not found')
    await user.click(backdrop)
    await waitFor(() => {
      expect(onClose).toHaveBeenCalledTimes(1)
    })
  })

  it('traps Tab inside, closes topmost-first on Escape, and returns focus', async () => {
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
            <Modal
              label="Trap dialog"
              title="Trap dialog"
              closeLabel="Close dialog"
              onClose={onClose}
            >
              <button type="button">inner action</button>
            </Modal>
          ) : null}
        </>
      )
    }
    renderApp(<Wrapper />)
    await user.click(screen.getByRole('button', { name: /opener/i }))
    const opener = screen.getByRole('button', { name: /opener/i })
    const close = screen.getByRole('button', { name: /close dialog/i })
    const inner = screen.getByRole('button', { name: /inner action/i })
    expect(close).toHaveFocus()
    await user.tab()
    expect(inner).toHaveFocus()
    await user.tab()
    expect(close).toHaveFocus()
    await user.keyboard('{Escape}')
    await waitFor(() => {
      expect(onClose).toHaveBeenCalledTimes(1)
    })
    // Focus returns the moment the close starts, not after the exit.
    expect(opener).toHaveFocus()
  })

  it('dismisses on Escape', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    renderModal(onClose)
    await user.tab()
    expect(screen.getByRole('button', { name: /close dialog/i })).toHaveFocus()
    await user.keyboard('{Escape}')
    await waitFor(() => {
      expect(onClose).toHaveBeenCalledTimes(1)
    })
  })

  it('closes instantly under reduced motion without an exit timer', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    // defineProperty (never a bare `window.matchMedia` reference, which
    // trips `unbound-method`); restored to the setup default afterwards.
    const stub =
      (matches: boolean) =>
      (query: string): MediaQueryList => ({
        matches,
        media: query,
        onchange: null,
        addListener: () => undefined,
        removeListener: () => undefined,
        addEventListener: () => undefined,
        removeEventListener: () => undefined,
        dispatchEvent: () => false,
      })
    Object.defineProperty(window, 'matchMedia', {
      value: stub(true),
      configurable: true,
      writable: true,
    })
    try {
      renderModal(onClose)
      await user.click(screen.getByRole('button', { name: /close dialog/i }))
      expect(onClose).toHaveBeenCalledTimes(1)
    } finally {
      Object.defineProperty(window, 'matchMedia', {
        value: stub(false),
        configurable: true,
        writable: true,
      })
    }
  })

  it('ignores repeated closes while the exit plays', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    renderModal(onClose)
    const close = screen.getByRole('button', { name: /close dialog/i })
    await user.click(close)
    await user.click(close)
    await user.keyboard('{Escape}')
    await waitFor(() => {
      expect(onClose).toHaveBeenCalledTimes(1)
    })
  })
})
