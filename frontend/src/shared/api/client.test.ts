import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  GatewayClient,
  isStreamingEnabled,
  keyFingerprint,
  parseGatewayErrorCode,
  parseRateLimit,
  resolveApiBase,
  resolveManagementBase,
  safeErrorMessage,
  selectPrimaryDimension,
  setDriftReporter,
  setHeadersReporter,
  toErrorMessage,
} from './client.js'
import { useAuthStore } from '../auth/store.js'

afterEach(() => {
  vi.unstubAllGlobals()
  setHeadersReporter(null)
  setDriftReporter(null)
})

/**
 * Stubs fetch with one JSON body for transport-boundary tests.
 *
 * @param body - Decoded body the gateway supposedly sent.
 */
function stubJson(body: unknown): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(() =>
      Promise.resolve(
        new Response(JSON.stringify(body), { headers: { 'content-type': 'application/json' } }),
      ),
    ),
  )
}

describe('wire drift', () => {
  it('degrades drifted collections to empty with a notice, never throws', async () => {
    const drifted: string[] = []
    setDriftReporter((endpoint) => {
      drifted.push(endpoint)
    })
    stubJson({ keys: {} })
    await expect(new GatewayClient().listKeys()).resolves.toEqual({ keys: [] })
    stubJson({ nope: 1 })
    await expect(new GatewayClient().circuitState()).resolves.toEqual({ circuits: [] })
    stubJson('nope')
    await expect(new GatewayClient().listBudgets()).resolves.toEqual({ budgets: [] })
    stubJson({ approvals: {} })
    await expect(new GatewayClient().hitlPending()).resolves.toEqual({ approvals: [] })
    stubJson({ data: {} })
    await expect(new GatewayClient().models()).resolves.toEqual({ data: [] })
    stubJson({ x: 1 })
    await expect(new GatewayClient().myTeams()).resolves.toEqual([])
    stubJson([1, 2])
    await expect(new GatewayClient().orgTeams('acme')).resolves.toEqual([])
    expect(drifted).toEqual([
      'keys',
      'circuits',
      'budgets',
      'hitl-pending',
      'models',
      'my-teams',
      'org-teams',
      'org-teams',
    ])
  })

  it('accepts the live bare-array approvals shape', async () => {
    stubJson([{ approvalId: 'a1', toolName: 't', requestedAt: 'r', requestedBy: 'b' }])
    await expect(new GatewayClient().hitlPending()).resolves.toEqual({
      approvals: [{ approvalId: 'a1', toolName: 't', requestedAt: 'r', requestedBy: 'b' }],
    })
  })

  it('drops malformed rows but keeps valid ones', async () => {
    const drifted: string[] = []
    setDriftReporter((endpoint) => {
      drifted.push(endpoint)
    })
    stubJson([
      {
        keyId: 'k1',
        keyPrefix: 'gw-aaa',
        ownerId: 'o1',
        name: 'good',
        rpmLimit: 60,
        tpmLimit: 1000,
        allowedModels: [],
        allowedProviders: [],
        enabled: true,
        createdAt: '2026-01-01',
        ownerUserId: null,
        ownerUsername: null,
      },
      { name: 42 },
    ])
    const keys = await new GatewayClient().listKeys()
    expect(keys.keys.map((k) => k.name)).toEqual(['good'])
    expect(drifted).toEqual(['keys'])
  })

  it('degrades drifted summaries to zeros with a notice', async () => {
    const drifted: string[] = []
    setDriftReporter((endpoint) => {
      drifted.push(endpoint)
    })
    stubJson({ totalRequests: 'lots' })
    const summary = await new GatewayClient().ledgerSummary()
    expect(summary.totalRequests).toBe(0)
    expect(summary.byOwner).toEqual([])
    stubJson({ totalRequests: 'lots' })
    const mine = await new GatewayClient().myUsage()
    expect(mine.summary.totalRequests).toBe(0)
    stubJson({ totalRequests: 'lots' })
    const theirs = await new GatewayClient().userUsage('u1')
    expect(theirs.summary.totalRequests).toBe(0)
    expect(drifted).toEqual(['ledger-summary', 'my-usage', 'user-usage'])
  })

  it('degrades drifted ledger pages to an empty page', async () => {
    const drifted: string[] = []
    setDriftReporter((endpoint) => {
      drifted.push(endpoint)
    })
    stubJson({ content: null, page: 0, size: 10, totalElements: 0, totalPages: 0, hasNext: false })
    const page = await new GatewayClient().ledgerLogs(0, 10)
    expect(page.content).toEqual([])
    stubJson({
      content: [{ requestId: 'r1', model: 'm', costUsdMicros: 5, createdAt: 't' }, { nope: true }],
      page: 0,
      size: 10,
      totalElements: 2,
      totalPages: 1,
      hasNext: false,
    })
    const mixed = await new GatewayClient().ledgerLogs(0, 10)
    expect(mixed.content.map((e) => e.requestId)).toEqual(['r1'])
    expect(drifted).toEqual(['ledger-entries', 'ledger-entries'])
  })

  it('reads a drifted receipt as gone, never fabricated', async () => {
    const drifted: string[] = []
    setDriftReporter((endpoint) => {
      drifted.push(endpoint)
    })
    stubJson({ requestId: 'r1' })
    await expect(new GatewayClient().ledgerReceipt('r1')).resolves.toBeNull()
    expect(drifted).toEqual(['ledger-receipt'])
  })

  it('rejects a drifted identity with a safe error', async () => {
    stubJson({ username: 'op' })
    await expect(new GatewayClient().authMe()).rejects.toThrow(/changed shape/i)
  })

  it('rejects drifted mutation bodies with safe errors, never raw crashes', async () => {
    const drifted: string[] = []
    setDriftReporter((endpoint) => {
      drifted.push(endpoint)
    })
    const keyBody = {
      ownerId: 'o',
      ownerUserId: 'u',
      name: 'n',
      rpmLimit: 1,
      tpmLimit: 1,
      allowedModels: [],
    }
    stubJson({ nope: true })
    await expect(new GatewayClient().createKey(keyBody)).rejects.toThrow(/changed shape/i)
    stubJson({ nope: true })
    await expect(new GatewayClient().setKeyEnabled('k1', false)).rejects.toThrow(/changed shape/i)
    stubJson({ nope: true })
    await expect(new GatewayClient().revokeKey('k1')).rejects.toThrow(/changed shape/i)
    stubJson({ nope: true })
    await expect(
      new GatewayClient().createModelAlias({ name: 'a', chain: [], strategy: 'S' }),
    ).rejects.toThrow(/changed shape/i)
    stubJson({ nope: true })
    await expect(
      new GatewayClient().updateModelAlias('a', { chain: [], strategy: 'S' }),
    ).rejects.toThrow(/changed shape/i)
    stubJson({ nope: true })
    await expect(new GatewayClient().resetCircuit('openai')).rejects.toThrow(/changed shape/i)
    stubJson({ nope: true })
    await expect(new GatewayClient().cacheStats()).rejects.toThrow(/changed shape/i)
    stubJson({ nope: true })
    await expect(new GatewayClient().purgeCache()).rejects.toThrow(/changed shape/i)
    stubJson({ nope: true })
    await expect(
      new GatewayClient().createBudget({
        level: 'L',
        subjectId: 's',
        minuteMicros: 0,
        monthMicros: 0,
      }),
    ).rejects.toThrow(/changed shape/i)
    stubJson({ nope: true })
    await expect(new GatewayClient().chat({ model: 'm', messages: [] })).rejects.toThrow(
      /changed shape/i,
    )
    stubJson({ nope: true })
    await expect(new GatewayClient().embeddings({ model: 'm', input: 'hi' })).rejects.toThrow(
      /changed shape/i,
    )
    expect(drifted).toEqual([
      'keys-create',
      'keys-update',
      'keys-revoke',
      'models-create',
      'models-update',
      'circuits-reset',
      'cache-stats',
      'cache-purge',
      'budgets-create',
      'chat-completion',
      'embeddings',
    ])
  })
})

describe('keyFingerprint', () => {
  it('is stable per credential and distinct across credentials', () => {
    expect(keyFingerprint('gw-alpha')).toBe(keyFingerprint('gw-alpha'))
    expect(keyFingerprint('gw-alpha')).not.toBe(keyFingerprint('gw-beta'))
  })

  it('carries no key material', () => {
    const print = keyFingerprint('gw-super-secret-value')
    expect(print).not.toContain('gw-super-secret-value')
    expect(print).not.toContain('secret')
  })

  it('omits the authorization header when no credential resolves', async () => {
    let auth: string | null | undefined = undefined
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: unknown, init?: RequestInit) => {
        auth = new Headers(init?.headers).get('Authorization')
        return Promise.resolve(
          new Response(JSON.stringify({ data: [] }), {
            headers: { 'content-type': 'application/json' },
          }),
        )
      }),
    )
    useAuthStore.getState().clear()
    await new GatewayClient().models()
    expect(auth).toBeNull()
  })
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

  it('surfaces Spring Boot top-level messages (admin 400s name the reason)', () => {
    const body = JSON.stringify({
      timestamp: '2026-09-22T00:00:00Z',
      status: 400,
      error: 'Bad Request',
      message: 'unknown owner account',
      path: '/v1/admin/keys',
    })
    expect(safeErrorMessage(400, body)).toBe('unknown owner account')
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

describe('resolveManagementBase', () => {
  it('defaults loopback pages to the management port when unconfigured', () => {
    vi.stubEnv('VITE_MANAGEMENT_BASE_URL', '')
    expect(resolveManagementBase()).toBe('http://localhost:9091')
  })

  it('defaults loopback pages to the management port when absent', () => {
    vi.stubEnv('VITE_MANAGEMENT_BASE_URL', undefined)
    expect(resolveManagementBase()).toBe('http://localhost:9091')
  })

  it('trims whitespace and trailing slashes', () => {
    vi.stubEnv('VITE_MANAGEMENT_BASE_URL', '  http://localhost:9091///  ')
    expect(resolveManagementBase()).toBe('http://localhost:9091')
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

  it('reads the live OpenAI list shape with extra fields', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(
            JSON.stringify({
              object: 'list',
              data: [
                { id: 'gpt-56-luna', object: 'model', created: 1789775002, owned_by: 'openai' },
              ],
            }),
            { status: 200 },
          ),
        ),
      ),
    )
    const out = await new GatewayClient({ base: '', token: 'gw-test' }).models()
    expect(out.data.map((m) => m.id)).toEqual(['gpt-56-luna'])
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

  it('prefers the session bearer over the constructor token', async () => {
    let headers: Record<string, string> = {}
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: string, init: RequestInit) => {
        headers = (init.headers ?? {}) as Record<string, string>
        return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
      }),
    )
    useAuthStore
      .getState()
      .setSession({ accessToken: 'session-jwt', admin: true, username: 'test-admin' })
    try {
      await new GatewayClient({ base: '', token: 'gw-test' }).circuitState()
      expect(headers.Authorization).toBe('Bearer session-jwt')
      expect(headers['X-Admin-Key']).toBeUndefined()
    } finally {
      useAuthStore.getState().clear()
    }
  })

  it('falls back to the constructor token without a session', async () => {
    let headers: Record<string, string> = {}
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: string, init: RequestInit) => {
        headers = (init.headers ?? {}) as Record<string, string>
        return Promise.resolve(new Response(JSON.stringify([]), { status: 200 }))
      }),
    )
    await new GatewayClient({ base: '', token: 'gw-test' }).circuitState()
    expect(headers.Authorization).toBe('Bearer gw-test')
  })

  it('resolves empty payloads as empty catalogs, never undefined', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response(null, { status: 204 }))),
    )
    const out = await new GatewayClient({ base: '', token: 'gw-test' }).models()
    expect(out).toEqual({ data: [] })
  })

  it('resolves empty key payloads as an empty list', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response(null, { status: 204 }))),
    )
    useAuthStore.getState().clear()
    await expect(new GatewayClient({ base: '', token: 'gw-test' }).listKeys()).resolves.toEqual({
      keys: [],
    })
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

  it('scopes cache purges to an owner when given', async () => {
    let url = ''
    let method = ''
    vi.stubGlobal(
      'fetch',
      vi.fn((input: string, init?: RequestInit) => {
        url = input
        method = init?.method ?? ''
        return Promise.resolve(
          new Response(JSON.stringify({ success: true, evictedScope: 'tenant-corp' }), {
            status: 200,
          }),
        )
      }),
    )
    const out = await new GatewayClient({
      base: '',
      token: 'unused',
    }).purgeCache('tenant-corp')
    expect(url).toContain('ownerId=tenant-corp')
    expect(method).toBe('DELETE')
    expect(out.evictedScope).toBe('tenant-corp')
  })

  it('attempts a refresh on admin-path 401s with a session', async () => {
    useAuthStore.getState().setSession({ accessToken: 'stale-jwt', admin: true, username: 'op' })
    let refreshCalls = 0
    const seen: Record<string, string>[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init?: RequestInit) => {
        if (typeof url === 'string' && url.endsWith('/v1/auth/refresh')) {
          refreshCalls += 1
          return Promise.resolve(
            new Response(
              JSON.stringify({ accessToken: 'fresh-jwt', expiresInSeconds: 300, admin: true }),
              { status: 200 },
            ),
          )
        }
        seen.push((init?.headers ?? {}) as Record<string, string>)
        return Promise.resolve(new Response('x', { status: 401 }))
      }),
    )
    try {
      const err = await new GatewayClient({ base: '', token: 'gw-test' })
        .circuitState()
        .catch((e: unknown) => e)
      expect(err).toBeInstanceOf(ApiError)
      expect(refreshCalls).toBe(1)
      expect(useAuthStore.getState().session?.accessToken).toBe('fresh-jwt')
      expect(seen).toHaveLength(1)
    } finally {
      useAuthStore.getState().clear()
    }
  })

  it('skips refresh on public-path 401s', async () => {
    useAuthStore.getState().setSession({ accessToken: 'stale-jwt', admin: true, username: 'op' })
    let refreshCalls = 0
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (typeof url === 'string' && url.endsWith('/v1/auth/refresh')) {
          refreshCalls += 1
          return Promise.resolve(
            new Response(
              JSON.stringify({ accessToken: 'fresh-jwt', expiresInSeconds: 300, admin: true }),
              { status: 200 },
            ),
          )
        }
        return Promise.resolve(new Response('x', { status: 401 }))
      }),
    )
    try {
      await new GatewayClient({ base: '', token: 'gw-test' }).models().catch((e: unknown) => e)
      expect(refreshCalls).toBe(0)
      expect(useAuthStore.getState().session?.accessToken).toBe('stale-jwt')
    } finally {
      useAuthStore.getState().clear()
    }
  })

  it('skips refresh without a session', async () => {
    let refreshCalls = 0
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (typeof url === 'string' && url.endsWith('/v1/auth/refresh')) {
          refreshCalls += 1
        }
        return Promise.resolve(new Response('x', { status: 401 }))
      }),
    )
    await new GatewayClient({ base: '', token: 'gw-test' }).circuitState().catch((e: unknown) => e)
    expect(refreshCalls).toBe(0)
  })

  it('refreshes through the auth path outside the login exchange', async () => {
    const { refreshSession } = await import('../auth/session.js')
    useAuthStore.getState().setSession({ accessToken: 'stale-jwt', admin: true, username: 'op' })
    let refreshCalls = 0
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string) => {
        if (typeof url === 'string' && url.endsWith('/v1/auth/refresh')) {
          refreshCalls += 1
          return Promise.resolve(
            new Response(
              JSON.stringify({ accessToken: 'fresh-jwt', expiresInSeconds: 300, admin: true }),
              { status: 200 },
            ),
          )
        }
        return Promise.resolve(new Response('x', { status: 401 }))
      }),
    )
    try {
      const fresh = await refreshSession()
      expect(refreshCalls).toBe(1)
      expect(fresh?.accessToken).toBe('fresh-jwt')
      expect(useAuthStore.getState().session?.accessToken).toBe('fresh-jwt')
    } finally {
      useAuthStore.getState().clear()
    }
  })

  it('degrades to an empty board when the models envelope drifts', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response(JSON.stringify({ models: null }), { status: 200 }))),
    )
    const out = await new GatewayClient({ base: '', token: 'gw-test' }).listModelAliases()
    expect(out.models).toEqual([])
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

  it('signals suspension on 403 without inventing tools', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response('x', { status: 403 }))),
    )
    const out = await new GatewayClient({ base: '', token: 'gw-test' }).mcpTools()
    expect(out).toEqual({ suspended: true, status: 403 })
  })

  it('parses tools defensively, skipping malformed entries', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(
            JSON.stringify({
              jsonrpc: '2.0',
              id: 'tools-list',
              result: {
                tools: [
                  {
                    name: 'a__b',
                    description: 'does b',
                    inputSchema: { type: 'object' },
                    annotations: { readOnlyHint: true, destructiveHint: 'yes' },
                  },
                  'junk',
                  { description: 'nameless' },
                ],
              },
            }),
            { status: 200 },
          ),
        ),
      ),
    )
    const out = await new GatewayClient({ base: '', token: 'gw-test' }).mcpTools()
    expect(out).toEqual({
      tools: [
        {
          name: 'a__b',
          description: 'does b',
          inputSchema: { type: 'object' },
          annotations: { readOnlyHint: true, destructiveHint: 'yes' },
        },
      ],
    })
  })

  it('throws on JSON-RPC error envelopes instead of emptying', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(
            JSON.stringify({ jsonrpc: '2.0', id: 'x', error: { code: -32603, message: 'boom' } }),
            { status: 200 },
          ),
        ),
      ),
    )
    const err = await new GatewayClient({ base: '', token: 'gw-test' })
      .mcpTools()
      .catch((e: unknown) => e)
    expect(err).toBeInstanceOf(Error)
    expect((err as Error).message).toContain('boom')
  })

  it('returns no tools for non-object bodies', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response('null', { status: 200 }))),
    )
    const out = await new GatewayClient({ base: '', token: 'gw-test' }).mcpTools()
    expect(out).toEqual({ tools: [] })
  })

  it('rethrows aborts from the catalog fetch', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new DOMException('stop', 'AbortError'))),
    )
    const err = await new GatewayClient({ base: '', token: 'gw-test' })
      .mcpTools()
      .catch((e: unknown) => e)
    expect(err).toBeInstanceOf(DOMException)
  })

  it('maps catalog network failures honestly', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new TypeError('down'))),
    )
    const err = await new GatewayClient({ base: '', token: 'gw-test' })
      .mcpTools()
      .catch((e: unknown) => e)
    expect((err as Error).message).toContain('Network unreachable')
  })

  it('returns no tools when the envelope has no result', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(new Response(JSON.stringify({ jsonrpc: '2.0', id: 'x' }), { status: 200 })),
      ),
    )
    const out = await new GatewayClient({ base: '', token: 'gw-test' }).mcpTools()
    expect(out).toEqual({ tools: [] })
  })

  it('reads error bodies that fail mid-stream as empty', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => {
        const stream = new ReadableStream<Uint8Array>({
          start(ctrl) {
            ctrl.error(new Error('truncated'))
          },
        })
        return Promise.resolve(new Response(stream, { status: 500 }))
      }),
    )
    const err = await new GatewayClient({ base: '', token: 'gw-test' })
      .mcpTools()
      .catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
  })

  it('returns no tools for unparseable success bodies', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response('not-json{{{', { status: 200 }))),
    )
    const out = await new GatewayClient({ base: '', token: 'gw-test' }).mcpTools()
    expect(out).toEqual({ tools: [] })
  })

  it('returns no tools when tools is not an array', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify({ jsonrpc: '2.0', id: 'x', result: { tools: {} } }), {
            status: 200,
          }),
        ),
      ),
    )
    const out = await new GatewayClient({ base: '', token: 'gw-test' }).mcpTools()
    expect(out).toEqual({ tools: [] })
  })

  it('throws a generic message for codeless error envelopes', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify({ jsonrpc: '2.0', id: 'x', error: { code: -32603 } }), {
            status: 200,
          }),
        ),
      ),
    )
    const err = await new GatewayClient({ base: '', token: 'gw-test' })
      .mcpTools()
      .catch((e: unknown) => e)
    expect((err as Error).message).toBe('MCP catalog error.')
  })
})
