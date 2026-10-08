import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp, selectOption } from '../../test/utils.js'
import { A2aPage } from './page.js'

const CARD = {
  protocolVersion: '0.3',
  name: 'Helper',
  url: 'http://localhost:8080/v1/a2a/helper',
  description: 'Helps out',
  version: '1.0.0',
}

describe('A2aPage', () => {
  it('asks for a credential before probing', () => {
    renderApp(<A2aPage />)
    expect(screen.getByText(/paste a gateway key/i)).toBeInTheDocument()
  })

  it('loads the agent card and invokes message/send', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/a2a/:agent/card', () => HttpResponse.json(CARD)),
      http.post('*/v1/a2a/:agent', () =>
        HttpResponse.json({ jsonrpc: '2.0', id: 'a2a-1', result: { reply: 'hi' } }),
      ),
    )
    renderApp(<A2aPage />, { gatewayKey: 'gw-test' })
    await user.type(screen.getByLabelText(/agent/i), 'helper')
    await user.click(screen.getByRole('button', { name: /load card/i }))
    await screen.findByText('Helper')
    fireEvent.change(screen.getByLabelText(/params/i), { target: { value: '{oops' } })
    await user.click(screen.getByRole('button', { name: /send message/i }))
    expect(await screen.findByText(/must be valid json/i)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText(/params/i), { target: { value: '{}' } })
    await selectOption(user, /method/i, 'tasks/get')
    await user.click(screen.getByRole('button', { name: /send message/i }))
    expect(await screen.findByText(/"reply"/)).toBeInTheDocument()
  })

  it('names unknown agents honestly on 404', async () => {
    const user = userEvent.setup()
    server.use(http.get('*/v1/a2a/:agent/card', () => new HttpResponse('x', { status: 404 })))
    renderApp(<A2aPage />, { gatewayKey: 'gw-test' })
    await user.type(screen.getByLabelText(/agent/i), 'ghost')
    await user.click(screen.getByRole('button', { name: /load card/i }))
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/not found/i)
    })
  })

  it('rejects non-object params and reports invoke failures', async () => {
    const user = userEvent.setup()
    let calls = 0
    server.use(
      http.get('*/v1/a2a/:agent/card', () => HttpResponse.json(CARD)),
      http.post('*/v1/a2a/:agent', () => {
        calls += 1
        return new HttpResponse('x', { status: 502 })
      }),
    )
    renderApp(<A2aPage />, { gatewayKey: 'gw-test' })
    await user.type(screen.getByLabelText(/agent/i), 'helper')
    await user.click(screen.getByRole('button', { name: /load card/i }))
    await screen.findByText('Helper')
    await selectOption(user, /method/i, 'tasks/cancel')
    fireEvent.change(screen.getByLabelText(/params/i), { target: { value: '[1]' } })
    await user.click(screen.getByRole('button', { name: /send message/i }))
    expect(await screen.findByText(/must be a json object/i)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText(/params/i), { target: { value: '{}' } })
    await user.click(screen.getByRole('button', { name: /send message/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/could serve/i)
    expect(calls).toBe(1)
  })

  it('loads cards without descriptions honestly', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/a2a/:agent/card', () =>
        HttpResponse.json({
          protocolVersion: '0.3',
          name: 'Quiet',
          url: 'http://localhost:8080/v1/a2a/quiet',
          description: null,
          version: null,
        }),
      ),
    )
    renderApp(<A2aPage />, { gatewayKey: 'gw-test' })
    await user.type(screen.getByLabelText(/agent/i), 'quiet')
    await user.click(screen.getByRole('button', { name: /load card/i }))
    expect(await screen.findByText(/no description/i)).toBeInTheDocument()
  })

  it('streams message frames and reports the count', async () => {
    const user = userEvent.setup()
    let version: string | null = null
    server.use(
      http.get('*/v1/a2a/:agent/card', () => HttpResponse.json(CARD)),
      http.post('*/v1/a2a/:agent', ({ request }) => {
        version = request.headers.get('A2A-Version')
        const stream = new ReadableStream<Uint8Array>({
          start(ctrl) {
            ctrl.enqueue(
              new TextEncoder().encode(
                'data: {"jsonrpc":"2.0","id":1,"result":{"part":1}}\n\ndata: {"jsonrpc":"2.0","id":2,"result":{"part":2}}\n\n',
              ),
            )
            ctrl.close()
          },
        })
        return new HttpResponse(stream, { headers: { 'content-type': 'text/event-stream' } })
      }),
    )
    renderApp(<A2aPage />, { gatewayKey: 'gw-test' })
    await user.type(screen.getByLabelText(/agent/i), 'helper')
    await user.click(screen.getByRole('button', { name: /load card/i }))
    await screen.findByText('Helper')
    await selectOption(user, /method/i, 'message/stream')
    await user.type(screen.getByLabelText(/version pin/i), '0.3')
    await user.click(screen.getByRole('button', { name: /send message/i }))
    expect(await screen.findByText(/2 frames/)).toBeInTheDocument()
    expect(version).toBe('0.3')
  })

  it('names stream truncation without waiting for a terminal frame', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/a2a/:agent/card', () => HttpResponse.json(CARD)),
      http.post('*/v1/a2a/:agent', () => {
        const stream = new ReadableStream<Uint8Array>({
          start(ctrl) {
            ctrl.enqueue(new TextEncoder().encode('data: {"jsonrpc":"2.0","id":1}\n\n'))
            setTimeout(() => {
              ctrl.error(new Error('socket reset'))
            }, 50)
          },
        })
        return new HttpResponse(stream, { headers: { 'content-type': 'text/event-stream' } })
      }),
    )
    renderApp(<A2aPage />, { gatewayKey: 'gw-test' })
    await user.type(screen.getByLabelText(/agent/i), 'helper')
    await user.click(screen.getByRole('button', { name: /load card/i }))
    await screen.findByText('Helper')
    await selectOption(user, /method/i, 'message/stream')
    await user.click(screen.getByRole('button', { name: /send message/i }))
    expect(await screen.findByText(/truncated after 1 frames/i)).toBeInTheDocument()
  })

  it('names the throttle wait on 429 invokes', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/a2a/:agent/card', () => HttpResponse.json(CARD)),
      http.post(
        '*/v1/a2a/:agent',
        () => new HttpResponse('x', { status: 429, headers: { 'Retry-After': '7' } }),
      ),
    )
    renderApp(<A2aPage />, { gatewayKey: 'gw-test' })
    await user.type(screen.getByLabelText(/agent/i), 'helper')
    await user.click(screen.getByRole('button', { name: /load card/i }))
    await screen.findByText('Helper')
    await user.click(screen.getByRole('button', { name: /send message/i }))
    expect(await screen.findByText(/retry after 7s/i)).toBeInTheDocument()
  })

  it('names handshake failures with the server wait on streams', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/a2a/:agent/card', () => HttpResponse.json(CARD)),
      http.post(
        '*/v1/a2a/:agent',
        () => new HttpResponse('x', { status: 429, headers: { 'Retry-After': '7' } }),
      ),
    )
    renderApp(<A2aPage />, { gatewayKey: 'gw-test' })
    await user.type(screen.getByLabelText(/agent/i), 'helper')
    await user.click(screen.getByRole('button', { name: /load card/i }))
    await screen.findByText('Helper')
    await selectOption(user, /method/i, 'message/stream')
    await user.click(screen.getByRole('button', { name: /send message/i }))
    await waitFor(
      () => {
        expect(screen.getByText(/retry after 7s/i)).toBeInTheDocument()
      },
      { timeout: 20000 },
    )
  }, 25000)

  it('stops a live stream without an error', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/a2a/:agent/card', () => HttpResponse.json(CARD)),
      http.post('*/v1/a2a/:agent', () => {
        const stream = new ReadableStream<Uint8Array>({})
        return new HttpResponse(stream, { headers: { 'content-type': 'text/event-stream' } })
      }),
    )
    renderApp(<A2aPage />, { gatewayKey: 'gw-test' })
    await user.type(screen.getByLabelText(/agent/i), 'helper')
    await user.click(screen.getByRole('button', { name: /load card/i }))
    await screen.findByText('Helper')
    await selectOption(user, /method/i, 'message/stream')
    await user.click(screen.getByRole('button', { name: /send message/i }))
    await screen.findByRole('button', { name: /^stop$/i })
    await user.click(screen.getByRole('button', { name: /^stop$/i }))
    await waitFor(() => {
      expect(screen.queryByRole('button', { name: /^stop$/i })).not.toBeInTheDocument()
    })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('sends the version pin on unary invokes', async () => {
    const user = userEvent.setup()
    let version: string | null = null
    server.use(
      http.get('*/v1/a2a/:agent/card', () => HttpResponse.json(CARD)),
      http.post('*/v1/a2a/:agent', ({ request }) => {
        version = request.headers.get('A2A-Version')
        return HttpResponse.json({ jsonrpc: '2.0', id: 'a2a-1', result: { reply: 'hi' } })
      }),
    )
    renderApp(<A2aPage />, { gatewayKey: 'gw-test' })
    await user.type(screen.getByLabelText(/agent/i), 'helper')
    await user.click(screen.getByRole('button', { name: /load card/i }))
    await screen.findByText('Helper')
    await user.type(screen.getByLabelText(/version pin/i), '0.3')
    await user.click(screen.getByRole('button', { name: /send message/i }))
    expect(await screen.findByText(/"reply"/)).toBeInTheDocument()
    expect(version).toBe('0.3')
  })
})
