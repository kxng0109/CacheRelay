import { act, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { router } from './app/router.js'
import { server } from './test/setup.js'

const LEGS: [string, string][] = [
  ['/playground', 'Playground'],
  ['/circuits', 'Circuits'],
  ['/keys', 'Keys'],
  ['/ledger', 'Ledger'],
  ['/cache', 'Cache & budgets'],
  ['/embeddings', 'Embeddings'],
  ['/approvals', 'Approvals'],
  ['/mcp', 'MCP'],
  ['/observability', 'Observability'],
]

describe('application boot', () => {
  it('renders the shell from #root', async () => {
    document.body.innerHTML = '<div id="root"></div>'
    await import('./main.js')
    await waitFor(() => {
      expect(screen.getByText('CacheRelay')).toBeInTheDocument()
    })
  })

  it('loads every route chunk without crashing', async () => {
    server.use(
      http.get('*/actuator/health', () => HttpResponse.json({ status: 'UP' })),
      http.get(
        '*/actuator/prometheus',
        () => new HttpResponse('', { headers: { 'Content-Type': 'text/plain' } }),
      ),
    )
    for (const [path, label] of LEGS) {
      await act(async () => {
        await router.navigate(path)
      })
      await waitFor(() => {
        expect(screen.getByRole('link', { current: 'page' })).toHaveTextContent(label)
      })
    }
  })
})
