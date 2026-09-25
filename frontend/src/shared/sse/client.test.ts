import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  asError,
  backoffDelay,
  createIdempotencyKey,
  isRetryableHandshakeStatus,
  openSseStream,
  parseSseFrame,
} from './client.js'

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

describe('createIdempotencyKey', () => {
  it('mints unique keys, falling back without randomUUID', () => {
    const a = createIdempotencyKey()
    const b = createIdempotencyKey()
    expect(a.length).toBeGreaterThan(0)
    expect(a).not.toBe(b)
  })

  it('falls back to time plus random without crypto randomness', () => {
    const original = globalThis.crypto
    Object.defineProperty(globalThis, 'crypto', { value: {}, configurable: true })
    try {
      const key = createIdempotencyKey()
      expect(key.length).toBeGreaterThan(0)
    } finally {
      Object.defineProperty(globalThis, 'crypto', { value: original, configurable: true })
    }
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

  it('reports handshake response headers once the stream is accepted', async () => {
    const stream = new ReadableStream<Uint8Array>({
      start(ctrl) {
        ctrl.enqueue(new TextEncoder().encode('data: [DONE]\n\n'))
        ctrl.close()
      },
    })
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(stream, {
            headers: {
              'content-type': 'text/event-stream',
              'X-RateLimit-Remaining-RPM': '57',
            },
          }),
        ),
      ),
    )
    let remaining: string | null = null
    const seen: string[] = []
    await openSseStream({
      url: 'http://x/stream',
      headers: {},
      body: { model: 'm' },
      signal: new AbortController().signal,
      maxRetries: 0,
      onHeaders: (h) => {
        remaining = h.get('X-RateLimit-Remaining-RPM')
      },
      onMessage: (d) => {
        seen.push(d)
      },
    })
    expect(remaining).toBe('57')
    expect(seen).toEqual([])
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

  it('reuses one idempotency key across handshake retries', async () => {
    const keys: (string | null)[] = []
    const retries: number[] = []
    const fetchMock = vi.fn((_url: unknown, init?: RequestInit) => {
      keys.push(new Headers(init?.headers).get('Idempotency-Key'))
      if (keys.length === 1) return Promise.resolve(new Response('boom', { status: 500 }))
      return sseResponse('data: back\n\ndata: [DONE]\n\n')
    })
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
      onRetry: (attempt) => {
        retries.push(attempt)
      },
    })
    expect(seen).toEqual(['back'])
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(keys[0]).toBeTruthy()
    expect(keys[0]).toBe(keys[1])
    expect(retries).toEqual([1])
  })

  it('reports mid-stream failure after delivered frames as incomplete without retrying', async () => {
    const fetchMock = vi.fn(() => {
      const enc = new TextEncoder()
      let step = 0
      // Deliver three frames through `pull`, then error: `error()` inside
      // `start()` would discard the queue, so the failure must come after
      // the reads.
      const failing = new ReadableStream<Uint8Array>({
        pull(ctrl) {
          step += 1
          if (step <= 3) ctrl.enqueue(enc.encode(`data: frame${String(step)}\n\n`))
          else ctrl.error(new Error('boom'))
        },
      })
      return Promise.resolve(
        new Response(failing, { headers: { 'content-type': 'text/event-stream' } }),
      )
    })
    vi.stubGlobal('fetch', fetchMock)
    const seen: string[] = []
    let incompleteCalls = 0
    let deliveredCount = 0
    let errorCalls = 0
    let retryCalls = 0
    await openSseStream({
      url: 'http://x/stream',
      headers: {},
      signal: new AbortController().signal,
      maxRetries: 5,
      onMessage: (d) => {
        seen.push(d)
      },
      onIncomplete: (_e, delivered) => {
        incompleteCalls += 1
        deliveredCount = delivered
      },
      onError: () => {
        errorCalls += 1
      },
      onRetry: () => {
        retryCalls += 1
      },
    })
    // Exactly one POST: partial delivery never retries, so no second
    // request and no backoff wait.
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(seen).toEqual(['frame1', 'frame2', 'frame3'])
    expect(incompleteCalls).toBe(1)
    expect(deliveredCount).toBe(3)
    expect(errorCalls).toBe(0)
    expect(retryCalls).toBe(0)
  })

  it('classifies handshake statuses for retry', () => {
    for (const status of [400, 401, 403, 404, 422]) {
      expect(isRetryableHandshakeStatus(status)).toBe(false)
    }
    for (const status of [408, 429, 500, 502, 503]) {
      expect(isRetryableHandshakeStatus(status)).toBe(true)
    }
  })

  it('surfaces deterministic 400 denials without retry', async () => {
    const fetchMock = vi.fn(() => Promise.resolve(new Response('bad', { status: 400 })))
    vi.stubGlobal('fetch', fetchMock)
    let message = ''
    const started = Date.now()
    await openSseStream({
      url: 'http://x/stream',
      headers: {},
      signal: new AbortController().signal,
      maxRetries: 5,
      onMessage: () => {
        throw new Error('must not receive messages')
      },
      onError: (e) => {
        message = e.message
      },
    })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(message).toContain('HTTP 400')
    expect(Date.now() - started).toBeLessThan(900)
  })

  it('retries 429 and network failures', async () => {
    const throttled = vi.fn(() => Promise.resolve(new Response('slow', { status: 429 })))
    vi.stubGlobal('fetch', throttled)
    await openSseStream({
      url: 'http://x/stream',
      headers: {},
      signal: new AbortController().signal,
      maxRetries: 1,
      onMessage: () => {
        throw new Error('must not receive messages')
      },
    })
    expect(throttled).toHaveBeenCalledTimes(2)

    const dropped = vi.fn(() => Promise.reject(new TypeError('load failed')))
    vi.stubGlobal('fetch', dropped)
    await openSseStream({
      url: 'http://x/stream',
      headers: {},
      signal: new AbortController().signal,
      maxRetries: 1,
      onMessage: () => {
        throw new Error('must not receive messages')
      },
    })
    expect(dropped).toHaveBeenCalledTimes(2)
  })

  it('dispatches CRLF and CR delimited frames', async () => {
    const body = 'data: first\r\n\r\ndata: second\r\rdata: [DONE]\r\n\r\n'
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(
            new ReadableStream<Uint8Array>({
              start(ctrl) {
                ctrl.enqueue(new TextEncoder().encode(body))
                ctrl.close()
              },
            }),
            { headers: { 'content-type': 'text/event-stream' } },
          ),
        ),
      ),
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
    expect(seen).toEqual(['first', 'second'])
    expect(done).toBe(true)
  })

  it('reassembles CRLF pairs split across chunk boundaries', async () => {
    let step = 0
    const split = new ReadableStream<Uint8Array>({
      pull(ctrl) {
        step += 1
        if (step === 1) ctrl.enqueue(new TextEncoder().encode('data: split\r'))
        else if (step === 2) ctrl.enqueue(new TextEncoder().encode('\n\ndata: [DONE]\n\n'))
        else ctrl.close()
      },
    })
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(new Response(split, { headers: { 'content-type': 'text/event-stream' } })),
      ),
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
    expect(seen).toEqual(['split'])
    expect(done).toBe(true)
  })

  it('sends a caller-provided idempotency key verbatim', async () => {
    const keys: (string | null)[] = []
    const fetchMock = vi.fn((_url: unknown, init?: RequestInit) => {
      keys.push(new Headers(init?.headers).get('Idempotency-Key'))
      return Promise.resolve(new Response('boom', { status: 500 }))
    })
    vi.stubGlobal('fetch', fetchMock)
    await openSseStream({
      url: 'http://x/stream',
      headers: {},
      signal: new AbortController().signal,
      maxRetries: 1,
      idempotencyKey: 'fixed-key',
      onMessage: () => {
        throw new Error('must not receive messages')
      },
    })
    expect(keys).toEqual(['fixed-key', 'fixed-key'])
  })

  it('sends credential-free GET streams without a body', async () => {
    const seenCalls: { method: string | undefined; body: unknown }[] = []
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      seenCalls.push({ method: init?.method, body: init?.body })
      expect(url).toBe('http://x/stream')
      return sseResponse('data: back\n\ndata: [DONE]\n\n')
    })
    vi.stubGlobal('fetch', fetchMock)
    const seen: string[] = []
    await openSseStream({
      url: 'http://x/stream',
      method: 'GET',
      headers: {},
      signal: new AbortController().signal,
      maxRetries: 0,
      onMessage: (d) => {
        seen.push(d)
      },
    })
    expect(fetchMock).toHaveBeenCalledWith(
      'http://x/stream',
      expect.objectContaining({ method: 'GET' }),
    )
    expect(seenCalls[0]?.method).toBe('GET')
    expect(seenCalls[0]?.body).toBeUndefined()
    expect(seen).toEqual(['back'])
  })
  it('drops oversized frames with a malformed count and keeps streaming', async () => {
    const body = `data: ${'y'.repeat(1_200_000)}\n\ndata: ok\n\ndata: [DONE]\n\n`
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(
            new ReadableStream<Uint8Array>({
              start(ctrl) {
                ctrl.enqueue(new TextEncoder().encode(body))
                ctrl.close()
              },
            }),
            { headers: { 'content-type': 'text/event-stream' } },
          ),
        ),
      ),
    )
    const seen: string[] = []
    let malformed = 0
    let done = false
    await openSseStream({
      url: 'http://x/stream',
      headers: {},
      signal: new AbortController().signal,
      maxRetries: 0,
      onMessage: (d) => {
        seen.push(d)
      },
      onMalformed: (count) => {
        malformed = count
      },
      onDone: () => {
        done = true
      },
    })
    expect(seen).toEqual(['ok'])
    expect(malformed).toBe(1)
    expect(done).toBe(true)
  })

  it('bounds delimiter-free floods with a malformed count', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(
            new ReadableStream<Uint8Array>({
              start(ctrl) {
                ctrl.enqueue(new TextEncoder().encode('x'.repeat(9_000_000)))
                ctrl.close()
              },
            }),
            { headers: { 'content-type': 'text/event-stream' } },
          ),
        ),
      ),
    )
    const seen: string[] = []
    let malformed = 0
    let message = ''
    await openSseStream({
      url: 'http://x/stream',
      headers: {},
      signal: new AbortController().signal,
      maxRetries: 5,
      onMessage: (d) => {
        seen.push(d)
      },
      onMalformed: (count) => {
        malformed = count
      },
      onError: (e) => {
        message = e.message
      },
    })
    expect(seen).toEqual([])
    expect(malformed).toBeGreaterThan(0)
    expect(message).toContain('budget')
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
