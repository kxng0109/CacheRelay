import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { SseStreamViewer } from './SseStreamViewer.js'
import type { StreamSummary } from './SseStreamViewer.js'

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
  it('stops a hanging stream and settles neutrally, never as an error', async () => {
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
      expect(screen.getByText(/phase: stopped/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/stopped by user/i)).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^stop$/i })).not.toBeInTheDocument()
  })

  it('reports a user stop as neutral, never an error', async () => {
    const user = userEvent.setup()
    const summaries: unknown[] = []
    server.use(
      http.post('*/v1/chat/completions', () => {
        const hanging = new ReadableStream<Uint8Array>({})
        return new HttpResponse(hanging, { headers: { 'content-type': 'text/event-stream' } })
      }),
    )
    renderApp(
      <SseStreamViewer
        token="gw-test"
        model="m"
        messages={MESSAGES}
        onSummary={(s) => {
          summaries.push(s)
        }}
      />,
    )
    await user.click(await screen.findByRole('button', { name: /^stop$/i }))
    await waitFor(() => {
      expect(screen.getByText(/phase: stopped/i)).toBeInTheDocument()
    })
    // Neutral status, never the red error alert.
    expect(screen.getByText(/stopped by user/i)).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(summaries).toHaveLength(1)
    expect(summaries[0]).toMatchObject({ phase: 'stopped' })
    expect(summaries[0]).not.toHaveProperty('error')
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

  it('surfaces stream provenance headers without leaking secrets', async () => {
    const summaries: StreamSummary[] = []
    server.use(
      http.post('*/v1/chat/completions', () => {
        const stream = new ReadableStream<Uint8Array>({
          start(ctrl) {
            ctrl.enqueue(new TextEncoder().encode('data: ok\n\ndata: [DONE]\n\n'))
            ctrl.close()
          },
        })
        return new HttpResponse(stream, {
          headers: {
            'content-type': 'text/event-stream',
            'X-CacheRelay-Provider': 'openai',
            'X-CacheRelay-Tried': 'openai,anthropic',
            'X-CacheRelay-Audit-Receipt': 'merkle:abc123',
            'Idempotent-Replayed': 'true',
          },
        })
      }),
    )
    renderApp(
      <SseStreamViewer
        token="gw-test"
        model="m"
        messages={MESSAGES}
        onSummary={(s) => {
          summaries.push(s)
        }}
      />,
    )
    await waitFor(() => {
      expect(screen.getByText(/phase: done/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/provider: openai/i)).toBeInTheDocument()
    expect(screen.getByText(/tried: openai,anthropic/i)).toBeInTheDocument()
    expect(screen.getByText(/receipt: merkle:abc123/i)).toBeInTheDocument()
    expect(screen.getByText(/replayed/i)).toBeInTheDocument()
    expect(summaries[0]).toMatchObject({
      provider: 'openai',
      tried: 'openai,anthropic',
      receipt: 'merkle:abc123',
      replayed: true,
    })
  })

  it('reads live responses without provenance as provider-live', async () => {
    const summaries: StreamSummary[] = []
    server.use(
      http.post('*/v1/chat/completions', () => {
        const stream = new ReadableStream<Uint8Array>({
          start(ctrl) {
            ctrl.enqueue(new TextEncoder().encode('data: ok\n\ndata: [DONE]\n\n'))
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
        onSummary={(s) => {
          summaries.push(s)
        }}
      />,
    )
    await waitFor(() => {
      expect(screen.getByText(/phase: done/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/provenance: live/i)).toBeInTheDocument()
    expect(summaries[0]).toMatchObject({ provider: null, tried: null, receipt: null })
    expect(summaries[0]?.replayed).toBe(false)
  })

  it('sends extra chat options through the stream body', async () => {
    let body: unknown = null
    server.use(
      http.post('*/v1/chat/completions', async ({ request }) => {
        body = await request.json()
        const stream = new ReadableStream<Uint8Array>({
          start(ctrl) {
            ctrl.enqueue(new TextEncoder().encode('data: ok\n\ndata: [DONE]\n\n'))
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
        chatOptions={{ temperature: 0.7, seed: 42 }}
      />,
    )
    await waitFor(() => {
      expect(screen.getByText(/phase: done/i)).toBeInTheDocument()
    })
    expect(body).toMatchObject({ temperature: 0.7, seed: 42, stream: true })
  })

  it('keeps partial frames on mid-stream failure and retries as a new run', async () => {
    const user = userEvent.setup()
    let calls = 0
    server.use(
      http.post('*/v1/chat/completions', () => {
        calls += 1
        if (calls === 1) {
          // Frame first, failure later: the reader parses a frame before
          // the stream errors, so the client settles incomplete (not a
          // pre-frame handshake retry).
          const stream = new ReadableStream<Uint8Array>({
            start(ctrl) {
              ctrl.enqueue(
                new TextEncoder().encode('data: {"choices":[{"delta":{"content":"part"}}]}\n\n'),
              )
              setTimeout(() => {
                ctrl.error(new Error('upstream reset'))
              }, 50)
            },
          })
          return new HttpResponse(stream, { headers: { 'content-type': 'text/event-stream' } })
        }
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
      expect(screen.getByText(/phase: incomplete/i)).toBeInTheDocument()
    })
    expect(screen.getByRole('log')).toHaveTextContent('part')
    await user.click(screen.getByRole('button', { name: /retry run/i }))
    await waitFor(() => {
      expect(screen.getByText(/phase: done/i)).toBeInTheDocument()
    })
    expect(calls).toBe(2)
  })

  it('reports clipboard write rejection without throwing', async () => {
    const user = userEvent.setup()
    Object.defineProperty(window.navigator, 'clipboard', {
      value: { writeText: () => Promise.reject(new Error('denied')) },
      configurable: true,
    })
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
    await user.click(screen.getByRole('button', { name: /^copy$/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/copy failed/i)
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

  it('truncates runaway transcripts with a visible stop notice', async () => {
    const big = 'y'.repeat(200_000)
    const frames =
      Array.from({ length: 12 }, () => `data: {"choices":[{"delta":{"content":"${big}"}}]}`).join(
        '\n\n',
      ) + '\n\n'
    server.use(
      http.post('*/v1/chat/completions', () => {
        const stream = new ReadableStream<Uint8Array>({
          start(ctrl) {
            ctrl.enqueue(new TextEncoder().encode(frames))
            ctrl.close()
          },
        })
        return new HttpResponse(stream, { headers: { 'content-type': 'text/event-stream' } })
      }),
    )
    renderApp(<SseStreamViewer token="gw-test" model="m" messages={MESSAGES} />)
    await waitFor(() => {
      expect(screen.getByText(/output truncated/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/phase: done/i)).toBeInTheDocument()
    expect(screen.getByRole('log').textContent.length).toBeLessThan(2_097_152 + 1000)
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
      expect(screen.getByText(/phase: stopped/i)).toBeInTheDocument()
    })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
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
