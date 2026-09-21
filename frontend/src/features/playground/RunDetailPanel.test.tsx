import { fireEvent, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { renderApp } from '../../test/utils.js'
import { RunDetailPanel } from './RunDetailPanel.js'

describe('RunDetailPanel', () => {
  it('shows em dashes for unknown measurements, never zeros', () => {
    renderApp(
      <RunDetailPanel detail={{ runId: 1, model: 'm', streaming: true, status: 'running' }} />,
    )
    expect(screen.getByLabelText(/run 1 details/i)).toBeInTheDocument()
    expect(screen.queryByText(/^0( ms)?$/)).not.toBeInTheDocument()
    expect(screen.getByText('live')).toBeInTheDocument()
  })

  it('presents known facts as tiles and key values', () => {
    renderApp(
      <RunDetailPanel
        detail={{
          runId: 2,
          model: 'gpt-56-luna',
          streaming: true,
          status: 'done',
          latencyMs: 1234.5,
          frames: 42,
          cacheTier: 'HIT (L1-Exact)',
          similarity: '1.0000',
          age: '7',
        }}
      />,
    )
    expect(screen.getByText('1235 ms')).toBeInTheDocument()
    expect(screen.getByText('42')).toBeInTheDocument()
    expect(screen.getByText('HIT (L1-Exact)')).toBeInTheDocument()
    expect(screen.getByText('1.0000')).toBeInTheDocument()
    expect(screen.getByText(/raw json/i)).toBeInTheDocument()
  })

  it('copies the raw payload and reports clipboard absence', async () => {
    const user = userEvent.setup()
    const writes: string[] = []
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {
        writeText: (s: string): Promise<void> => {
          writes.push(s)
          return Promise.resolve()
        },
      },
    })
    renderApp(
      <RunDetailPanel detail={{ runId: 3, model: 'm', streaming: false, status: 'done' }} />,
    )
    await user.click(screen.getByText(/raw json/i))
    await user.click(screen.getByRole('button', { name: /^copy$/i }))
    expect(writes.length).toBe(1)
    const first = writes.at(0)
    expect(first).toBeDefined()
    if (first !== undefined) {
      expect(JSON.parse(first)).toMatchObject({ run: 3, model: 'm' })
    }
    try {
      // No clipboard stub: the absence branch runs instead.
      Object.defineProperty(navigator, 'clipboard', {
        configurable: true,
        value: undefined,
      })
      renderApp(
        <RunDetailPanel detail={{ runId: 4, model: 'm', streaming: false, status: 'done' }} />,
      )
      const copies = screen.getAllByRole('button', { name: /^copy$/i })
      const last = copies.at(-1)
      expect(last).toBeDefined()
      if (last !== undefined) fireEvent.click(last)
      expect(screen.getByText(/copy unavailable in this browser/i)).toBeInTheDocument()
    } finally {
      Object.defineProperty(navigator, 'clipboard', {
        configurable: true,
        value: undefined,
      })
    }
  })

  it('copies the receipt as markdown with a heading and fenced payload', async () => {
    const user = userEvent.setup()
    const writes: string[] = []
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {
        writeText: (s: string): Promise<void> => {
          writes.push(s)
          return Promise.resolve()
        },
      },
    })
    try {
      renderApp(
        <RunDetailPanel
          detail={{ runId: 5, model: 'gpt-56-luna', streaming: false, status: 'done' }}
        />,
      )
      await user.click(screen.getByRole('button', { name: /copy markdown/i }))
      expect(writes.length).toBe(1)
      const first = writes.at(0)
      expect(first).toBeDefined()
      if (first !== undefined) {
        expect(first.startsWith('# Run 5 · gpt-56-luna (done)')).toBe(true)
        expect(first).toContain('```json')
      }
    } finally {
      Object.defineProperty(navigator, 'clipboard', {
        configurable: true,
        value: undefined,
      })
    }
  })
})
