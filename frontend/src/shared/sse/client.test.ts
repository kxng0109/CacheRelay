import { afterEach, describe, expect, it, vi } from 'vitest'
import { asError, backoffDelay, openSseStream, parseSseFrame } from './client.js'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('parseSseFrame', () => {
  it('extracts a single data payload', () => {
    expect(parseSseFrame('data: hello')).toBe('hello')
  })

  it('joins multi-line data payloads', () => {
    expect(parseSseFrame('data: a\ndata: b')).toBe('a\nb')
  })

  it('ignores comments and returns null without data', () => {
    expect(parseSseFrame(': ping\nevent: x')).toBeNull()
  })

  it('returns null for empty frames', () => {
    expect(parseSseFrame('')).toBeNull()
  })

  it('rejects adversarial javascript payloads as opaque text (never executes)', () => {
    const out = parseSseFrame('data: <script>alert(1)</script>')
    expect(out).toBe('<script>alert(1)</script>')
  })
})

describe('backoffDelay', () => {
  it('grows exponentially and stays within cap plus jitter', () => {
    const d0 = backoffDelay(0)
    const d3 = backoffDelay(3)
    expect(d0).toBeGreaterThanOrEqual(1000)
    expect(d0).toBeLessThan(1600)
    expect(d3).toBeGreaterThanOrEqual(8000)
    expect(d3).toBeLessThan(8600)
  })

  it('caps at 30s plus jitter', () => {
    expect(backoffDelay(99)).toBeLessThan(30_600)
  })
})

describe('openSseStream', () => {
  const sseResponse = (body: string): Promise<Response> => {
    const stream = new ReadableStream<Uint8Array>({
      start(ctrl) {
        ctrl.enqueue(new TextEncoder().encode(body))
        ctrl.close()
      },
    })
    return Promise.resolve(
      new Response(stream, { headers: { 'content-type': 'text/event-stream' } }),
    )
  }

  it('streams frames then [DONE] without retry', async () => {
    const body = 'data: {"choices":[{"delta":{"content":"hi"}}]}\n\ndata: [DONE]\n\n'
    vi.stubGlobal(
      'fetch',
      vi.fn(() => sseResponse(body)),
    )
    const seen: string[] = []
    let done = false
    await openSseStream({
      url: 'http://x/stream',
      headers: {},
      body: { model: 'm' },
      signal: new AbortController().signal,
      maxRetries: 0,
      onMessage: (d) => {
        seen.push(d)
      },
      onDone: () => {
        done = true
      },
    })
    expect(seen).toHaveLength(1)
    expect(done).toBe(true)
  })

  it('counts malformed frames without dying', async () => {
    const body = ': ping\n\nnot-a-frame\n\ndata: ok\n\ndata: [DONE]\n\n'
    vi.stubGlobal(
      'fetch',
      vi.fn(() => sseResponse(body)),
    )
    const seen: string[] = []
    let malformed = 0
    await openSseStream({
      url: 'http://x/stream',
      headers: {},
      signal: new AbortController().signal,
      maxRetries: 0,
      onMessage: (d) => {
        seen.push(d)
      },
      onMalformed: (c) => {
        malformed = c
      },
    })
    expect(seen).toEqual(['ok'])
    expect(malformed).toBe(2)
  })

  it('reports handshake failure without retry when retries are zero', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response('err', { status: 500 }))),
    )
    let message = ''
    const unexpected: string[] = []
    await openSseStream({
      url: 'http://x/stream',
      headers: {},
      signal: new AbortController().signal,
      maxRetries: 0,
      onMessage: (d) => {
        unexpected.push(d)
      },
      onError: (e) => {
        message = e.message
      },
    })
    expect(message).toContain('HTTP 500')
    expect(unexpected).toEqual([])
  })

  it('rejects non-event-stream content types', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response('{}', { status: 200 }))),
    )
    let message = ''
    await openSseStream({
      url: 'http://x/stream',
      headers: {},
      signal: new AbortController().signal,
      maxRetries: 0,
      onMessage: () => {
        throw new Error('must not receive messages')
      },
      onError: (e) => {
        message = e.message
      },
    })
    expect(message).toContain('handshake failed')
  })

  it('rejects empty bodies', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(null, { status: 200, headers: { 'content-type': 'text/event-stream' } }),
        ),
      ),
    )
    let message = ''
    await openSseStream({
      url: 'http://x/stream',
      headers: {},
      signal: new AbortController().signal,
      maxRetries: 0,
      onMessage: () => {
        throw new Error('must not receive messages')
      },
      onError: (e) => {
        message = e.message
      },
    })
    expect(message).toContain('empty body')
  })

  it('finishes cleanly when the server closes without [DONE]', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => sseResponse('data: tail\n\n')),
    )
    const seen: string[] = []
    let done = false
    await openSseStream({
      url: 'http://x/stream',
      headers: {},
      signal: new AbortController().signal,
      maxRetries: 0,
      onMessage: (d) => {
        seen.push(d)
      },
      onDone: () => {
        done = true
      },
    })
    expect(seen).toEqual(['tail'])
    expect(done).toBe(true)
  })

  it('retries once after a failed handshake then streams', async () => {
    const fetchMock = vi
      .fn(() => Promise.resolve(new Response('boom', { status: 500 })))
      .mockImplementationOnce(() => Promise.resolve(new Response('boom', { status: 500 })))
    fetchMock.mockImplementation(() => sseResponse('data: back\n\ndata: [DONE]\n\n'))
    vi.stubGlobal('fetch', fetchMock)
    const seen: string[] = []
    await openSseStream({
      url: 'http://x/stream',
      headers: {},
      signal: new AbortController().signal,
      maxRetries: 1,
      onMessage: (d) => {
        seen.push(d)
      },
    })
    expect(seen).toEqual(['back'])
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('reports abort instead of reconnecting when stopped mid-backoff', async () => {
    const fetchMock = vi.fn(() => Promise.resolve(new Response('boom', { status: 500 })))
    vi.stubGlobal('fetch', fetchMock)
    const ctrl = new AbortController()
    let message = ''
    const pending = openSseStream({
      url: 'http://x/stream',
      headers: {},
      signal: ctrl.signal,
      maxRetries: 5,
      onMessage: () => {
        throw new Error('must not receive messages')
      },
      onError: (e) => {
        message = e.message
      },
    })
    setTimeout(() => {
      ctrl.abort()
    }, 100)
    await pending
    expect(message).toBe('Stream aborted.')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('times out silent streams with the heartbeat watchdog', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => {
        const hanging = new ReadableStream<Uint8Array>({})
        return Promise.resolve(
          new Response(hanging, { headers: { 'content-type': 'text/event-stream' } }),
        )
      }),
    )
    let done = false
    await openSseStream({
      url: 'http://x/stream',
      headers: {},
      signal: new AbortController().signal,
      maxRetries: 0,
      heartbeatMs: 60,
      onMessage: () => {
        throw new Error('must not receive messages')
      },
      onDone: () => {
        done = true
      },
    })
    expect(done).toBe(true)
  }, 10_000)

  it('settles silently when pre-aborted without an error callback', async () => {
    const fetchMock = vi.fn(() => Promise.resolve(new Response('boom', { status: 500 })))
    vi.stubGlobal('fetch', fetchMock)
    const ctrl = new AbortController()
    ctrl.abort()
    await openSseStream({
      url: 'http://x/stream',
      headers: {},
      signal: ctrl.signal,
      maxRetries: 0,
      onMessage: () => {
        throw new Error('must not receive messages')
      },
    })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})

describe('asError', () => {
  it('passes errors through untouched', () => {
    const err = new TypeError('x')
    expect(asError(err)).toBe(err)
  })

  it('wraps non-error reasons into errors', () => {
    expect(asError('string-throw')).toEqual(new Error('string-throw'))
    expect(asError(null)).toEqual(new Error('null'))
  })
})
