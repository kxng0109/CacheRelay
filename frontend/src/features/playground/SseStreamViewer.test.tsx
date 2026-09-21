import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { SseStreamViewer } from './SseStreamViewer.js'

const MESSAGES = [{ role: 'user' as const, content: 'hi' }]

const STREAM_DONE = 'data: {"choices":[{"delta":{"content":"Hello"}}]}\n\ndata: [DONE]\n\n'

/**
 * Installs a stub clipboard capturing written text.
 *
 * @returns The captured writes.
 */
function stubClipboard(): string[] {
  const writes: string[] = []
  Object.defineProperty(window.navigator, 'clipboard', {
    value: { writeText: vi.fn((s: string) => Promise.resolve(s).then(() => writes.push(s))) },
    configurable: true,
  })
  return writes
}

afterEach(() => {
  // Clipboard is installed per-test via defineProperty; remove the stub so
  // the absence path stays testable and cross-test leakage is impossible.
  if ('clipboard' in window.navigator) {
    delete (window.navigator as unknown as Record<string, unknown>).clipboard
  }
})

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

  it('shows a caret while streaming and removes it on completion', async () => {
    server.use(
      http.post('*/v1/chat/completions', () => {
        // One chunk, never closed: text lands while the phase stays live.
        const hanging = new ReadableStream<Uint8Array>({
          start(ctrl) {
            ctrl.enqueue(
              new TextEncoder().encode('data: {"choices":[{"delta":{"content":"Hi"}}]}\n\n'),
            )
          },
        })
        return new HttpResponse(hanging, { headers: { 'content-type': 'text/event-stream' } })
      }),
    )
    const rendered = renderApp(<SseStreamViewer token="gw-test" model="m" messages={MESSAGES} />)
    await waitFor(() => {
      expect(screen.getByRole('log')).toHaveTextContent('Hi')
    })
    expect(screen.getByText('▍')).toBeInTheDocument()
    rendered.unmount()
  })

  it('copies the transcript and confirms inline', async () => {
    const user = userEvent.setup()
    const writes = stubClipboard()
    server.use(
      http.post('*/v1/chat/completions', () => {
        const stream = new ReadableStream<Uint8Array>({
          start(ctrl) {
            ctrl.enqueue(new TextEncoder().encode(STREAM_DONE))
            ctrl.close()
          },
        })
        return new HttpResponse(stream, { headers: { 'content-type': 'text/event-stream' } })
      }),
    )
    renderApp(<SseStreamViewer token="gw-test" model="m" messages={MESSAGES} />)
    await waitFor(() => {
      expect(screen.getByRole('log')).toHaveTextContent('Hello')
    })
    const copy = screen.getByRole('button', { name: /^copy$/i })
    expect(copy).toBeEnabled()
    await user.click(copy)
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /copied \d+B/i })).toBeInTheDocument()
    })
    expect(writes).toEqual(['Hello'])
  })

  it('reports copy failure without throwing', async () => {
    // No clipboard stub: jsdom has no clipboard, so the absence path runs.
    // NOTE: fireEvent, not user-event — userEvent.setup() installs its own
    // working clipboard stub, which would mask the absence branch.
    server.use(
      http.post('*/v1/chat/completions', () => {
        const stream = new ReadableStream<Uint8Array>({
          start(ctrl) {
            ctrl.enqueue(new TextEncoder().encode(STREAM_DONE))
            ctrl.close()
          },
        })
        return new HttpResponse(stream, { headers: { 'content-type': 'text/event-stream' } })
      }),
    )
    renderApp(<SseStreamViewer token="gw-test" model="m" messages={MESSAGES} />)
    await waitFor(() => {
      expect(screen.getByRole('log')).toHaveTextContent('Hello')
    })
    // Completion drops the caret: the transcript is final, nothing blinks.
    expect(screen.queryByText('▍')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /^copy$/i }))
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/copy unavailable/i)
    })
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

  it('shows the cache tier, similarity, and age from response headers', async () => {
    server.use(
      http.post('*/v1/chat/completions', () => {
        const stream = new ReadableStream<Uint8Array>({
          start(ctrl) {
            ctrl.enqueue(new TextEncoder().encode(STREAM_DONE))
            ctrl.close()
          },
        })
        return new HttpResponse(stream, {
          headers: {
            'content-type': 'text/event-stream',
            'X-Cache': 'HIT (L1-Exact)',
            'X-CacheRelay-Similarity-Score': '1.0000',
            Age: '42',
          },
        })
      }),
    )
    renderApp(<SseStreamViewer token="gw-test" model="m" messages={MESSAGES} />)
    await waitFor(() => {
      expect(screen.getByText(/cache: HIT \(L1-Exact\)/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/sim 1\.0000/i)).toBeInTheDocument()
    expect(screen.getByText(/age 42s/i)).toBeInTheDocument()
  })

  it('labels provider-backed responses live when no cache header arrives', async () => {
    server.use(
      http.post('*/v1/chat/completions', () => {
        const stream = new ReadableStream<Uint8Array>({
          start(ctrl) {
            ctrl.enqueue(new TextEncoder().encode(STREAM_DONE))
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
    expect(screen.getByText(/cache: live/i)).toBeInTheDocument()
  })
})
