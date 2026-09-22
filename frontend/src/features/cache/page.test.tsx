import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
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
    expect(screen.getByText('on · TENANT')).toBeInTheDocument()
    expect(screen.getByText('1 MB')).toBeInTheDocument()
    expect(screen.getByText(/l1 on · l2 on/)).toBeInTheDocument()
    expect(screen.getByText('TEAM')).toBeInTheDocument()
    expect(screen.getByText('5000')).toBeInTheDocument()
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

  it('purges the cache and announces the outcome', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([])),
      http.delete('*/v1/admin/cache', () =>
        HttpResponse.json({ success: true, evictedScope: 'ALL' }),
      ),
    )
    renderApp(<CachePage />, { adminSession: true })
    await user.click(await screen.findByRole('button', { name: /^purge/i }))
    await user.click(await screen.findByRole('button', { name: /purge now/i }))
    await waitFor(() => {
      expect(screen.getByText(/cache purged \(ALL\)/i)).toBeInTheDocument()
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
        return HttpResponse.json({ success: true, evictedScope: scope ?? 'ALL' })
      }),
    )
    renderApp(<CachePage />, { adminSession: true })
    await user.click(await screen.findByRole('button', { name: /^purge/i }))
    await user.type(screen.getByLabelText(/owner scope/i), 'tenant-corp')
    await user.click(screen.getByRole('button', { name: /purge now/i }))
    await waitFor(() => {
      expect(screen.getByText(/cache purged \(tenant-corp\)/i)).toBeInTheDocument()
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
        return HttpResponse.json({ success: true, evictedScope: 'ALL' })
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
    await user.type(await screen.findByLabelText(/^level$/i), 'KEY')
    await user.type(screen.getByLabelText(/^subject$/i), 'k1')
    await user.type(screen.getByLabelText(/minute cap/i), '5')
    await user.type(screen.getByLabelText(/month cap/i), '50')
    await user.type(screen.getByLabelText(/webhook/i), 'https://ops.example.com/hook')
    await user.click(screen.getByRole('button', { name: /create budget/i }))
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
    await user.type(await screen.findByLabelText(/^level$/i), 'TEAM')
    await user.type(screen.getByLabelText(/^subject$/i), 'tenant-corp')
    await user.type(screen.getByLabelText(/minute cap/i), '10')
    await user.type(screen.getByLabelText(/month cap/i), '100')
    await user.click(screen.getByRole('button', { name: /create budget/i }))
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
    await user.type(await screen.findByLabelText(/^level$/i), 'TEAM')
    await user.type(screen.getByLabelText(/^subject$/i), 'bad')
    await user.type(screen.getByLabelText(/minute cap/i), '5')
    await user.type(screen.getByLabelText(/month cap/i), '50')
    await user.click(screen.getByRole('button', { name: /create budget/i }))
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
      expect(screen.getByText('off · GLOBAL')).toBeInTheDocument()
    })
    expect(screen.getByText(/l1 off · l2 off/)).toBeInTheDocument()
    expect(screen.getByText(/guards off\/off/i)).toBeInTheDocument()
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
      expect(screen.getByText(/redis off/i)).toBeInTheDocument()
    })
    expect(screen.getByText('uncapped')).toBeInTheDocument()
  })

  it('validates the budget form before submitting', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json([])),
    )
    renderApp(<CachePage />, { adminSession: true })
    await user.click(await screen.findByRole('button', { name: /create budget/i }))
    await waitFor(() => {
      expect(screen.getByText(/level is required/i)).toBeInTheDocument()
    })
    expect(screen.getAllByText(/expected number, received NaN/i)).toHaveLength(2)
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
      expect(screen.getByText('on · TENANT')).toBeInTheDocument()
    })
    expect(calls).toBe(1)
    await user.click(screen.getByRole('button', { name: /refresh/i }))
    await waitFor(() => {
      expect(calls).toBeGreaterThan(1)
    })
  })
})
