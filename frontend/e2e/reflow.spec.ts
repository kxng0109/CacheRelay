import { expect, test, type Page } from '@playwright/test'

/**
 * DEF-04: 320 px reflow proof for the 13 `TableScroll`-wrapped tables.
 * At 320×640 (or 400 % zoom) the page itself must never scroll
 * horizontally (WCAG 2.2 SC 1.4.10); each table scrolls inside its own
 * region instead. Admin JSON is stubbed per route so tables actually
 * render without a backend; the DEV seed supplies the session tier.
 */

test.use({ viewport: { width: 320, height: 640 } })

const SUMMARY = {
  totalRequests: 1,
  totalPromptTokens: 0,
  totalCompletionTokens: 0,
  totalTokens: 0,
  totalCostUsdMicros: 12,
  totalCostUsd: '0.000012',
  averageDurationMs: 3,
  byOwner: [],
  byModel: [],
  byProvider: [],
}

const ENTRIES = {
  content: [
    {
      requestId: 'r1',
      model: 'gpt-4o-mini',
      costUsdMicros: 12,
      createdAt: '2026-09-17T00:00:00Z',
    },
  ],
  page: 0,
  size: 25,
  totalElements: 1,
  totalPages: 1,
  hasNext: false,
}

const KEYS = [
  {
    keyId: 'k1',
    keyPrefix: 'gw-',
    ownerId: 'tenant-corp',
    name: 'ci-key',
    rpmLimit: 60,
    tpmLimit: 100000,
    allowedModels: ['gpt-4o-mini'],
    allowedProviders: [],
    enabled: true,
    createdAt: '2026-09-17T00:00:00Z',
  },
]

const STATS = {
  enabled: true,
  defaultScope: 'TENANT',
  similarityThreshold: 0.92,
  embeddingModel: 'test-embed',
  l0MaxBytes: 1048576,
  l0InMemoryTtlSeconds: 300,
  l1RedisEnabled: true,
  l2SemanticEnabled: true,
  polarityGuardEnabled: true,
  entityGuardEnabled: true,
}

const BUDGETS = [
  {
    id: 'b1',
    level: 'TEAM',
    subjectId: 'tenant-corp',
    minuteMicros: 100,
    monthMicros: 5000,
    webhookUrl: null,
    createdAt: '2026-09-18T00:00:00Z',
    updatedAt: '2026-09-18T00:00:00Z',
  },
]

const ALIASES = {
  models: [
    {
      name: 'db-fast',
      chain: [{ providerName: 'openai', modelOverride: null }],
      strategy: 'RACE',
      source: 'database',
    },
  ],
}

const PROVIDERS = {
  providers: [
    {
      name: 'openai',
      type: 'OPENAI',
      baseUrl: null,
      keyConfigured: true,
      connectTimeoutSeconds: 5,
      requestTimeoutSeconds: 60,
      embeddingSingleAsString: false,
      circuitState: 'CLOSED',
      aliasReferences: 1,
      validationStatus: 'LIVE_VERIFIED',
    },
  ],
}

/**
 * Stubs every admin JSON surface the reflow routes read, plus the
 * approvals badge query the shell fires for admin sessions.
 *
 * @param page - Playwright page before navigation.
 */
async function stubAdminTables(page: Page): Promise<void> {
  const json = (body: unknown) => ({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(body),
  })
  await page.route('**/v1/admin/ledger/summary', (route) => route.fulfill(json(SUMMARY)))
  await page.route('**/v1/admin/ledger/entries*', (route) => route.fulfill(json(ENTRIES)))
  await page.route('**/v1/admin/keys', (route) => route.fulfill(json(KEYS)))
  await page.route('**/v1/admin/cache/stats', (route) => route.fulfill(json(STATS)))
  await page.route('**/v1/admin/budgets', (route) => route.fulfill(json(BUDGETS)))
  await page.route('**/v1/admin/models', (route) => route.fulfill(json(ALIASES)))
  await page.route('**/v1/admin/providers', (route) => route.fulfill(json(PROVIDERS)))
  await page.route('**/v1/admin/mcp/approvals/pending', (route) =>
    route.fulfill(json({ approvals: [] })),
  )
}

const SCREENS: { route: string; heading: string; seed?: 'user' | 'admin'; tables: number }[] = [
  { route: '/', heading: 'Overview', seed: 'user', tables: 0 },
  { route: '/playground', heading: 'Playground', tables: 0 },
  { route: '/embeddings', heading: 'Embeddings', tables: 0 },
  { route: '/mcp', heading: 'MCP tools', tables: 0 },
  { route: '/ledger', heading: 'Ledger', seed: 'admin', tables: 1 },
  { route: '/keys', heading: 'Keys', seed: 'admin', tables: 1 },
  { route: '/cache', heading: 'Cache and budgets', seed: 'admin', tables: 1 },
  { route: '/models', heading: 'Models', seed: 'admin', tables: 2 },
]

for (const { route, heading, seed, tables } of SCREENS) {
  test(`reflow clean at 320px: ${route}`, async ({ page }) => {
    await stubAdminTables(page)
    if (seed === undefined) {
      await page.goto(route)
    } else {
      await page.goto(`/__test/session/${seed}?next=${route}`)
    }
    await expect(page.getByRole('heading', { level: 1, name: heading })).toBeVisible()
    if (tables > 0) {
      // Wait for the stubbed rows to render before measuring.
      await page.waitForFunction(
        (expected) => document.querySelectorAll('table').length >= expected,
        tables,
      )
    }
    // Settle late webfonts before measuring: a mid-swap measure flakes.
    // (`networkidle` never fires here — the app polls on a 15 s cadence.)
    await page.evaluate(() => document.fonts.ready)
    // Page-level must not scroll horizontally at 320 px.
    const pageWidth: number = await page.evaluate(() => document.documentElement.scrollWidth)
    expect(pageWidth).toBeLessThanOrEqual(320)
    // Every rendered table scrolls inside its own region.
    const unwrapped: string[] = await page.evaluate(() => {
      const bad: string[] = []
      document.querySelectorAll('table').forEach((t) => {
        if (t.closest('.overflow-x-auto') === null) {
          bad.push(t.querySelector('caption')?.textContent ?? 'table')
        }
      })
      return bad
    })
    expect(unwrapped).toEqual([])
  })
}
