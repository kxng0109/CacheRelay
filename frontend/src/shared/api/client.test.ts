import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, GatewayClient, parseRateLimit, safeErrorMessage } from './client.js'

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
})
