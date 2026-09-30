import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  GatewayClient,
  isStreamingEnabled,
  keyFingerprint,
  mcpErrorMessage,
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

  it('reads the spec-shaped approvals envelope with server identity', async () => {
    const row = {
      tokenId: '9f8e7d6c5b4a3210',
      toolName: 'postgres__run_query',
      serverName: 'postgres',
      ownerId: 'tenant-corp',
      keyName: 'production-key',
      createdAt: '2026-09-01T12:00:00Z',
      expiresAt: '2026-09-01T12:05:00Z',
    }
    stubJson({ approvals: [row, { nope: true }] })
    await expect(new GatewayClient().hitlPending()).resolves.toEqual({ approvals: [row] })
  })

  it('hydrates decrypted approval args and returns decision receipts', async () => {
    stubJson({ tokenId: 'abc', toolName: 't', args: { q: 'select 1' } })
    const detail = await new GatewayClient().hitlDetail('9f8e7d6c5b4a32109f8e7d6c5b4a3210')
    expect(detail).toMatchObject({ tokenId: 'abc' })
    await expect(new GatewayClient().hitlDetail('bad id!')).rejects.toThrow(/lowercase hex/i)
    stubJson({ status: 'APPROVED', tokenId: '9f8e7d6c5b4a3210', message: 'ok' })
    await expect(
      new GatewayClient().decideHitl('9f8e7d6c5b4a3210', true, {
        reason: 'Looks safe',
        decidedBy: 'on-call',
      }),
    ).resolves.toMatchObject({ status: 'APPROVED' })
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

  it('searches the admin model catalog with defaults, clamping, and drift safety', async () => {
    const seen: string[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((url: unknown) => {
        seen.push(String(url))
        return Promise.resolve(
          new Response(
            JSON.stringify({
              models: [
                {
                  modelId: 'gpt-4o',
                  provider: 'openai',
                  mode: 'chat',
                  inputCostPerToken: 0.000005,
                  outputCostPerToken: 0.000015,
                  cacheReadInputTokenCost: null,
                  cacheCreationInputTokenCost: null,
                  maxInputTokens: 128000,
                  maxOutputTokens: 16384,
                  qualityTier: 'FRONTIER',
                  benchmarkRefs: null,
                  embeddingDimensions: null,
                },
                { modelId: 42 },
              ],
            }),
            { headers: { 'content-type': 'application/json' } },
          ),
        )
      }),
    )
    const out = await new GatewayClient().searchModelCatalog({
      provider: 'openai',
      q: 'gpt',
      limit: 500,
    })
    expect(out.models.map((m) => m.modelId)).toEqual(['gpt-4o'])
    expect(seen[0]).toContain('/v1/admin/model-catalog')
    expect(seen[0]).toContain('limit=200')
    stubJson({ models: {} })
    await expect(new GatewayClient().searchModelCatalog()).resolves.toEqual({ models: [] })
  })

  it('lists user summaries as a page with identity-only rows', async () => {
    stubJson({
      content: [
        {
          userId: '123e4567-e89b-12d3-a456-426614174000',
          username: 'alice',
          admin: false,
          disabled: false,
          createdAt: '2026-09-01T00:00:00Z',
        },
        { username: 'nameless' },
      ],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      hasNext: false,
    })
    await expect(new GatewayClient().listUsers()).resolves.toMatchObject({
      users: [{ username: 'alice' }],
      totalElements: 1,
    })
    stubJson({ content: {} })
    await expect(new GatewayClient().listUsers()).resolves.toMatchObject({ users: [] })
  })

  it('parses nullable embedding dimensions on catalog entries', async () => {
    stubJson({
      models: [
        {
          modelId: 'text-embedding-3-small',
          provider: 'openai',
          mode: 'embeddings',
          inputCostPerToken: 0.00000002,
          outputCostPerToken: 0,
          cacheReadInputTokenCost: null,
          cacheCreationInputTokenCost: null,
          maxInputTokens: 8191,
          maxOutputTokens: null,
          qualityTier: null,
          benchmarkRefs: null,
          embeddingDimensions: 1536,
        },
        {
          modelId: 'gpt-4o',
          provider: 'openai',
          mode: 'chat',
          inputCostPerToken: 0.000005,
          outputCostPerToken: 0.000015,
          cacheReadInputTokenCost: null,
          cacheCreationInputTokenCost: null,
          maxInputTokens: 128000,
          maxOutputTokens: 16384,
          qualityTier: 'FRONTIER',
          benchmarkRefs: null,
          embeddingDimensions: null,
        },
      ],
    })
    const out = await new GatewayClient().searchModelCatalog({})
    expect(out.models.map((m) => m.embeddingDimensions)).toEqual([1536, null])
  })

  it('sets and revokes the self-service default key with hash validation', async () => {
    const seen: string[] = []
    let method = 'PUT'
    vi.stubGlobal(
      'fetch',
      vi.fn((url: unknown, init?: { method?: string }) => {
        seen.push(`${init?.method ?? 'GET'} ${String(url)}`)
        method = init?.method ?? 'GET'
        return Promise.resolve(new Response(null, { status: 204 }))
      }),
    )
    const hash = 'a'.repeat(64)
    await new GatewayClient().setDefaultKey(hash)
    expect(seen[0]).toContain('/v1/me/keys/default')
    expect(method).toBe('PUT')
    await new GatewayClient().revokeOwnKey(hash)
    expect(seen[1]).toContain(`/v1/me/keys/${hash}/revoke`)
    expect(() => new GatewayClient().setDefaultKey('short')).toThrow(/64-char/i)
    expect(() => new GatewayClient().revokeOwnKey('short')).toThrow(/64-char/i)
  })

  it('creates invites and manages notifications with contract validation', async () => {
    stubJson({ link: 'http://x/redeem?token=abc', emailed: false })
    await expect(new GatewayClient().createInvite({})).resolves.toMatchObject({ emailed: false })
    stubJson([
      {
        id: 'n1',
        scope: 'budgets',
        channel: 'webhook',
        target: 'https://ops.example.com/hook',
        secretRef: null,
        minSeverity: 'warning',
        createdAt: '2026-09-01T12:00:00Z',
      },
      { id: 7 },
    ])
    const notes = await new GatewayClient().listNotifications('budgets')
    expect(notes.notifications.map((n) => n.id)).toEqual(['n1'])
    await expect(new GatewayClient().listNotifications('  ')).rejects.toThrow(/scope is required/i)
    await expect(
      new GatewayClient().createNotification({ scope: 's', channel: 'pager', target: 't' }),
    ).rejects.toThrow(/channel must be/i)
    stubJson({})
    await new GatewayClient().setUserDisabled('123e4567-e89b-12d3-a456-426614174000', true)
    await new GatewayClient().deleteUser('123e4567-e89b-12d3-a456-426614174000')
    await new GatewayClient().deleteNotification('123e4567-e89b-12d3-a456-426614174000')
  })

  it('reads budget balance and holds without fabricating spend', async () => {
    stubJson({
      level: 'KEY',
      subject: 'abc',
      minuteLimitMicros: 1000,
      minuteSpentMicros: 100,
      monthLimitMicros: 10000,
      monthSpentMicros: 500,
    })
    await expect(new GatewayClient().getBudgetBalance('KEY', 'abc')).resolves.toMatchObject({
      minuteSpentMicros: 100,
    })
    stubJson({
      requestId: 'r1',
      subject: 'abc',
      heldMicros: 50,
      settledMicros: null,
      state: 'HOLD',
    })
    await expect(new GatewayClient().getBudgetHold('r1')).resolves.toMatchObject({ state: 'HOLD' })
    stubJson({ nope: true })
    await expect(new GatewayClient().getBudgetHold('r1')).resolves.toBeNull()
  })

  it('updates and deletes budgets with full-replace semantics', async () => {
    const seen: string[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((url: unknown, init?: { method?: string }) => {
        seen.push(`${init?.method ?? 'GET'} ${String(url)}`)
        if ((init?.method ?? 'GET') === 'DELETE') {
          return Promise.resolve(new Response(null, { status: 204 }))
        }
        return Promise.resolve(
          new Response(
            JSON.stringify({
              id: 'b1',
              level: 'KEY',
              subjectId: 'abc',
              minuteMicros: 1000,
              monthMicros: 0,
              webhookUrl: null,
              createdAt: '2026-09-01T12:00:00Z',
              updatedAt: '2026-09-01T12:00:00Z',
            }),
            { headers: { 'content-type': 'application/json' } },
          ),
        )
      }),
    )
    await expect(
      new GatewayClient().updateBudget('b1', { minuteMicros: 1000, monthMicros: 0 }),
    ).resolves.toMatchObject({ id: 'b1' })
    expect(seen[0]).toContain('/v1/admin/budgets/b1')
    await new GatewayClient().deleteBudget('b1')
    expect(seen[1]).toContain('/v1/admin/budgets/b1')
  })

  it('reads cache tiers with nullable dead-tier metrics', async () => {
    stubJson({
      generatedAt: '2026-09-01T12:00:00Z',
      accounting: { reachable: false },
      cache: {
        reachable: true,
        usedBytes: 1024,
        maxBytes: 4096,
        usedPercent: 25,
        maxmemoryPolicy: 'allkeys-lru',
        evictedKeysTotal: 3,
        keyspaceHits: 100,
        keyspaceMisses: 10,
      },
    })
    const tiers = await new GatewayClient().cacheTiers()
    expect(tiers.accounting.reachable).toBe(false)
    expect(tiers.accounting.usedBytes).toBeNull()
    expect(tiers.cache.usedPercent).toBe(25)
  })

  it('reports purge counts from the full purge receipt', async () => {
    stubJson({
      success: true,
      message: 'Purged cache for tenant t: 7 keys',
      evictedScope: 't',
      evictedKeys: 7,
    })
    await expect(new GatewayClient().purgeCache('t')).resolves.toMatchObject({ evictedKeys: 7 })
  })

  it('reads single circuits and MCP circuits with full snapshots', async () => {
    stubJson({
      provider: 'openai',
      state: 'CLOSED',
      failures: 0,
      cooldownMsRemaining: 0,
      halfOpenProbe: false,
    })
    await expect(new GatewayClient().getCircuit('openai')).resolves.toMatchObject({
      failures: 0,
    })
    await expect(new GatewayClient().resetCircuit('openai')).resolves.toMatchObject({
      cooldownMsRemaining: 0,
    })
    stubJson([
      {
        provider: 'postgres',
        state: 'OPEN',
        failures: 3,
        cooldownMsRemaining: 1000,
        halfOpenProbe: false,
      },
    ])
    const mcp = await new GatewayClient().listMcpCircuits()
    expect(mcp.circuits.map((c) => c.provider)).toEqual(['postgres'])
    stubJson({
      provider: 'postgres',
      state: 'CLOSED',
      failures: 0,
      cooldownMsRemaining: 0,
      halfOpenProbe: false,
    })
    await expect(new GatewayClient().resetMcpCircuit('postgres')).resolves.toMatchObject({
      state: 'CLOSED',
    })
  })

  it('reads model quality as rated-or-null and writes with tier validation', async () => {
    stubJson({
      modelId: 'gpt-4o',
      tier: 'FRONTIER',
      benchmarkRefs: null,
      updatedAt: '2026-09-01T12:00:00Z',
    })
    await expect(new GatewayClient().getModelQuality('gpt-4o')).resolves.toMatchObject({
      tier: 'FRONTIER',
    })
    await expect(
      new GatewayClient().setModelQuality('gpt-4o', { tier: 'STANDARD' }),
    ).resolves.toMatchObject({ modelId: 'gpt-4o' })
    await expect(new GatewayClient().setModelQuality('gpt-4o', { tier: 'fancy' })).rejects.toThrow(
      /tier must be FRONTIER, STANDARD, or BUDGET/i,
    )
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
      content: [
        {
          id: 'u1',
          requestId: 'r1',
          ownerId: 'tenant-corp',
          provider: 'openai',
          model: 'm',
          promptTokens: 8,
          completionTokens: 4,
          totalTokens: 12,
          costUsdMicros: 5,
          costUsd: '0.000005',
          durationMs: 41,
          createdAt: 't',
        },
        { nope: true },
      ],
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

  it('scopes ledger pages with server filters and sort', async () => {
    const seen: string[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((url: unknown) => {
        seen.push(String(url))
        return Promise.resolve(
          new Response(
            JSON.stringify({
              content: [],
              page: 0,
              size: 25,
              totalElements: 0,
              totalPages: 0,
              hasNext: false,
            }),
            { headers: { 'content-type': 'application/json' } },
          ),
        )
      }),
    )
    await new GatewayClient().ledgerLogs(0, 25, {
      ownerId: 'tenant-corp',
      provider: 'openai',
      model: 'gpt-4o',
      from: '2026-09-01T00:00:00Z',
      to: '2026-09-26T00:00:00Z',
      sort: 'costUsdMicros',
    })
    expect(seen[0]).toContain('ownerId=tenant-corp')
    expect(seen[0]).toContain('provider=openai')
    expect(seen[0]).toContain('model=gpt-4o')
    expect(seen[0]).toContain('sort=costUsdMicros')
    await new GatewayClient().ledgerLogs(0, 25)
    expect(seen[1]).not.toContain('ownerId=')
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

  it('maps conflict, payload, guardrail, and upstream statuses distinctly', () => {
    expect(safeErrorMessage(409, '')).toContain('Conflict')
    expect(safeErrorMessage(413, '')).toContain('too large')
    expect(safeErrorMessage(422, '')).toContain('Unprocessable')
    expect(safeErrorMessage(502, '')).toContain('provider')
    expect(safeErrorMessage(504, '')).toContain('timed out')
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

  it('passes extended chat and embedding fields through to the wire', async () => {
    let chatBody: unknown = null
    let embBody: unknown = null
    vi.stubGlobal(
      'fetch',
      vi.fn((url: string, init: RequestInit) => {
        if (url === '/v1/chat/completions') {
          chatBody = JSON.parse((init.body ?? '{}') as string) as unknown
          return Promise.resolve(
            new Response(JSON.stringify({ choices: [], model: 'm' }), { status: 200 }),
          )
        }
        embBody = JSON.parse((init.body ?? '{}') as string) as unknown
        return Promise.resolve(
          new Response(
            JSON.stringify({
              data: [{ embedding: [0.1], index: 0 }],
              model: 'e',
              usage: { prompt_tokens: 5, total_tokens: 5 },
            }),
            { status: 200 },
          ),
        )
      }),
    )
    await new GatewayClient({ base: '', token: 'gw-test' }).chat({
      model: 'm',
      messages: [{ role: 'user', content: 'hi' }],
      temperature: 0.7,
      top_p: 0.9,
      seed: 42,
      reasoning_effort: 'low',
    })
    expect(chatBody).toMatchObject({ temperature: 0.7, top_p: 0.9, seed: 42 })
    const emb = await new GatewayClient({ base: '', token: 'gw-test' }).embeddings({
      model: 'e',
      input: 'hi',
      dimensions: 512,
      encoding_format: 'float',
      user: 'op',
    })
    expect(embBody).toMatchObject({ dimensions: 512, encoding_format: 'float', user: 'op' })
    expect(emb.usage).toMatchObject({ prompt_tokens: 5, total_tokens: 5 })
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
          new Response(
            JSON.stringify({
              success: true,
              message: 'Purged cache for tenant tenant-corp: 3 keys',
              evictedScope: 'tenant-corp',
              evictedKeys: 3,
            }),
            { status: 200 },
          ),
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

  it('invokes a tool and maps RPC error codes honestly', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify({ jsonrpc: '2.0', id: 'call-1', result: { ok: true } }), {
            status: 200,
          }),
        ),
      ),
    )
    await expect(
      new GatewayClient({ base: '', token: 'gw-test' }).mcpCall('a__b', { q: 1 }),
    ).resolves.toMatchObject({ ok: true })
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(
            JSON.stringify({
              jsonrpc: '2.0',
              id: 'call-1',
              error: { code: -32601, message: 'Method not found' },
            }),
            { status: 200 },
          ),
        ),
      ),
    )
    await expect(
      new GatewayClient({ base: '', token: 'gw-test' }).mcpCall('a__b', {}),
    ).rejects.toThrow(/method not found/i)
  })

  it('names every RPC fault with and without server detail', () => {
    expect(mcpErrorMessage(-32700, 'oops')).toContain('Parse error')
    expect(mcpErrorMessage(-32600, null)).toContain('Invalid request')
    expect(mcpErrorMessage(-32601, 'gone')).toContain('Method not found')
    expect(mcpErrorMessage(-32602, '')).toContain('Invalid params')
    expect(mcpErrorMessage(-32603, 'boom')).toContain('Tool failed')
    expect(mcpErrorMessage(-32020, null)).toContain('Header mismatch')
    expect(mcpErrorMessage(-32021, 'cap')).toContain('Missing capability')
    expect(mcpErrorMessage(-32022, null)).toContain('Unsupported protocol version')
    expect(mcpErrorMessage(-32099, 'weird')).toContain('-32099')
    expect(mcpErrorMessage(-32099, null)).toContain('Tool error')
  })

  it('reads A2A agent cards and relays JSON-RPC with method gating', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(
            JSON.stringify({
              protocolVersion: '0.3',
              name: 'Helper',
              url: 'http://localhost:8080/v1/a2a/helper',
              description: 'Helps out',
              version: '1.0.0',
            }),
            { status: 200 },
          ),
        ),
      ),
    )
    await expect(
      new GatewayClient({ base: '', token: 'gw-test' }).a2aCard('helper'),
    ).resolves.toMatchObject({ name: 'Helper' })
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify({ jsonrpc: '2.0', id: 1, result: { done: true } }), {
            status: 200,
          }),
        ),
      ),
    )
    await expect(
      new GatewayClient({ base: '', token: 'gw-test' }).a2aInvoke('helper', 'message/send', {
        text: 'hi',
      }),
    ).resolves.toMatchObject({ done: true })
    await expect(
      new GatewayClient({ base: '', token: 'gw-test' }).a2aInvoke('helper', 'bogus/method', {}),
    ).rejects.toThrow(/unknown A2A method/i)
  })

  it('probes alert webhooks and reports the receipt count', async () => {
    stubJson({ received: 1 })
    await expect(
      new GatewayClient().sendAlertProbe([{ labels: { alertname: 'Test' } }]),
    ).resolves.toEqual({ received: 1 })
    await expect(new GatewayClient().sendAlertProbe([])).rejects.toThrow(/non-empty/i)
    await expect(
      new GatewayClient().sendAlertProbe(new Array(101).fill({ labels: {} })),
    ).rejects.toThrow(/at most 100/i)
    stubJson({ nope: true })
    await expect(new GatewayClient().sendAlertProbe([{ labels: {} }])).rejects.toThrow(
      /changed shape/i,
    )
  })

  it('rejects contract-violating inputs before sending', async () => {
    await expect(new GatewayClient().createInvite({ email: 'bad' })).rejects.toThrow(/valid/i)
    await expect(
      new GatewayClient().createNotification({ scope: '', channel: 'webhook', target: 't' }),
    ).rejects.toThrow(/1-160/i)
    await expect(
      new GatewayClient().createNotification({ scope: 's', channel: 'webhook', target: '' }),
    ).rejects.toThrow(/1-512/i)
    await expect(
      new GatewayClient().createNotification({
        scope: 's',
        channel: 'webhook',
        target: 't',
        secretRef: 'lowercase',
      }),
    ).rejects.toThrow(/environment variable/i)
    await expect(
      new GatewayClient().createNotification({
        scope: 's',
        channel: 'webhook',
        target: 't',
        minSeverity: 'info',
      }),
    ).rejects.toThrow(/warning or critical/i)
    await expect(new GatewayClient().setModelQuality('m', { tier: 'fancy' })).rejects.toThrow(
      /FRONTIER/i,
    )
    await expect(new GatewayClient().setModelQuality('', { tier: 'FRONTIER' })).rejects.toThrow(
      /model id/i,
    )
    await expect(
      new GatewayClient().setModelQuality('m', {
        tier: 'FRONTIER',
        benchmarkRefs: 'x'.repeat(2001),
      }),
    ).rejects.toThrow(/2000/i)
    stubJson({ nope: true })
    await expect(new GatewayClient().searchModelCatalog()).resolves.toEqual({ models: [] })
    stubJson({ nope: true })
    await expect(new GatewayClient().getModelQuality('m')).resolves.toBeNull()
    stubJson({ nope: true })
    await expect(new GatewayClient().getBudgetHold('r')).resolves.toBeNull()
    stubJson({ nope: true })
    await expect(new GatewayClient().getCircuit('openai')).resolves.toBeNull()
    stubJson({ nope: true })
    await expect(new GatewayClient().getMcpCircuit('postgres')).resolves.toBeNull()
  })

  it('rethrows non-404 failures from nullable single reads', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response('x', { status: 500 }))),
    )
    await expect(new GatewayClient().getCircuit('openai')).rejects.toThrow(/HTTP 500/)
    await expect(new GatewayClient().getMcpCircuit('postgres')).rejects.toThrow(/HTTP 500/)
    await expect(new GatewayClient().getBudgetHold('r1')).rejects.toThrow(/HTTP 500/)
    await expect(new GatewayClient().getModelQuality('m')).rejects.toThrow(/HTTP 500/)
  })

  it('reads unknown singles as null without throwing', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response('x', { status: 404 }))),
    )
    await expect(new GatewayClient().getCircuit('openai')).resolves.toBeNull()
    await expect(new GatewayClient().getMcpCircuit('postgres')).resolves.toBeNull()
  })

  it('rejects empty tool payloads and transport failures', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response('null', { status: 200 }))),
    )
    await expect(new GatewayClient().mcpCall('a__b', {})).rejects.toThrow(/no payload/i)
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response('x', { status: 502 }))),
    )
    await expect(new GatewayClient().mcpCall('a__b', {})).rejects.toThrow(/could serve/i)
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.reject(new Error('down'))),
    )
    await expect(new GatewayClient().mcpCall('a__b', {})).rejects.toThrow(/unreachable/i)
  })

  it('degrades non-array notification lists to empty', async () => {
    stubJson({ notifications: {} })
    await expect(new GatewayClient().listNotifications('budgets')).resolves.toEqual({
      notifications: [],
    })
  })

  it('relays agent faults with defaults and null results', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify({ jsonrpc: '2.0', id: 1, error: {} }), { status: 200 }),
        ),
      ),
    )
    await expect(new GatewayClient().a2aInvoke('helper', 'message/send', {})).rejects.toThrow(
      /tool failed/i,
    )
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(new Response(JSON.stringify({ jsonrpc: '2.0', id: 1 }), { status: 200 })),
      ),
    )
    await expect(new GatewayClient().a2aInvoke('helper', 'message/send', {})).resolves.toBeNull()
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response('null', { status: 200 }))),
    )
    await expect(new GatewayClient().a2aInvoke('helper', 'message/send', {})).rejects.toThrow(
      /no payload/i,
    )
  })

  it('decides approvals without rationale and maps RPC faults', async () => {
    stubJson({ status: 'REJECTED', tokenId: 'abc', message: 'purged' })
    await expect(new GatewayClient().decideHitl('abc', false)).resolves.toMatchObject({
      status: 'REJECTED',
    })
    stubJson({ nope: true })
    await expect(new GatewayClient().decideHitl('abc', true, { reason: 'x' })).rejects.toThrow(
      /changed shape/i,
    )
    stubJson({ nope: true })
    await expect(new GatewayClient().a2aCard('helper')).rejects.toThrow(/changed shape/i)
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(
            JSON.stringify({ jsonrpc: '2.0', id: 1, error: { code: -32602, message: 'Bad' } }),
            { status: 200 },
          ),
        ),
      ),
    )
    await expect(new GatewayClient().a2aInvoke('helper', 'message/send', {})).rejects.toThrow(
      /invalid params/i,
    )
  })

  it('rejects empty tool payloads and transport failures', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response('null', { status: 200 }))),
    )
    await expect(new GatewayClient().mcpCall('a__b', {})).rejects.toThrow(/no payload/i)
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response('x', { status: 502 }))),
    )
    await expect(new GatewayClient().mcpCall('a__b', {})).rejects.toThrow(/could serve/i)
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

describe('orgs and teams', () => {
  it('lists orgs drift-safe and manages the local lifecycle', async () => {
    const seen: string[] = []
    const bodies: unknown[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((url: unknown, init?: { method?: string; body?: string }) => {
        seen.push(`${init?.method ?? 'GET'} ${String(url)}`)
        if (init?.body !== undefined) bodies.push(JSON.parse(init.body))
        const method = init?.method ?? 'GET'
        const urlStr = String(url)
        if (method === 'GET' && urlStr.endsWith('/v1/admin/orgs')) {
          return Promise.resolve(
            new Response(
              JSON.stringify([{ id: 'o1', slug: 'acme', displayName: 'Acme' }, { id: 7 }]),
              { headers: { 'content-type': 'application/json' } },
            ),
          )
        }
        if (method === 'POST' && urlStr.endsWith('/v1/admin/orgs')) {
          return Promise.resolve(
            new Response(JSON.stringify({ id: 'o2', slug: 'globex', displayName: 'Globex' }), {
              status: 201,
              headers: { 'content-type': 'application/json' },
            }),
          )
        }
        if (method === 'PATCH' && urlStr.includes('/v1/admin/orgs/')) {
          return Promise.resolve(
            new Response(JSON.stringify({ id: 'o1', slug: 'acme', displayName: 'Acme Inc' }), {
              headers: { 'content-type': 'application/json' },
            }),
          )
        }
        if (method === 'POST' && urlStr.includes('/teams')) {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                teamId: 't9',
                orgSlug: 'acme',
                name: 'Ops',
                idpGroupId: 'local:acme',
                activeMembers: 0,
              }),
              { status: 201, headers: { 'content-type': 'application/json' } },
            ),
          )
        }
        if (method === 'PATCH' && urlStr.includes('/v1/admin/teams/')) {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                teamId: 't9',
                orgSlug: 'acme',
                name: 'Operations',
                idpGroupId: 'local:acme',
                activeMembers: 0,
              }),
              { headers: { 'content-type': 'application/json' } },
            ),
          )
        }
        if (method === 'PUT' && urlStr.includes('/members/')) {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                userId: '123e4567-e89b-12d3-a456-426614174000',
                teamId: 't9',
                role: 'LEAD',
                status: 'ACTIVE',
              }),
              { headers: { 'content-type': 'application/json' } },
            ),
          )
        }
        if (method === 'DELETE' && urlStr.includes('/members/')) {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                userId: '123e4567-e89b-12d3-a456-426614174000',
                teamId: 't9',
                role: 'LEAD',
                status: 'INACTIVE',
              }),
              { headers: { 'content-type': 'application/json' } },
            ),
          )
        }
        return Promise.resolve(new Response(null, { status: 204 }))
      }),
    )
    const client = new GatewayClient()
    const orgs = await client.listOrgs()
    expect(orgs.map((o) => o.slug)).toEqual(['acme'])
    const created = await client.createOrg({ slug: ' Globex ', displayName: ' Globex ' })
    expect(created.slug).toBe('globex')
    expect(bodies[0]).toMatchObject({ slug: 'Globex', displayName: 'Globex' })
    const renamed = await client.renameOrg('o1', { displayName: 'Acme Inc' })
    expect(renamed.displayName).toBe('Acme Inc')
    await client.deleteOrg('o1')
    expect(seen).toContain('DELETE /v1/admin/orgs/o1')
    const team = await client.createTeam('o1', { name: 'Ops' })
    expect(team.activeMembers).toBe(0)
    const teamRenamed = await client.renameTeam('t9', { displayName: 'Operations' })
    expect(teamRenamed.name).toBe('Operations')
    expect(bodies).toContainEqual({ displayName: 'Operations' })
    await client.deleteTeam('t9')
    const assigned = await client.assignMember('t9', '123e4567-e89b-12d3-a456-426614174000', {
      role: 'LEAD',
    })
    expect(assigned.status).toBe('ACTIVE')
    const revoked = await client.revokeMember('t9', '123e4567-e89b-12d3-a456-426614174000')
    expect(revoked.status).toBe('INACTIVE')
    await expect(client.createOrg({ slug: '', displayName: 'x' })).rejects.toThrow(/slug/i)
    await expect(client.createOrg({ slug: 'a'.repeat(65), displayName: 'x' })).rejects.toThrow(/64/)
    await expect(client.assignMember('t9', 'not-a-uuid', { role: 'MEMBER' })).rejects.toThrow(
      /uuid/i,
    )
    await expect(
      client.assignMember('t9', '123e4567-e89b-12d3-a456-426614174000', {
        role: 'OWNER',
      }),
    ).rejects.toThrow(/MEMBER\|LEAD/i)
  })

  it('rejects malformed org, team, and member inputs before sending', async () => {
    const client = new GatewayClient()
    await expect(client.createOrg({ slug: 'ok', displayName: '' })).rejects.toThrow(/required/i)
    await expect(client.createOrg({ slug: 'ok', displayName: 'x'.repeat(129) })).rejects.toThrow(
      /128/,
    )
    await expect(client.createOrg({ slug: 'bad slug!', displayName: 'x' })).rejects.toThrow(
      /letters, digits/,
    )
    await expect(client.renameOrg('  ', { displayName: 'x' })).rejects.toThrow(/org id/i)
    await expect(client.renameOrg('o1', { displayName: '' })).rejects.toThrow(/required/i)
    await expect(client.renameOrg('o1', { displayName: 'x'.repeat(129) })).rejects.toThrow(/128/)
    expect(() => client.deleteOrg('  ')).toThrow(/org id/i)
    await expect(client.createTeam('  ', { name: 'x' })).rejects.toThrow(/org id/i)
    await expect(client.createTeam('o1', { name: '' })).rejects.toThrow(/required/i)
    await expect(client.createTeam('o1', { name: 'x'.repeat(129) })).rejects.toThrow(/128/)
    await expect(client.renameTeam('  ', { displayName: 'x' })).rejects.toThrow(/team id/i)
    await expect(client.renameTeam('t1', { displayName: '' })).rejects.toThrow(/required/i)
    await expect(client.renameTeam('t1', { displayName: 'x'.repeat(129) })).rejects.toThrow(/128/)
    expect(() => client.deleteTeam('  ')).toThrow(/team id/i)
    await expect(
      client.assignMember('  ', '123e4567-e89b-12d3-a456-426614174000', {
        role: 'MEMBER',
      }),
    ).rejects.toThrow(/team id/i)
    await expect(client.revokeMember('t1', 'not-a-uuid')).rejects.toThrow(/uuid/i)
    await expect(client.revokeMember('  ', '123e4567-e89b-12d3-a456-426614174000')).rejects.toThrow(
      /team id/i,
    )
    await expect(client.createInvite({ email: 'bad' })).rejects.toThrow(/valid/i)
    stubJson({ link: 'http://x/redeem?token=blank', emailed: false })
    await expect(new GatewayClient().createInvite({ teamId: '   ' })).resolves.toMatchObject({
      emailed: false,
    })
  })

  it('sends optional invite team placement and validates it', async () => {
    let body: unknown = null
    vi.stubGlobal(
      'fetch',
      vi.fn((_url: unknown, init?: { body?: string }) => {
        if (init?.body !== undefined) body = JSON.parse(init.body)
        return Promise.resolve(
          new Response(JSON.stringify({ link: 'http://x/redeem?token=t', emailed: false }), {
            status: 201,
            headers: { 'content-type': 'application/json' },
          }),
        )
      }),
    )
    await new GatewayClient().createInvite({
      teamId: '123e4567-e89b-12d3-a456-426614174000',
    })
    expect(body).toMatchObject({ teamId: '123e4567-e89b-12d3-a456-426614174000' })
    await new GatewayClient().createInvite({})
    expect(body).toMatchObject({ teamId: null })
    await expect(new GatewayClient().createInvite({ teamId: 'nope' })).rejects.toThrow(/uuid/i)
  })
})
