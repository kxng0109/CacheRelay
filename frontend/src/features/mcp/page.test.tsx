import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { useAuthStore } from '../../shared/auth/store.js'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { McpPage } from './page.js'

function toolsList() {
  return {
    jsonrpc: '2.0',
    id: 'tools-list',
    result: {
      tools: [
        {
          name: 'postgres__run_query',
          description: 'Run a read query',
          inputSchema: { type: 'object', properties: { sql: { type: 'string' } } },
          annotations: {
            readOnlyHint: true,
            destructiveHint: true,
            idempotentHint: true,
            openWorldHint: true,
          },
        },
        { name: 'plain', description: 'No hints', inputSchema: {}, annotations: {} },
        { name: 'broken', description: 42, inputSchema: null, annotations: null },
        'not-an-object',
        { description: 'nameless' },
      ],
    },
  }
}

describe('McpPage', () => {
  it('asks for a credential before probing', () => {
    renderApp(<McpPage />)
    expect(screen.getByText(/paste a gateway key/i)).toBeInTheDocument()
  })

  it('declares suspension honestly on 403 without inventing tools', async () => {
    server.use(http.post('*/v1/mcp', () => new HttpResponse('x', { status: 403 })))
    renderApp(<McpPage />, { gatewayKey: 'gw-test' })
    await waitFor(() => {
      expect(screen.getByText(/suspended upstream/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/will not invent tools/i)).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('lists live tools with permission badges', async () => {
    server.use(http.post('*/v1/mcp', () => HttpResponse.json(toolsList())))
    renderApp(<McpPage />, { gatewayKey: 'gw-test' })
    const table = await screen.findByRole('table')
    expect(table).toHaveTextContent('postgres__run_query')
    expect(table).toHaveTextContent('read')
    expect(screen.getByText(/tools:/i)).toHaveTextContent('3')
  })

  it('sends the pasted gateway key even when a session is present', async () => {
    let auth: string | null = null
    server.use(
      http.post('*/v1/mcp', ({ request }) => {
        auth = request.headers.get('authorization')
        return HttpResponse.json(toolsList())
      }),
    )
    renderApp(<McpPage />, { gatewayKey: 'gw-pasted-key', adminSession: true })
    await screen.findByRole('table')
    expect(auth).toBe('Bearer gw-pasted-key')
  })

  it('refetches with the new key after a key switch', async () => {
    const seen: string[] = []
    server.use(
      http.post('*/v1/mcp', ({ request }) => {
        seen.push(request.headers.get('authorization') ?? '')
        return HttpResponse.json(toolsList())
      }),
    )
    renderApp(<McpPage />, { gatewayKey: 'gw-first' })
    await screen.findByRole('table')
    expect(seen).toEqual(['Bearer gw-first'])
    useAuthStore.getState().setGatewayKey('gw-second')
    await waitFor(() => {
      expect(seen).toEqual(['Bearer gw-first', 'Bearer gw-second'])
    })
  })

  it('selects a tool with the keyboard', async () => {
    const user = userEvent.setup()
    server.use(http.post('*/v1/mcp', () => HttpResponse.json(toolsList())))
    renderApp(<McpPage />, { gatewayKey: 'gw-test' })
    const table = await screen.findByRole('table')
    within(table)
      .getByRole('button', { name: /inspect tool postgres__run_query/i })
      .focus()
    await user.keyboard('{Enter}')
    expect(screen.getByRole('dialog', { name: /tool inspector/i })).toHaveTextContent(
      'postgres__run_query',
    )
  })

  it('closes the tool inspector from its close button', async () => {
    const user = userEvent.setup()
    server.use(http.post('*/v1/mcp', () => HttpResponse.json(toolsList())))
    renderApp(<McpPage />, { gatewayKey: 'gw-test' })
    const table = await screen.findByRole('table')
    await user.click(
      within(table).getByRole('button', { name: /inspect tool postgres__run_query/i }),
    )
    const inspector = await screen.findByRole('dialog', { name: /tool inspector/i })
    await user.click(within(inspector).getByRole('button', { name: /close inspector/i }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: /tool inspector/i })).not.toBeInTheDocument()
    })
    expect(screen.getByText(/select a tool to inspect/i)).toBeInTheDocument()
  })

  it('filters tools and inspects schemas', async () => {
    const user = userEvent.setup()
    server.use(http.post('*/v1/mcp', () => HttpResponse.json(toolsList())))
    renderApp(<McpPage />, { gatewayKey: 'gw-test' })
    const table = await screen.findByRole('table')
    await user.type(screen.getByLabelText(/filter tools/i), 'postgres')
    expect(table).toHaveTextContent('postgres__run_query')
    await user.click(
      within(table).getByRole('button', { name: /inspect tool postgres__run_query/i }),
    )
    const inspector = screen.getByRole('dialog', { name: /tool inspector/i })
    expect(inspector).toHaveTextContent('read')
    expect(inspector).toHaveTextContent('destructive')
    expect(inspector).toHaveTextContent('idempotent')
    expect(inspector).toHaveTextContent('network')
    expect(inspector).toHaveTextContent('sql')
  })

  it('retries the catalog on demand', async () => {
    const user = userEvent.setup()
    let calls = 0
    server.use(
      http.post('*/v1/mcp', () => {
        calls += 1
        return new HttpResponse('x', { status: 403 })
      }),
    )
    renderApp(<McpPage />, { gatewayKey: 'gw-test' })
    await waitFor(() => {
      expect(screen.getByText(/suspended upstream/i)).toBeInTheDocument()
    })
    expect(calls).toBe(1)
    await user.click(screen.getByRole('button', { name: /retry/i }))
    await waitFor(() => {
      expect(calls).toBe(2)
    })
  })

  it('reports catalog failures as alerts', async () => {
    server.use(http.post('*/v1/mcp', () => new HttpResponse('x', { status: 500 })))
    renderApp(<McpPage />, { gatewayKey: 'gw-test' })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/HTTP 500/)
    })
  })

  it('names an empty catalog honestly', async () => {
    server.use(
      http.post('*/v1/mcp', () =>
        HttpResponse.json({ jsonrpc: '2.0', id: 'tools-list', result: { tools: [] } }),
      ),
    )
    renderApp(<McpPage />, { gatewayKey: 'gw-test' })
    await waitFor(() => {
      expect(screen.getByText(/catalog is empty/i)).toBeInTheDocument()
    })
  })

  it('names a filter with zero matches honestly', async () => {
    const user = userEvent.setup()
    server.use(http.post('*/v1/mcp', () => HttpResponse.json(toolsList())))
    renderApp(<McpPage />, { gatewayKey: 'gw-test' })
    await screen.findByRole('table')
    await user.type(screen.getByLabelText(/filter tools/i), 'zzz-no-tool')
    expect(screen.getByText(/no tools match this filter/i)).toBeInTheDocument()
  })

  it('deselects a tool on second click', async () => {
    const user = userEvent.setup()
    server.use(http.post('*/v1/mcp', () => HttpResponse.json(toolsList())))
    renderApp(<McpPage />, { gatewayKey: 'gw-test' })
    const table = await screen.findByRole('table')
    await user.click(
      within(table).getByRole('button', { name: /inspect tool postgres__run_query/i }),
    )
    expect(screen.getByRole('dialog', { name: /tool inspector/i })).toHaveTextContent('sql')
    await user.click(
      within(table).getByRole('button', { name: /inspect tool postgres__run_query/i }),
    )
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: /tool inspector/i })).not.toBeInTheDocument()
    })
    expect(screen.getByText(/select a tool to inspect/i)).toBeInTheDocument()
  })

  it('inspects the malformed tool honestly', async () => {
    const user = userEvent.setup()
    server.use(http.post('*/v1/mcp', () => HttpResponse.json(toolsList())))
    renderApp(<McpPage />, { gatewayKey: 'gw-test' })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect tool broken/i }))
    const inspector = screen.getByRole('dialog', { name: /tool inspector/i })
    expect(inspector).toHaveTextContent('No description.')
  })

  it('surfaces JSON-RPC errors as alerts', async () => {
    server.use(
      http.post('*/v1/mcp', () =>
        HttpResponse.json({
          jsonrpc: '2.0',
          id: 'tools-list',
          error: { code: -32603, message: 'backend exploded' },
        }),
      ),
    )
    renderApp(<McpPage />, { gatewayKey: 'gw-test' })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/backend exploded/)
    })
  })
})
