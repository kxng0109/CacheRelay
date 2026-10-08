import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { CachePage } from './page.js'

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

describe('CachePage', () => {
  beforeEach(() => {
    // Tier telemetry fires on mount; default to an unreachable probe so
    // stats/budget-focused tests stay isolated (scenarios override).
    server.use(
      http.get('*/v1/admin/cache/tiers', () =>
        HttpResponse.json({
          generatedAt: '2026-09-01T12:00:00Z',
          accounting: { reachable: false },
          cache: { reachable: false },
        }),
      ),
    )
  })

  it('mounts the board without a session (router guards access)', () => {
    renderApp(<CachePage />)
    expect(screen.getByText(/loading cache config/i)).toBeInTheDocument()
  })

  it('renders tier config and budget caps', async () => {
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () =>
        HttpResponse.json([
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
        ]),
      ),
    )
    renderApp(<CachePage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText('tenant-corp')).toBeInTheDocument()
    })
    expect(screen.getByText('On')).toBeInTheDocument()
    expect(screen.getByText(/scope TENANT/i)).toBeInTheDocument()
    expect(screen.getByText('Cache on · exact on · semantic on')).toBeInTheDocument()
    expect(screen.getByText('1 MB')).toBeInTheDocument()
    expect(screen.getByText('Exact on · Semantic on')).toBeInTheDocument()
    expect(screen.getByText('TEAM')).toBeInTheDocument()
    expect(screen.getByText('5,000µ$')).toBeInTheDocument()
  })

  it('shows the empty budget state', async () => {
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([])),
    )
    renderApp(<CachePage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText(/no budgets yet/i)).toBeInTheDocument()
    })
  })

  it('renders tier telemetry with dead-tier honesty', async () => {
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([])),
      http.get('*/v1/admin/cache/tiers', () =>
        HttpResponse.json({
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
        }),
      ),
    )
    renderApp(<CachePage />, { adminSession: true })
    expect(await screen.findByText('accounting')).toBeInTheDocument()
    expect(screen.getByText(/tier telemetry/i)).toBeInTheDocument()
    expect(screen.getByText(/accounting.*unreachable/i)).toBeInTheDocument()
    expect(screen.getByText(/25%/)).toBeInTheDocument()
  })

  it('inspects a budget with live spend, edits caps, and deletes', async () => {
    const user = userEvent.setup()
    const budget = {
      id: 'b1',
      level: 'KEY',
      subjectId: 'abc',
      minuteMicros: 1000,
      monthMicros: 10000,
      webhookUrl: null,
      createdAt: '2026-09-01T12:00:00Z',
      updatedAt: '2026-09-01T12:00:00Z',
    }
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([budget])),
      http.get('*/v1/admin/budgets/KEY/abc/balance', () =>
        HttpResponse.json({
          level: 'KEY',
          subject: 'abc',
          minuteLimitMicros: 1000,
          minuteSpentMicros: 100,
          monthLimitMicros: 10000,
          monthSpentMicros: 500,
        }),
      ),
      http.put('*/v1/admin/budgets/:id', () =>
        HttpResponse.json({ ...budget, minuteMicros: 2000 }),
      ),
      http.delete('*/v1/admin/budgets/:id', () => new HttpResponse(null, { status: 204 })),
    )
    renderApp(<CachePage />, { adminSession: true })
    await user.click(await screen.findByRole('button', { name: /inspect budget abc/i }))
    expect(await screen.findByText(/minute spent/i)).toBeInTheDocument()
    await user.clear(screen.getByLabelText(/minute cap/i))
    await user.type(screen.getByLabelText(/minute cap/i), '2000')
    await user.clear(screen.getByLabelText(/month cap/i))
    await user.type(screen.getByLabelText(/month cap/i), '20000')
    await user.click(screen.getByRole('button', { name: /save caps/i }))
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(/budget updated/i)
    })
    await user.click(screen.getByRole('button', { name: /^delete budget$/i }))
    await user.click(screen.getByRole('button', { name: /^no$/i }))
    expect(screen.queryByText(/budget deleted/i)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /^delete budget$/i }))
    await user.click(screen.getByRole('button', { name: /^yes, delete$/i }))
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(/budget deleted/i)
    })
  })

  it('closes the budget inspector without mutating', async () => {
    const user = userEvent.setup()
    const budget = {
      id: 'b1',
      level: 'KEY',
      subjectId: 'abc',
      minuteMicros: 1000,
      monthMicros: 10000,
      webhookUrl: null,
      createdAt: '2026-09-01T12:00:00Z',
      updatedAt: '2026-09-01T12:00:00Z',
    }
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([budget])),
      http.get('*/v1/admin/budgets/KEY/abc/balance', () =>
        HttpResponse.json({
          level: 'KEY',
          subject: 'abc',
          minuteLimitMicros: 1000,
          minuteSpentMicros: 100,
          monthLimitMicros: 10000,
          monthSpentMicros: 500,
        }),
      ),
    )
    renderApp(<CachePage />, { adminSession: true })
    await user.click(await screen.findByRole('button', { name: /inspect budget abc/i }))
    await screen.findByRole('dialog', { name: /budget inspector/i })
    await user.click(screen.getByRole('button', { name: /close inspector/i }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: /budget inspector/i })).not.toBeInTheDocument()
    })
  })

  it('looks up a hold by request id and names expired holds honestly', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([])),
      http.get('*/v1/admin/budgets/holds/:id', ({ params }) =>
        params.id === 'live-1'
          ? HttpResponse.json({
              requestId: 'live-1',
              subject: 'abc',
              heldMicros: 50,
              settledMicros: null,
              state: 'HOLD',
            })
          : new HttpResponse(null, { status: 404 }),
      ),
    )
    renderApp(<CachePage />, { adminSession: true })
    await user.type(screen.getByLabelText(/hold request id/i), 'live-1')
    await user.click(screen.getByRole('button', { name: /inspect hold/i }))
    expect(await screen.findByText('HOLD')).toBeInTheDocument()
    await user.clear(screen.getByLabelText(/hold request id/i))
    await user.type(screen.getByLabelText(/hold request id/i), 'gone-9')
    await user.click(screen.getByRole('button', { name: /inspect hold/i }))
    await waitFor(() => {
      expect(screen.getByText(/hold expired or unknown/i)).toBeInTheDocument()
    })
  })

  it('shows settled amounts as estimates against final ledger cost', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([])),
      http.get('*/v1/admin/budgets/holds/:id', () =>
        HttpResponse.json({
          requestId: 'set-1',
          subject: 'abc',
          heldMicros: 100,
          settledMicros: 60,
          state: 'SETTLED',
        }),
      ),
    )
    renderApp(<CachePage />, { adminSession: true })
    await user.type(screen.getByLabelText(/hold request id/i), 'set-1')
    await user.click(screen.getByRole('button', { name: /inspect hold/i }))
    expect(await screen.findByText('SETTLED')).toBeInTheDocument()
    expect(screen.getByText(/pre-settle estimate/i)).toBeInTheDocument()
  })

  it('disambiguates zero spend from zero cap', async () => {
    const user = userEvent.setup()
    const budget = {
      id: 'b1',
      level: 'KEY',
      subjectId: 'abc',
      minuteMicros: 0,
      monthMicros: 0,
      webhookUrl: null,
      createdAt: '2026-09-01T12:00:00Z',
      updatedAt: '2026-09-01T12:00:00Z',
    }
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([budget])),
      http.get('*/v1/admin/budgets/KEY/abc/balance', () =>
        HttpResponse.json({
          level: 'KEY',
          subject: 'abc',
          minuteLimitMicros: 0,
          minuteSpentMicros: 0,
          monthLimitMicros: 0,
          monthSpentMicros: 0,
        }),
      ),
    )
    renderApp(<CachePage />, { adminSession: true })
    await user.click(await screen.findByRole('button', { name: /inspect budget abc/i }))
    expect(await screen.findByText(/no traffic yet/i)).toBeInTheDocument()
  })

  it('warns that tier stats may lag after a purge', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([])),
      http.delete('*/v1/admin/cache', () =>
        HttpResponse.json({
          success: true,
          message: 'Global cache purge completed successfully: 7 keys',
          evictedScope: 'ALL',
          evictedKeys: 7,
        }),
      ),
    )
    renderApp(<CachePage />, { adminSession: true })
    await user.click(await screen.findByRole('button', { name: /^purge/i }))
    await user.click(await screen.findByRole('button', { name: /purge now/i }))
    await waitFor(() => {
      expect(screen.getByText(/cache purged \(ALL, 7 keys\)/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/tiers may lag|may lag/i)).toBeInTheDocument()
  })

  it('renders admin unavailable on cache stealth denials', async () => {
    server.use(
      http.get('*/v1/admin/cache/stats', () => new HttpResponse('x', { status: 404 })),
      http.get('*/v1/admin/cache/tiers', () => new HttpResponse('x', { status: 404 })),
      http.get('*/v1/admin/budgets', () => new HttpResponse('x', { status: 404 })),
    )
    renderApp(<CachePage />, { adminSession: true })
    const faces = await screen.findAllByText(/admin unavailable/i)
    expect(faces.length).toBeGreaterThanOrEqual(3)
  })

  it('reports tier probe failures honestly', async () => {
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([])),
      http.get('*/v1/admin/cache/tiers', () => new HttpResponse('x', { status: 503 })),
    )
    renderApp(<CachePage />, { adminSession: true })
    expect(await screen.findByText(/tier telemetry/i)).toBeInTheDocument()
    expect(await screen.findByText(/temporarily unavailable/i)).toBeInTheDocument()
  })

  it('reports budget save and delete failures honestly', async () => {
    const user = userEvent.setup()
    const budget = {
      id: 'b1',
      level: 'KEY',
      subjectId: 'abc',
      minuteMicros: 1000,
      monthMicros: 10000,
      webhookUrl: null,
      createdAt: '2026-09-01T12:00:00Z',
      updatedAt: '2026-09-01T12:00:00Z',
    }
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([budget])),
      http.get('*/v1/admin/budgets/KEY/abc/balance', () =>
        HttpResponse.json({
          level: 'KEY',
          subject: 'abc',
          minuteLimitMicros: 1000,
          minuteSpentMicros: 100,
          monthLimitMicros: 10000,
          monthSpentMicros: 500,
        }),
      ),
      http.put('*/v1/admin/budgets/:id', () => new HttpResponse('x', { status: 409 })),
      http.delete('*/v1/admin/budgets/:id', () => new HttpResponse('x', { status: 404 })),
    )
    renderApp(<CachePage />, { adminSession: true })
    await user.click(await screen.findByRole('button', { name: /inspect budget abc/i }))
    await user.click(screen.getByRole('button', { name: /save caps/i }))
    expect(await screen.findByText(/conflict/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /^delete budget$/i }))
    await user.click(screen.getByRole('button', { name: /^yes, delete$/i }))
    expect(await screen.findByText(/not found/i)).toBeInTheDocument()
  })

  it('reports non-conflict save failures without a reload loop', async () => {
    const user = userEvent.setup()
    const budget = {
      id: 'b1',
      level: 'KEY',
      subjectId: 'abc',
      minuteMicros: 1000,
      monthMicros: 10000,
      webhookUrl: null,
      createdAt: '2026-09-01T12:00:00Z',
      updatedAt: '2026-09-01T12:00:00Z',
    }
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([budget])),
      http.get('*/v1/admin/budgets/KEY/abc/balance', () =>
        HttpResponse.json({
          level: 'KEY',
          subject: 'abc',
          minuteLimitMicros: 1000,
          minuteSpentMicros: 100,
          monthLimitMicros: 10000,
          monthSpentMicros: 500,
        }),
      ),
      http.put('*/v1/admin/budgets/:id', () => new HttpResponse('x', { status: 500 })),
    )
    renderApp(<CachePage />, { adminSession: true })
    await user.click(await screen.findByRole('button', { name: /inspect budget abc/i }))
    await user.click(screen.getByRole('button', { name: /save caps/i }))
    expect(await screen.findByText(/request failed \(http 500\)/i)).toBeInTheDocument()
  })

  it('sends the webhook on save and reloads latest caps on 409', async () => {
    const user = userEvent.setup()
    let putBody: unknown = null
    let puts = 0
    const budget = {
      id: 'b1',
      level: 'KEY',
      subjectId: 'abc',
      minuteMicros: 1000,
      monthMicros: 10000,
      webhookUrl: null,
      createdAt: '2026-09-01T12:00:00Z',
      updatedAt: '2026-09-01T12:00:00Z',
    }
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () =>
        HttpResponse.json([
          puts === 0 ? budget : { ...budget, minuteMicros: 2000, monthMicros: 20000 },
        ]),
      ),
      http.get('*/v1/admin/budgets/KEY/abc/balance', () =>
        HttpResponse.json({
          level: 'KEY',
          subject: 'abc',
          minuteLimitMicros: 1000,
          minuteSpentMicros: 100,
          monthLimitMicros: 10000,
          monthSpentMicros: 500,
        }),
      ),
      http.put('*/v1/admin/budgets/:id', async ({ request }) => {
        puts += 1
        putBody = await request.json()
        if (puts === 1) return new HttpResponse('x', { status: 409 })
        return HttpResponse.json({ ...budget, minuteMicros: 2000, monthMicros: 20000 })
      }),
    )
    renderApp(<CachePage />, { adminSession: true })
    await user.click(await screen.findByRole('button', { name: /inspect budget abc/i }))
    await user.type(screen.getByLabelText(/webhook url/i), 'https://ops.example.com/hook')
    await user.click(screen.getByRole('button', { name: /save caps/i }))
    expect(await screen.findByText(/changed concurrently/i)).toBeInTheDocument()
    expect(putBody).toMatchObject({ webhookUrl: 'https://ops.example.com/hook' })
    await waitFor(() => {
      expect(screen.getByLabelText(/minute cap/i)).toHaveValue('2000')
    })
  })

  it('opens the create dialog with focus in the form', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([])),
    )
    renderApp(<CachePage />, { adminSession: true })
    const shortcuts = await screen.findAllByRole('button', { name: /create budget/i })
    const shortcut = shortcuts[0]
    if (shortcut === undefined) throw new Error('Empty-state shortcut not found')
    await user.click(shortcut)
    await screen.findByRole('dialog', { name: /create budget/i })
    await waitFor(() => {
      expect(screen.getByLabelText(/^level$/i)).toHaveFocus()
    })
  })

  it('closes the create dialog on Escape', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([])),
    )
    renderApp(<CachePage />, { adminSession: true })
    const escapeTriggers = await screen.findAllByRole('button', { name: /create budget/i })
    const escapeTrigger = escapeTriggers[0]
    if (escapeTrigger === undefined) throw new Error('Create budget trigger not found')
    await user.click(escapeTrigger)
    await screen.findByRole('dialog', { name: /create budget/i })
    await user.keyboard('{Escape}')
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: /create budget/i })).not.toBeInTheDocument()
    })
  })

  it('purges the cache and announces the outcome', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([])),
      http.delete('*/v1/admin/cache', () =>
        HttpResponse.json({
          success: true,
          message: 'Global cache purge completed successfully: 7 keys',
          evictedScope: 'ALL',
          evictedKeys: 7,
        }),
      ),
    )
    renderApp(<CachePage />, { adminSession: true })
    await user.click(await screen.findByRole('button', { name: /^purge/i }))
    await user.click(await screen.findByRole('button', { name: /purge now/i }))
    await waitFor(() => {
      expect(screen.getByText(/cache purged \(ALL, 7 keys\)/i)).toBeInTheDocument()
    })
  })

  it('scopes purges to an owner when given', async () => {
    const user = userEvent.setup()
    let scope: string | null = null
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([])),
      http.delete('*/v1/admin/cache', ({ request }) => {
        scope = new URL(request.url).searchParams.get('ownerId')
        return HttpResponse.json({
          success: true,
          message: `Purged cache for tenant ${scope ?? 'ALL'}: 3 keys`,
          evictedScope: scope ?? 'ALL',
          evictedKeys: 3,
        })
      }),
    )
    renderApp(<CachePage />, { adminSession: true })
    await user.click(await screen.findByRole('button', { name: /^purge/i }))
    await user.type(screen.getByLabelText(/owner scope/i), 'tenant-corp')
    await user.click(screen.getByRole('button', { name: /purge now/i }))
    await waitFor(() => {
      expect(screen.getByText(/cache purged \(tenant-corp, 3 keys\)/i)).toBeInTheDocument()
    })
    expect(scope).toBe('tenant-corp')
  })

  it('cancels purge from the confirm step', async () => {
    const user = userEvent.setup()
    let calls = 0
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([])),
      http.delete('*/v1/admin/cache', () => {
        calls += 1
        return HttpResponse.json({
          success: true,
          message: 'Global cache purge completed successfully: 7 keys',
          evictedScope: 'ALL',
          evictedKeys: 7,
        })
      }),
    )
    renderApp(<CachePage />, { adminSession: true })
    await user.click(await screen.findByRole('button', { name: /^purge/i }))
    await user.click(screen.getByRole('button', { name: /cancel/i }))
    expect(calls).toBe(0)
  })

  it('creates a budget with an optional webhook', async () => {
    const user = userEvent.setup()
    let body = ''
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([])),
      http.post('*/v1/admin/budgets', async ({ request }) => {
        body = await request.text()
        return HttpResponse.json({
          id: 'b3',
          level: 'KEY',
          subjectId: 'k1',
          minuteMicros: 5,
          monthMicros: 50,
          webhookUrl: 'https://ops.example.com/hook',
          createdAt: '2026-09-18T00:00:00Z',
          updatedAt: '2026-09-18T00:00:00Z',
        })
      }),
    )
    renderApp(<CachePage />, { adminSession: true })
    const webhookTriggers = await screen.findAllByRole('button', { name: /create budget/i })
    const webhookTrigger = webhookTriggers[0]
    if (webhookTrigger === undefined) throw new Error('Create budget trigger not found')
    await user.click(webhookTrigger)
    await user.type(await screen.findByLabelText(/^level$/i), 'KEY')
    await user.type(screen.getByLabelText(/^subject$/i), 'k1')
    await user.type(screen.getByLabelText(/minute cap/i), '5')
    await user.type(screen.getByLabelText(/month cap/i), '50')
    await user.type(screen.getByLabelText(/webhook/i), 'https://ops.example.com/hook')
    const createButtons = screen.getAllByRole('button', { name: /create budget/i })
    const submitButton = createButtons[createButtons.length - 1]
    if (submitButton === undefined) throw new Error('Create budget submit not found')
    await user.click(submitButton)
    await waitFor(() => {
      expect(screen.queryByText(/budget creation failed/i)).not.toBeInTheDocument()
    })
    expect(body).toContain('https://ops.example.com/hook')
  })

  it('creates a budget', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([])),
      http.post('*/v1/admin/budgets', () =>
        HttpResponse.json({
          id: 'b2',
          level: 'TEAM',
          subjectId: 'tenant-corp',
          minuteMicros: 10,
          monthMicros: 100,
          webhookUrl: null,
          createdAt: '2026-09-18T00:00:00Z',
          updatedAt: '2026-09-18T00:00:00Z',
        }),
      ),
    )
    renderApp(<CachePage />, { adminSession: true })
    const basicTriggers = await screen.findAllByRole('button', { name: /create budget/i })
    const basicTrigger = basicTriggers[0]
    if (basicTrigger === undefined) throw new Error('Create budget trigger not found')
    await user.click(basicTrigger)
    await user.type(await screen.findByLabelText(/^level$/i), 'TEAM')
    await user.type(screen.getByLabelText(/^subject$/i), 'tenant-corp')
    await user.type(screen.getByLabelText(/minute cap/i), '10')
    await user.type(screen.getByLabelText(/month cap/i), '100')
    const createButtons = screen.getAllByRole('button', { name: /create budget/i })
    const submitButton = createButtons[createButtons.length - 1]
    if (submitButton === undefined) throw new Error('Create budget submit not found')
    await user.click(submitButton)
    await waitFor(() => {
      expect(screen.queryByText(/budget creation failed/i)).not.toBeInTheDocument()
    })
  })

  it('reports purge failures honestly', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([])),
      http.delete('*/v1/admin/cache', () => new HttpResponse('x', { status: 500 })),
    )
    renderApp(<CachePage />, { adminSession: true })
    await user.click(await screen.findByRole('button', { name: /^purge/i }))
    await user.click(await screen.findByRole('button', { name: /purge now/i }))
    await waitFor(() => {
      expect(screen.getByText(/HTTP 500/)).toBeInTheDocument()
    })
  })

  it('reports budget creation failures honestly', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([])),
      http.post('*/v1/admin/budgets', () => new HttpResponse('x', { status: 400 })),
    )
    renderApp(<CachePage />, { adminSession: true })
    const failureTriggers = await screen.findAllByRole('button', { name: /create budget/i })
    const failureTrigger = failureTriggers[0]
    if (failureTrigger === undefined) throw new Error('Create budget trigger not found')
    await user.click(failureTrigger)
    await user.type(await screen.findByLabelText(/^level$/i), 'TEAM')
    await user.type(screen.getByLabelText(/^subject$/i), 'bad')
    await user.type(screen.getByLabelText(/minute cap/i), '5')
    await user.type(screen.getByLabelText(/month cap/i), '50')
    const createButtons = screen.getAllByRole('button', { name: /create budget/i })
    const submitButton = createButtons[createButtons.length - 1]
    if (submitButton === undefined) throw new Error('Create budget submit not found')
    await user.click(submitButton)
    await waitFor(() => {
      expect(screen.getByText(/invalid request/i)).toBeInTheDocument()
    })
  })

  it('reports stats fetch failures as alerts', async () => {
    server.use(
      http.get('*/v1/admin/cache/stats', () => new HttpResponse('x', { status: 500 })),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([])),
    )
    renderApp(<CachePage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/HTTP 500/)
    })
  })

  it('reports budgets fetch failures as alerts', async () => {
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => new HttpResponse('x', { status: 503 })),
    )
    renderApp(<CachePage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/temporarily unavailable/i)
    })
  })

  it('shows cache off and tier flags honestly', async () => {
    server.use(
      http.get('*/v1/admin/cache/stats', () =>
        HttpResponse.json({
          ...STATS,
          enabled: false,
          defaultScope: 'GLOBAL',
          l1RedisEnabled: false,
          l2SemanticEnabled: false,
          polarityGuardEnabled: false,
          entityGuardEnabled: false,
        }),
      ),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([])),
    )
    renderApp(<CachePage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText('Off')).toBeInTheDocument()
    })
    expect(screen.getByText(/scope GLOBAL/i)).toBeInTheDocument()
    expect(screen.getByText('Exact off · Semantic off')).toBeInTheDocument()
    expect(screen.getByText(/polarity off \/ entity off/i)).toBeInTheDocument()
  })

  it('shows redis as off and zero caps honestly', async () => {
    server.use(
      http.get('*/v1/admin/cache/stats', () =>
        HttpResponse.json({ ...STATS, l1RedisEnabled: false }),
      ),
      http.get('*/v1/admin/budgets', () =>
        HttpResponse.json([
          {
            id: 'b0',
            level: 'TEAM',
            subjectId: 'uncapped',
            minuteMicros: 0,
            monthMicros: 0,
            webhookUrl: null,
            createdAt: '2026-09-18T00:00:00Z',
            updatedAt: '2026-09-18T00:00:00Z',
          },
        ]),
      ),
    )
    renderApp(<CachePage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText('Cache on · exact off · semantic on')).toBeInTheDocument()
    })
    expect(screen.getByText('uncapped')).toBeInTheDocument()
    expect(screen.getAllByText('no cap').length).toBeGreaterThanOrEqual(2)
  })

  it('validates the budget form before submitting', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([])),
    )
    renderApp(<CachePage />, { adminSession: true })
    const validationTriggers = await screen.findAllByRole('button', {
      name: /create budget/i,
    })
    const validationTrigger = validationTriggers[0]
    if (validationTrigger === undefined) throw new Error('Create budget trigger not found')
    await user.click(validationTrigger)
    await screen.findByRole('dialog', { name: /create budget/i })
    const validationButtons = screen.getAllByRole('button', { name: /create budget/i })
    const validationSubmit = validationButtons[validationButtons.length - 1]
    if (validationSubmit === undefined) throw new Error('Create budget submit not found')
    await user.click(validationSubmit)
    await waitFor(() => {
      expect(screen.getByText(/level is required/i)).toBeInTheDocument()
    })
    expect(screen.getAllByText(/expected number, received NaN/i)).toHaveLength(2)
    expect(screen.getByLabelText(/minute cap/i)).toHaveAttribute('min', '0')
    expect(screen.getByLabelText(/month cap/i)).toHaveAttribute('min', '0')
    // FE-30: errors are linked, not just broadcast.
    expect(screen.getByLabelText(/^level$/i)).toHaveAttribute(
      'aria-describedby',
      'budget-level-error',
    )
    expect(screen.getByLabelText(/minute cap/i)).toHaveAttribute(
      'aria-describedby',
      'budget-minute-error',
    )
    expect(screen.getByLabelText(/webhook url/i)).not.toHaveAttribute('aria-describedby')
  })

  it('rejects non-https and local webhooks before submitting', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([])),
    )
    renderApp(<CachePage />, { adminSession: true })
    const triggers = await screen.findAllByRole('button', { name: /create budget/i })
    const trigger = triggers[0]
    if (trigger === undefined) throw new Error('Create budget trigger not found')
    await user.click(trigger)
    await screen.findByRole('dialog', { name: /create budget/i })
    await user.type(screen.getByLabelText(/^level$/i), 'KEY')
    await user.type(screen.getByLabelText(/subject/i), 'tenant-corp')
    for (const bad of ['file:///etc/passwd', 'http://127.0.0.1:9000/hook', 'not-a-url']) {
      await user.clear(screen.getByLabelText(/webhook url/i))
      await user.type(screen.getByLabelText(/webhook url/i), bad)
      const submits = screen.getAllByRole('button', { name: /create budget/i })
      const submit = submits[submits.length - 1]
      if (submit === undefined) throw new Error('Create budget submit not found')
      await user.click(submit)
      await waitFor(() => {
        expect(screen.getByText(/non-local host/i)).toBeInTheDocument()
      })
    }
  })

  it('copies visible budgets as a markdown table', async () => {
    const user = userEvent.setup()
    const writes: string[] = []
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {
        writeText: (s: string): Promise<void> => {
          writes.push(s)
          return Promise.resolve()
        },
      },
    })
    try {
      server.use(
        http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
        http.get('*/v1/admin/budgets', () =>
          HttpResponse.json([
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
          ]),
        ),
      )
      renderApp(<CachePage />, { adminSession: true })
      await user.click(await screen.findByRole('button', { name: /copy markdown/i }))
      expect(writes.length).toBe(1)
      const first = writes.at(0)
      expect(first).toBeDefined()
      if (first !== undefined) {
        expect(first.startsWith('# Spend budgets')).toBe(true)
        expect(first).toContain('| tenant-corp | TEAM | 100 | 5000 |')
      }
    } finally {
      Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined })
    }
  })

  it('reports budget copy failure inline', async () => {
    const user = userEvent.setup()
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: (): Promise<void> => Promise.reject(new Error('denied')) },
    })
    try {
      server.use(
        http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
        http.get('*/v1/admin/budgets', () =>
          HttpResponse.json([
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
          ]),
        ),
      )
      renderApp(<CachePage />, { adminSession: true })
      await user.click(await screen.findByRole('button', { name: /copy markdown/i }))
      expect(await screen.findByText(/copy failed. select the text manually/i)).toBeInTheDocument()
    } finally {
      Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined })
    }
  })

  it('reports budget copy absence inline', async () => {
    const user = userEvent.setup()
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined })
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () =>
        HttpResponse.json([
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
        ]),
      ),
    )
    renderApp(<CachePage />, { adminSession: true })
    await user.click(await screen.findByRole('button', { name: /copy markdown/i }))
    expect(screen.getByText(/copy unavailable in this browser/i)).toBeInTheDocument()
  })

  it('refreshes stats on demand', async () => {
    const user = userEvent.setup()
    let calls = 0
    server.use(
      http.get('*/v1/admin/cache/stats', () => {
        calls += 1
        return HttpResponse.json(STATS)
      }),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([])),
    )
    renderApp(<CachePage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText('On')).toBeInTheDocument()
    })
    expect(calls).toBe(1)
    await user.click(screen.getByRole('button', { name: /refresh/i }))
    await waitFor(() => {
      expect(calls).toBeGreaterThan(1)
    })
  })
})
