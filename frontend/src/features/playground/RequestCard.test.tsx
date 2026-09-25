import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { resolveApiBase } from '../../shared/api/client.js'
import { renderApp } from '../../test/utils.js'
import { RequestCard, exampleRequest } from './RequestCard.js'

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

  it('quotes server-controlled model ids so shells cannot break out', () => {
    const evil = `x';$(touch pwned);echo '`
    const snippet = exampleRequest(evil)
    expect(snippet).toContain(`'\\''`)
    const arg = snippet.slice(snippet.indexOf('-d ') + 3).trim()
    const inner = arg.slice(1, -1).replaceAll(`'\\''`, `'`)
    expect((JSON.parse(inner) as { model: string }).model).toBe(evil)
  })

  it('derives the snippet URL from the configured base', () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://gw.example')
    try {
      expect(exampleRequest('m')).toContain('https://gw.example/v1/chat/completions')
    } finally {
      vi.unstubAllEnvs()
    }
    const base = resolveApiBase()
    const configured = base.length > 0 ? base : window.location.origin
    expect(exampleRequest('m')).toContain(`${configured}/v1/chat/completions`)
  })

  it('falls back to the page origin when no base is configured', () => {
    vi.stubEnv('VITE_API_BASE_URL', '')
    try {
      const snippet = exampleRequest('m')
      expect(snippet).toContain(`${window.location.origin}/v1/chat/completions`)
      expect(snippet).not.toContain('localhost:8080')
    } finally {
      vi.unstubAllEnvs()
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
