import { screen } from '@testing-library/react'
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
    expect(closed).toBe(1)
    unmount()
    await user.keyboard('{Escape}')
    expect(closed).toBe(1)
  })
})
