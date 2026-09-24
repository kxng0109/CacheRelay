import { HttpResponse, http } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import { server } from '../../test/setup.js'
import {
  ApiError,
  GatewayClient,
  dashboardRetry,
  dashboardRetryDelay,
  resolveSsoProviders,
  ssoAuthorizationUrl,
} from './client.js'

/**
 * Dashboard/teams contract witness: every Phase 1–2 read the UI
 * needs, pinned against MSW so a backend drift fails loudly here first.
 * Wire names follow the backend source (`byOwner`, teams `org` query
 * param) — never the Java field names.
 */

function summaryBody(overrides: Record<string, unknown> = {}) {
  return {
    totalRequests: 7,
    totalPromptTokens: 700,
    totalCompletionTokens: 300,
    totalTokens: 1000,
    totalCostUsdMicros: 1500,
    totalCostUsd: '0.001500',
    averageDurationMs: 42.5,
    byOwner: [],
    byModel: [],
    byProvider: [],
    ...overrides,
  }
}

describe('myUsage', () => {
  it('sends from/to and returns the summary with freshness headers', async () => {
    let seenUrl = ''
    server.use(
      http.get('*/v1/me/usage', ({ request }) => {
        seenUrl = request.url
        return new HttpResponse(JSON.stringify(summaryBody()), {
          headers: {
            'Content-Type': 'application/json',
            'X-Dashboard-Generated-At': '2026-09-24T10:00:00Z',
            'X-Dashboard-Watermark': '2026-09-24T09:59:00Z',
          },
        })
      }),
    )
    const view = await new GatewayClient().myUsage('2026-09-17T00:00:00Z', '2026-09-24T00:00:00Z')
    expect(seenUrl).toContain('from=2026-09-17')
    expect(seenUrl).toContain('to=2026-09-24')
    expect(view.summary.totalRequests).toBe(7)
    expect(view.generatedAt).toBe('2026-09-24T10:00:00Z')
    expect(view.watermark).toBe('2026-09-24T09:59:00Z')
  })

  it('omits absent window params and nulls missing headers', async () => {
    let seenUrl = ''
    server.use(
      http.get('*/v1/me/usage', ({ request }) => {
        seenUrl = request.url
        return HttpResponse.json(summaryBody())
      }),
    )
    const view = await new GatewayClient().myUsage()
    expect(seenUrl.endsWith('/v1/me/usage')).toBe(true)
    expect(view.generatedAt).toBeNull()
    expect(view.watermark).toBeNull()
  })

  it('sends single-sided windows without the absent param', async () => {
    const seen: string[] = []
    server.use(
      http.get('*/v1/me/usage', ({ request }) => {
        seen.push(request.url)
        return HttpResponse.json(summaryBody())
      }),
    )
    await new GatewayClient().myUsage('2026-09-17T00:00:00Z')
    await new GatewayClient().myUsage(undefined, '2026-09-24T00:00:00Z')
    expect(seen[0]).toContain('from=2026-09-17')
    expect(seen[0]).not.toContain('to=')
    expect(seen[1]).toContain('to=2026-09-24')
    expect(seen[1]).not.toContain('from=')
  })

  it('surfaces 401 without a retry loop', async () => {
    server.use(http.get('*/v1/me/usage', () => new HttpResponse('x', { status: 401 })))
    await expect(new GatewayClient().myUsage()).rejects.toMatchObject({ status: 401 })
  })
})

describe('userUsage', () => {
  it('drill-downs into one account with the same shape', async () => {
    server.use(
      http.get('*/v1/admin/ledger/user/:id/summary', () =>
        HttpResponse.json(summaryBody({ totalRequests: 3 })),
      ),
    )
    const view = await new GatewayClient().userUsage('123e4567-e89b-12d3-a456-426614174000')
    expect(view.summary.totalRequests).toBe(3)
  })

  it('maps stealth 404 to ApiError for the admin-unavailable screen', async () => {
    server.use(
      http.get('*/v1/admin/ledger/user/:id/summary', () => new HttpResponse('x', { status: 404 })),
    )
    const error = await new GatewayClient()
      .userUsage('123e4567-e89b-12d3-a456-426614174000')
      .catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).status).toBe(404)
  })
})

describe('teams', () => {
  it('lists my active memberships', async () => {
    server.use(
      http.get('*/v1/me/teams', () =>
        HttpResponse.json([
          {
            teamId: 't1',
            teamName: 'Eng',
            orgSlug: 'acme',
            role: 'MEMBER',
            status: 'ACTIVE',
          },
        ]),
      ),
    )
    const teams = await new GatewayClient().myTeams()
    expect(teams).toHaveLength(1)
    expect(teams[0]?.teamName).toBe('Eng')
  })

  it('lists org teams with the required org param', async () => {
    let seenUrl = ''
    server.use(
      http.get('*/v1/admin/teams', ({ request }) => {
        seenUrl = request.url
        return HttpResponse.json([
          {
            teamId: 't1',
            orgSlug: 'acme',
            name: 'Eng',
            idpGroupId: 'group-1',
            activeMembers: 12,
          },
        ])
      }),
    )
    const teams = await new GatewayClient().orgTeams('acme')
    expect(seenUrl).toContain('org=acme')
    expect(teams[0]?.activeMembers).toBe(12)
  })

  it('surfaces unknown orgs as 404', async () => {
    server.use(http.get('*/v1/admin/teams', () => new HttpResponse('x', { status: 404 })))
    await expect(new GatewayClient().orgTeams('nope')).rejects.toMatchObject({ status: 404 })
  })
})

describe('authMe', () => {
  it('returns the session identity for SSO fragment completion', async () => {
    server.use(
      http.get('*/v1/auth/me', () =>
        HttpResponse.json({ userId: 'u1', username: 'op', admin: false }),
      ),
    )
    const me = await new GatewayClient({ token: 'frag-jwt' }).authMe()
    expect(me.username).toBe('op')
  })
})

describe('dashboardRetry', () => {
  it('backs off on 429 only, at most twice', () => {
    const limited = new ApiError({
      message: 'limited',
      status: 429,
      requestId: null,
      rateLimit: { dimension: null, limit: null, remaining: null, reset: null, retryAfter: null },
      cacheStatus: null,
      debugId: null,
      code: null,
    })
    expect(dashboardRetry(0, limited)).toBe(true)
    expect(dashboardRetry(1, limited)).toBe(true)
    expect(dashboardRetry(2, limited)).toBe(false)
    expect(dashboardRetry(0, new Error('nope'))).toBe(false)
    expect(
      dashboardRetry(
        0,
        new ApiError({
          message: 'bad',
          status: 400,
          requestId: null,
          rateLimit: {
            dimension: null,
            limit: null,
            remaining: null,
            reset: null,
            retryAfter: null,
          },
          cacheStatus: null,
          debugId: null,
          code: null,
        }),
      ),
    ).toBe(false)
  })

  it('delays exponentially with a cap', () => {
    expect(dashboardRetryDelay(0)).toBe(1000)
    expect(dashboardRetryDelay(1)).toBe(2000)
    expect(dashboardRetryDelay(10)).toBe(8000)
  })
})

describe('sso providers', () => {
  it('reads the non-secret provider allow-list from the environment', () => {
    vi.stubEnv('VITE_SSO_PROVIDERS', 'google, github,,okta')
    expect(resolveSsoProviders()).toEqual(['google', 'github', 'okta'])
    vi.unstubAllEnvs()
  })

  it('is empty when unconfigured so the UI greys SSO out', () => {
    vi.stubEnv('VITE_SSO_PROVIDERS', '')
    expect(resolveSsoProviders()).toEqual([])
    vi.unstubAllEnvs()
  })

  it('builds authorization entry URLs against the API base', () => {
    expect(ssoAuthorizationUrl('google')).toContain('/oauth2/authorization/google')
  })
})
