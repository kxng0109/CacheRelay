import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  GatewayClient,
  isStreamingEnabled,
  parseRateLimit,
  resolveApiBase,
  safeErrorMessage,
  toErrorMessage,
} from './client.js'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('parseRateLimit', () => {
  it('parses numeric headers', () => {
    const h = new Headers({
      'X-RateLimit-Limit': '60',
      'X-RateLimit-Remaining': '59',
      'X-RateLimit-Reset': '12',
      'Retry-After': '5',
    })
    expect(parseRateLimit(h)).toEqual({ limit: 60, remaining: 59, reset: 12, retryAfter: 5 })
  })

  it('returns nulls when headers are absent', () => {
    expect(parseRateLimit(new Headers())).toEqual({
      limit: null,
      remaining: null,
      reset: null,
      retryAfter: null,
    })
  })

  it('rejects non-numeric header injection', () => {
    const h = new Headers({ 'X-RateLimit-Remaining': '1; DROP' })
    expect(parseRateLimit(h).remaining).toBeNull()
  })
})

describe('safeErrorMessage', () => {
  it('maps 429 without leaking internals', () => {
    expect(safeErrorMessage(429, '')).toContain('Rate limit')
  })

  it('maps default-deny 403 to a policy message', () => {
    const body = JSON.stringify({
      type: 'https://cacherelay.io/problems/forbidden',
      title: 'Forbidden',
      status: 403,
      detail: 'Forbidden',
      instance: '/v1/admin/x',
    })
    expect(safeErrorMessage(403, body)).toContain('gateway policy')
  })

  it('surfaces gateway error messages for known shapes', () => {
    const body = JSON.stringify({ error: { message: 'Bad model', type: 'x', code: null } })
    expect(safeErrorMessage(400, body)).toBe('Bad model')
  })

  it('falls back safely on malformed bodies', () => {
    expect(safeErrorMessage(500, '{{{not json')).toContain('HTTP 500')
  })

  it('maps 404 and 400 to actionable messages', () => {
    expect(safeErrorMessage(404, '')).toContain('not found')
    expect(safeErrorMessage(400, '')).toContain('Invalid request')
  })

  it('prefers backend detail text for non-policy errors', () => {
    const body = JSON.stringify({
      type: 'https://example.com/problems/x',
      title: 'Bad',
      status: 400,
      detail: 'Model xyz is unknown.',
      instance: '/v1/chat/completions',
    })
    expect(safeErrorMessage(400, body)).toBe('Model xyz is unknown.')
  })
})

describe('resolveApiBase', () => {
  it('falls back to same-origin when unconfigured', () => {
    vi.stubEnv('VITE_API_BASE_URL', '')
    expect(resolveApiBase()).toBe('')
  })

  it('falls back to same-origin when absent', () => {
    vi.stubEnv('VITE_API_BASE_URL', undefined)
    expect(resolveApiBase()).toBe('')
  })
})

describe('isStreamingEnabled', () => {
  it('streams by default', () => {
    vi.stubEnv('VITE_FEATURE_STREAMING', undefined)
    expect(isStreamingEnabled()).toBe(true)
  })

  it('disables on explicit false in any casing', () => {
    vi.stubEnv('VITE_FEATURE_STREAMING', 'false')
    expect(isStreamingEnabled()).toBe(false)
    vi.stubEnv('VITE_FEATURE_STREAMING', 'FALSE')
    expect(isStreamingEnabled()).toBe(false)
  })

  it('streams for any other value', () => {
    vi.stubEnv('VITE_FEATURE_STREAMING', 'true')
    expect(isStreamingEnabled()).toBe(true)
    vi.stubEnv('VITE_FEATURE_STREAMING', '')
    expect(isStreamingEnabled()).toBe(true)
  })
})

describe('toErrorMessage', () => {
  it('prefers the error message', () => {
    expect(toErrorMessage(new Error('boom'), 'fallback')).toBe('boom')
  })

  it('falls back for non-error throws', () => {
    expect(toErrorMessage('string-throw', 'fallback')).toBe('fallback')
    expect(toErrorMessage(null, 'fallback')).toBe('fallback')
  })
})

describe('GatewayClient transport', () => {
  it('returns parsed json on success', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(new Response(JSON.stringify({ data: [{ id: 'x' }] }), { status: 200 })),
      ),
    )
    const out = await new GatewayClient({ base: '', token: 'gw-test' }).models()
    expect(out).toEqual({ data: [{ id: 'x' }] })
  })

  it('throws ApiError with status and headers on failure', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify({ error: { message: 'nope', type: 't', code: null } }), {
            status: 429,
            headers: { 'X-RateLimit-Remaining': '0', 'Retry-After': '7' },
          }),
        ),
      ),
    )
    const err = await new GatewayClient({ base: '', token: 'gw-test' })
      .models()
      .catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    const apiErr = err as ApiError
    expect(apiErr.status).toBe(429)
    expect(apiErr.rateLimit.retryAfter).toBe(7)
    expect(apiErr.message).toContain('Rate limit')
  })

  it('maps unreachable networks with the cause attached', async () => {
    const cause = new TypeError('refused')
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(cause)),
    )
    const err = await new GatewayClient({ base: '', token: 'gw-test' })
      .models()
      .catch((e: unknown) => e)
    expect(err).toBeInstanceOf(Error)
    expect((err as Error).message).toContain('Network unreachable')
    expect((err as Error).cause).toBe(cause)
  })

  it('propagates aborts untouched', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new DOMException('Stopped', 'AbortError'))),
    )
    const ctrl = new AbortController()
    const err = await new GatewayClient({ base: '', token: 'gw-test' })
      .models({ signal: ctrl.signal })
      .catch((e: unknown) => e)
    expect(err).toBeInstanceOf(DOMException)
  })

  it('forces non-streaming chat requests', async () => {
    let sent: unknown
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init: RequestInit) => {
        sent = JSON.parse((init.body ?? '{}') as string) as unknown
        expect(url).toBe('/v1/chat/completions')
        return Promise.resolve(
          new Response(JSON.stringify({ choices: [], model: 'm' }), { status: 200 }),
        )
      }),
    )
    await new GatewayClient({ base: '', token: 'gw-test' }).chat({
      model: 'm',
      messages: [{ role: 'user', content: 'hi' }],
      stream: true,
    })
    expect((sent as Record<string, unknown>).stream).toBe(false)
  })

  it('sends the admin key header form without a bearer token', async () => {
    let headers: Record<string, string> = {}
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: string, init: RequestInit) => {
        headers = (init.headers ?? {}) as Record<string, string>
        return Promise.resolve(new Response(JSON.stringify({ circuits: [] }), { status: 200 }))
      }),
    )
    await new GatewayClient({ base: '', token: 'unused', adminKey: 'master-test' }).circuitState()
    expect(headers['X-Admin-Key']).toBe('master-test')
    expect(headers.Authorization).toBeUndefined()
  })

  it('resolves empty payloads as undefined', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response(null, { status: 204 }))),
    )
    const out = await new GatewayClient({ base: '', token: 'gw-test' }).models()
    expect(out).toBeUndefined()
  })

  it('treats non-abort DOMExceptions as network failures', async () => {
    const cause = new DOMException('reset', 'NetworkError')
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(cause)),
    )
    const err = await new GatewayClient({ base: '', token: 'gw-test' })
      .models()
      .catch((e: unknown) => e)
    expect(err).toBeInstanceOf(Error)
    expect((err as Error).message).toContain('Network unreachable')
  })

  it('falls back to status messages for scalar JSON bodies', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response(JSON.stringify('oops'), { status: 400 }))),
    )
    const err = await new GatewayClient({ base: '', token: 'gw-test' })
      .models()
      .catch((e: unknown) => e)
    expect((err as ApiError).message).toContain('Invalid request')
  })

  it('falls back to status messages for shapeless error objects', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(new Response(JSON.stringify({ error: { type: 'x' } }), { status: 400 })),
      ),
    )
    const err = await new GatewayClient({ base: '', token: 'gw-test' })
      .models()
      .catch((e: unknown) => e)
    expect((err as ApiError).message).toContain('Invalid request')
  })

  it('maps gateway-shaped 503 bodies to retry guidance', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify({ error: { message: 'down', type: 't', code: null } }), {
            status: 503,
          }),
        ),
      ),
    )
    const err = await new GatewayClient({ base: '', token: 'gw-test' })
      .models()
      .catch((e: unknown) => e)
    expect((err as ApiError).message).toContain('temporarily unavailable')
  })

  it('scopes cache purges to a model when given', async () => {
    let url = ''
    vi.stubGlobal(
      'fetch',
      vi.fn((input: string) => {
        url = input
        return Promise.resolve(new Response(JSON.stringify({ purged: true }), { status: 200 }))
      }),
    )
    await new GatewayClient({ base: '', token: 'unused', adminKey: 'master-test' }).purgeCache(
      'gpt-4o-mini',
    )
    expect(url).toContain('model=gpt-4o-mini')
  })
})
