import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  GatewayClient,
  isStreamingEnabled,
  parseGatewayErrorCode,
  parseRateLimit,
  resolveApiBase,
  safeErrorMessage,
  selectPrimaryDimension,
  setHeadersReporter,
  toErrorMessage,
} from './client.js'

afterEach(() => {
  vi.unstubAllGlobals()
  setHeadersReporter(null)
})

describe('parseRateLimit', () => {
  it('parses the RPM trio the gateway sends', () => {
    const h = new Headers({
      'X-RateLimit-Limit-RPM': '60',
      'X-RateLimit-Remaining-RPM': '59',
      'X-RateLimit-Reset-RPM': '12',
      'Retry-After': '5',
    })
    expect(parseRateLimit(h)).toEqual({
      dimension: 'RPM',
      limit: 60,
      remaining: 59,
      reset: 12,
      retryAfter: 5,
    })
  })

  it('falls back to the TPM trio when RPM headers are absent', () => {
    const h = new Headers({
      'X-RateLimit-Limit-TPM': '100000',
      'X-RateLimit-Remaining-TPM': '99950',
      'X-RateLimit-Reset-TPM': '30',
    })
    expect(parseRateLimit(h)).toEqual({
      dimension: 'TPM',
      limit: 100000,
      remaining: 99950,
      reset: 30,
      retryAfter: null,
    })
  })

  it('leads with the most-constrained dimension, not fixed RPM', () => {
    const h = new Headers({
      'X-RateLimit-Limit-RPM': '60',
      'X-RateLimit-Remaining-RPM': '59',
      'X-RateLimit-Reset-RPM': '12',
      'X-RateLimit-Limit-TPM': '100000',
      'X-RateLimit-Remaining-TPM': '1000',
      'X-RateLimit-Reset-TPM': '30',
    })
    expect(parseRateLimit(h).dimension).toBe('TPM')
    expect(parseRateLimit(h).remaining).toBe(1000)
  })

  it('breaks most-constrained ties toward RPM', () => {
    const h = new Headers({
      'X-RateLimit-Limit-RPM': '60',
      'X-RateLimit-Remaining-RPM': '30',
      'X-RateLimit-Limit-TPM': '100000',
      'X-RateLimit-Remaining-TPM': '50000',
    })
    expect(parseRateLimit(h).dimension).toBe('RPM')
  })

  it('lets the backend-named 429 code override the header math', () => {
    const h = new Headers({
      'X-RateLimit-Limit-RPM': '60',
      'X-RateLimit-Remaining-RPM': '0',
      'X-RateLimit-Limit-TPM': '100000',
      'X-RateLimit-Remaining-TPM': '0',
      'Retry-After': '9',
    })
    expect(parseRateLimit(h, 'TPM_EXCEEDED').dimension).toBe('TPM')
    expect(parseRateLimit(h, 'RPM_EXCEEDED').dimension).toBe('RPM')
  })

  it('ignores unknown 429 codes instead of rendering them', () => {
    const h = new Headers({
      'X-RateLimit-Limit-RPM': '60',
      'X-RateLimit-Remaining-RPM': '59',
    })
    expect(parseRateLimit(h, 'BOGUS_CODE').dimension).toBe('RPM')
  })

  it('maps the unlimited sentinel to null (renders as em-dash)', () => {
    const h = new Headers({
      'X-RateLimit-Limit-RPM': 'unlimited',
      'X-RateLimit-Remaining-RPM': 'unlimited',
      'X-RateLimit-Reset-RPM': 'unlimited',
    })
    expect(parseRateLimit(h)).toEqual({
      dimension: null,
      limit: null,
      remaining: null,
      reset: null,
      retryAfter: null,
    })
  })

  it('returns nulls when headers are absent', () => {
    expect(parseRateLimit(new Headers())).toEqual({
      dimension: null,
      limit: null,
      remaining: null,
      reset: null,
      retryAfter: null,
    })
  })

  it('rejects non-numeric header injection', () => {
    const h = new Headers({ 'X-RateLimit-Remaining-RPM': '1; DROP' })
    expect(parseRateLimit(h).remaining).toBeNull()
  })

  it('rejects a non-numeric retry-after without failing', () => {
    const h = new Headers({
      'X-RateLimit-Limit-RPM': '60',
      'X-RateLimit-Remaining-RPM': '0',
      'Retry-After': 'soon',
    })
    expect(parseRateLimit(h).retryAfter).toBeNull()
  })
})

describe('selectPrimaryDimension', () => {
  const triple = (limit: number | null, remaining: number | null) => ({ limit, remaining })

  it('prefers the named 429 dimension over fractions', () => {
    expect(selectPrimaryDimension(triple(60, 59), triple(100, 99), 'TPM_EXCEEDED')).toBe('TPM')
    expect(selectPrimaryDimension(triple(60, 1), triple(100, 99), 'RPM_EXCEEDED')).toBe('RPM')
  })

  it('returns null when neither dimension is capped and observed', () => {
    expect(selectPrimaryDimension(triple(null, null), triple(null, null), null)).toBeNull()
    expect(selectPrimaryDimension(triple(null, 5), triple(0, 0), null)).toBeNull()
  })

  it('picks the lone capped dimension', () => {
    expect(selectPrimaryDimension(triple(60, 3), triple(null, null), null)).toBe('RPM')
    expect(selectPrimaryDimension(triple(null, null), triple(100, 3), null)).toBe('TPM')
  })
})

describe('parseGatewayErrorCode', () => {
  it('extracts the deny code from the gateway envelope', () => {
    expect(
      parseGatewayErrorCode(JSON.stringify({ error: { message: 'slow', code: 'RPM_EXCEEDED' } })),
    ).toBe('RPM_EXCEEDED')
  })

  it('returns null for empty, non-JSON, and codeless bodies', () => {
    expect(parseGatewayErrorCode('')).toBeNull()
    expect(parseGatewayErrorCode('{{{not json')).toBeNull()
    expect(parseGatewayErrorCode(JSON.stringify({ error: { message: 'x' } }))).toBeNull()
    expect(parseGatewayErrorCode(JSON.stringify({ ok: true }))).toBeNull()
  })

  it('rejects oversized and non-string codes', () => {
    expect(
      parseGatewayErrorCode(JSON.stringify({ error: { message: 'x', code: 'A'.repeat(65) } })),
    ).toBeNull()
    expect(parseGatewayErrorCode(JSON.stringify({ error: { message: 'x', code: 429 } }))).toBeNull()
    expect(
      parseGatewayErrorCode(JSON.stringify({ error: { message: 'x', code: '<script>' } })),
    ).toBe('<script>')
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

  it('maps plain-text 403 bodies without parsing', () => {
    expect(safeErrorMessage(403, 'Forbidden')).toContain('gateway policy')
  })

  it('maps unlisted statuses to their HTTP code', () => {
    expect(safeErrorMessage(418, '')).toContain('HTTP 418')
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
            headers: { 'X-RateLimit-Remaining-RPM': '0', 'Retry-After': '7' },
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
    expect(apiErr.code).toBeNull()
    expect(apiErr.message).toContain('Rate limit')
  })

  it('carries the backend-named binding dimension on 429', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(
            JSON.stringify({
              error: { message: 'Token rate limit exceeded.', code: 'TPM_EXCEEDED' },
            }),
            {
              status: 429,
              headers: {
                'X-RateLimit-Remaining-RPM': '12',
                'X-RateLimit-Limit-RPM': '60',
                'X-RateLimit-Remaining-TPM': '0',
                'X-RateLimit-Limit-TPM': '500000',
                'Retry-After': '9',
              },
            },
          ),
        ),
      ),
    )
    const err = await new GatewayClient({ base: '', token: 'gw-test' })
      .models()
      .catch((e: unknown) => e)
    const apiErr = err as ApiError
    expect(apiErr.code).toBe('TPM_EXCEEDED')
    expect(apiErr.rateLimit.dimension).toBe('TPM')
    expect(apiErr.rateLimit.remaining).toBe(0)
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

  it('recovers when the error body itself fails to read', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(
            new ReadableStream<Uint8Array>({
              start(ctrl) {
                ctrl.error(new Error('truncated'))
              },
            }),
            { status: 503 },
          ),
        ),
      ),
    )
    const err = await new GatewayClient({ base: '', token: 'gw-test' })
      .models()
      .catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).message).toContain('temporarily unavailable')
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

  it('notifies the module reporter with success-path headers', async () => {
    const seen: string[] = []
    setHeadersReporter((h, code) => {
      expect(code).toBeNull()
      const v = h.get('X-RateLimit-Remaining-RPM')
      if (v !== null) seen.push(v)
    })
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify({ data: [] }), {
            status: 200,
            headers: { 'X-RateLimit-Remaining-RPM': '41' },
          }),
        ),
      ),
    )
    await new GatewayClient({ base: '', token: 'gw-test' }).models()
    expect(seen).toEqual(['41'])
  })

  it('notifies the module reporter before throwing on 429', async () => {
    let remaining: string | null = null
    let seenCode: string | null | undefined
    setHeadersReporter((h, code) => {
      remaining = h.get('X-RateLimit-Remaining-RPM')
      seenCode = code
    })
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify({ error: { message: 'slow', type: 't', code: null } }), {
            status: 429,
            headers: { 'X-RateLimit-Remaining-RPM': '0', 'Retry-After': '9' },
          }),
        ),
      ),
    )
    const err = await new GatewayClient({ base: '', token: 'gw-test' })
      .models()
      .catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(remaining).toBe('0')
    expect(seenCode).toBeNull()
  })

  it('prefers per-call onHeaders over the module reporter', async () => {
    const calls: string[] = []
    setHeadersReporter(() => {
      calls.push('module')
    })
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify({ data: [] }), {
            status: 200,
            headers: { 'X-RateLimit-Remaining-RPM': '10' },
          }),
        ),
      ),
    )
    await new GatewayClient({ base: '', token: 'gw-test' }).models({
      onHeaders: (h) => {
        calls.push(h.get('X-RateLimit-Remaining-RPM') ?? 'missing')
      },
    })
    expect(calls).toEqual(['10'])
  })
})
