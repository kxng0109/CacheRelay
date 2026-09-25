import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import { renderApp } from '../../test/utils.js'
import { InspectorShell } from './InspectorShell.js'

describe('InspectorShell', () => {
  it('closes on Escape and removes its listener on unmount', async () => {
    const user = userEvent.setup()
    let closed = 0
    const { unmount } = renderApp(
      <InspectorShell
        label="Probe inspector"
        title="probe"
        onClose={() => {
          closed += 1
        }}
      >
        <p>body</p>
      </InspectorShell>,
    )
    expect(screen.getByText('probe')).toBeInTheDocument()
    await user.keyboard('{Escape}')
    await waitFor(() => {
      expect(closed).toBe(1)
    })
    unmount()
    await user.keyboard('{Escape}')
    expect(closed).toBe(1)
  })

  it('plays the exit transition on backdrop dismiss before closing', async () => {
    const user = userEvent.setup()
    let closed = 0
    renderApp(
      <InspectorShell
        label="Probe inspector"
        title="probe"
        onClose={() => {
          closed += 1
        }}
      >
        <p>body</p>
      </InspectorShell>,
    )
    const panel = document.querySelector('aside')
    await waitFor(() => {
      expect(panel).toHaveClass('translate-x-0')
    })
    await user.click(screen.getByRole('button', { name: /dismiss inspector/i }))
    expect(panel).toHaveClass('translate-x-full')
    expect(closed).toBe(0)
    await waitFor(() => {
      expect(closed).toBe(1)
    })
  })

  it('plays the exit transition on close button before closing', async () => {
    const user = userEvent.setup()
    let closed = 0
    renderApp(
      <InspectorShell
        label="Probe inspector"
        title="probe"
        onClose={() => {
          closed += 1
        }}
      >
        <p>body</p>
      </InspectorShell>,
    )
    const panel = document.querySelector('aside')
    await waitFor(() => {
      expect(panel).toHaveClass('translate-x-0')
    })
    await user.click(screen.getByRole('button', { name: /^close inspector$/i }))
    expect(panel).toHaveClass('translate-x-full')
    expect(closed).toBe(0)
    await waitFor(() => {
      expect(closed).toBe(1)
    })
  })

  it('ignores repeated closes while the exit plays', async () => {
    const user = userEvent.setup()
    let closed = 0
    renderApp(
      <InspectorShell
        label="Probe inspector"
        title="probe"
        onClose={() => {
          closed += 1
        }}
      >
        <p>body</p>
      </InspectorShell>,
    )
    await screen.findByRole('dialog', { name: /probe inspector/i })
    const close = screen.getByRole('button', { name: /^close inspector$/i })
    await user.click(close)
    await user.click(close)
    await user.keyboard('{Escape}')
    await waitFor(() => {
      expect(closed).toBe(1)
    })
  })

  it('announces itself as a modal dialog with trapped focus', async () => {
    const user = userEvent.setup()
    let closed = 0
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
            <InspectorShell
              label="Probe inspector"
              title="probe"
              onClose={() => {
                closed += 1
                setOpen(false)
              }}
            >
              <button type="button">inner action</button>
            </InspectorShell>
          ) : null}
        </>
      )
    }
    renderApp(<Wrapper />)
    await user.click(screen.getByRole('button', { name: /opener/i }))
    const dialog = await screen.findByRole('dialog', { name: /probe inspector/i })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    // Heading-first initial focus is preserved from the bespoke behavior.
    expect(screen.getByText('probe')).toHaveFocus()
    const inner = screen.getByRole('button', { name: /inner action/i })
    await user.tab()
    expect(screen.getByRole('button', { name: /close inspector/i })).toHaveFocus()
    await user.tab()
    expect(inner).toHaveFocus()
    // The heading is initial focus only (tabindex -1), not a Tab stop:
    // the trap wraps the last stop back to the first.
    await user.tab()
    expect(screen.getByRole('button', { name: /close inspector/i })).toHaveFocus()
    await user.tab({ shift: true })
    expect(inner).toHaveFocus()
    await user.keyboard('{Escape}')
    await waitFor(() => {
      expect(closed).toBe(1)
    })
    expect(screen.getByRole('button', { name: /opener/i })).toHaveFocus()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
