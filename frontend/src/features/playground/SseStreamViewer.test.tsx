import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { SseStreamViewer } from './SseStreamViewer.js'

const MESSAGES = [{ role: 'user' as const, content: 'hi' }]

describe('SseStreamViewer', () => {
  it('stops a hanging stream and settles gracefully', async () => {
    const user = userEvent.setup()
    server.use(
      http.post('*/v1/chat/completions', () => {
        const hanging = new ReadableStream<Uint8Array>({})
        return new HttpResponse(hanging, { headers: { 'content-type': 'text/event-stream' } })
      }),
    )
    renderApp(<SseStreamViewer token="gw-test" model="m" messages={MESSAGES} />)
    await user.click(await screen.findByRole('button', { name: /^stop$/i }))
    await waitFor(() => {
      expect(screen.getByText(/phase: done/i)).toBeInTheDocument()
    })
    expect(screen.queryByRole('button', { name: /^stop$/i })).not.toBeInTheDocument()
  })

  it('counts malformed frames while streaming', async () => {
    server.use(
      http.post('*/v1/chat/completions', () => {
        const stream = new ReadableStream<Uint8Array>({
          start(ctrl) {
            ctrl.enqueue(new TextEncoder().encode(': ping\n\nnope\n\ndata: ok\n\ndata: [DONE]\n\n'))
            ctrl.close()
          },
        })
        return new HttpResponse(stream, { headers: { 'content-type': 'text/event-stream' } })
      }),
    )
    renderApp(<SseStreamViewer token="gw-test" model="m" messages={MESSAGES} />)
    await waitFor(() => {
      expect(screen.getByText(/phase: done/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/malformed: 2/i)).toBeInTheDocument()
    expect(screen.getByRole('log')).toHaveTextContent('ok')
  })

  it('appends non-chat payloads literally', async () => {
    server.use(
      http.post('*/v1/chat/completions', () => {
        const stream = new ReadableStream<Uint8Array>({
          start(ctrl) {
            const frames = [
              'data: 42',
              'data: {"foo":1}',
              'data: {"choices":[]}',
              'data: {"choices":[{}]}',
              'data: {"choices":[{"message":{"content":"M"}}]}',
              'data: [DONE]',
            ].join('\n\n')
            ctrl.enqueue(new TextEncoder().encode(`${frames}\n\n`))
            ctrl.close()
          },
        })
        return new HttpResponse(stream, { headers: { 'content-type': 'text/event-stream' } })
      }),
    )
    renderApp(<SseStreamViewer token="gw-test" model="m" messages={MESSAGES} />)
    await waitFor(() => {
      expect(screen.getByText(/phase: done/i)).toBeInTheDocument()
    })
    const log = screen.getByRole('log')
    expect(log).toHaveTextContent('42')
    expect(log).toHaveTextContent('M')
  })

  it('surfaces handshake failures as alerts', async () => {
    server.use(http.post('*/v1/chat/completions', () => new HttpResponse('x', { status: 503 })))
    renderApp(
      <SseStreamViewer
        token="gw-test"
        model="m"
        messages={MESSAGES}
        streamOptions={{ maxRetries: 0 }}
      />,
    )
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(
        /temporarily unavailable|handshake failed/i,
      )
    })
    expect(screen.getByText(/phase: error/i)).toBeInTheDocument()
  })

  it('cancels the reader on unmount without warnings', async () => {
    server.use(
      http.post('*/v1/chat/completions', () => {
        const hanging = new ReadableStream<Uint8Array>({})
        return new HttpResponse(hanging, { headers: { 'content-type': 'text/event-stream' } })
      }),
    )
    const rendered = renderApp(<SseStreamViewer token="gw-test" model="m" messages={MESSAGES} />)
    await screen.findByRole('button', { name: /^stop$/i })
    rendered.unmount()
  })

  it('stops before the handshake resolves without a reader', async () => {
    const user = userEvent.setup()
    let release!: () => void
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    server.use(
      http.post('*/v1/chat/completions', async () => {
        await gate
        return new HttpResponse('data: [DONE]\n\n', {
          headers: { 'content-type': 'text/event-stream' },
        })
      }),
    )
    renderApp(<SseStreamViewer token="gw-test" model="m" messages={MESSAGES} />)
    await user.click(await screen.findByRole('button', { name: /^stop$/i }))
    release()
    await waitFor(() => {
      expect(screen.getByText(/phase: error/i)).toBeInTheDocument()
    })
  })

  it('honors explicit retry and heartbeat options', async () => {
    server.use(
      http.post('*/v1/chat/completions', () => {
        const stream = new ReadableStream<Uint8Array>({
          start(ctrl) {
            ctrl.enqueue(new TextEncoder().encode('data: [DONE]\n\n'))
            ctrl.close()
          },
        })
        return new HttpResponse(stream, { headers: { 'content-type': 'text/event-stream' } })
      }),
    )
    renderApp(
      <SseStreamViewer
        token="gw-test"
        model="m"
        messages={MESSAGES}
        streamOptions={{ maxRetries: 2, heartbeatMs: 1000 }}
      />,
    )
    await waitFor(() => {
      expect(screen.getByText(/phase: done/i)).toBeInTheDocument()
    })
  })
})
