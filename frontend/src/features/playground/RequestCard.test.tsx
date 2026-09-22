import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { renderApp } from '../../test/utils.js'
import { RequestCard } from './RequestCard.js'

function stubClipboard(writeText?: (s: string) => Promise<void>): void {
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: writeText === undefined ? undefined : { writeText },
  })
}

describe('RequestCard', () => {
  it('copies a terminal-ready curl with a key placeholder', async () => {
    const user = userEvent.setup()
    const writes: string[] = []
    stubClipboard((s: string) => {
      writes.push(s)
      return Promise.resolve()
    })
    try {
      renderApp(<RequestCard model="gpt-4o" />)
      await user.click(screen.getByRole('button', { name: /^copy$/i }))
      expect(writes.length).toBe(1)
      const first = writes.at(0)
      expect(first).toBeDefined()
      if (first !== undefined) {
        expect(first).toContain('chat/completions')
        expect(first).toContain('YOUR_KEY')
        expect(first).toContain('gpt-4o')
      }
      expect(screen.getByRole('button', { name: /copied/i })).toBeInTheDocument()
    } finally {
      stubClipboard()
    }
  })

  it('reports clipboard absence inline', async () => {
    const user = userEvent.setup()
    stubClipboard()
    renderApp(<RequestCard model="gpt-4o" />)
    await user.click(screen.getByRole('button', { name: /^copy$/i }))
    expect(screen.getByText(/copy unavailable in this browser/i)).toBeInTheDocument()
  })

  it('reports clipboard rejection inline', async () => {
    const user = userEvent.setup()
    stubClipboard(() => Promise.reject(new Error('denied')))
    try {
      renderApp(<RequestCard model="gpt-4o" />)
      await user.click(screen.getByRole('button', { name: /^copy$/i }))
      expect(await screen.findByText(/copy failed. select the text manually/i)).toBeInTheDocument()
    } finally {
      stubClipboard()
    }
  })
})
