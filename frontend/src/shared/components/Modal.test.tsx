import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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
    const { container } = renderModal(onClose)
    const panel = container.querySelector('[role="dialog"]')
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
    const { container } = renderModal(onClose)
    const backdrop = container.firstElementChild
    if (!(backdrop instanceof HTMLElement)) throw new Error('Backdrop not found')
    await user.click(backdrop)
    await waitFor(() => {
      expect(onClose).toHaveBeenCalledTimes(1)
    })
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
})
