import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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
})
