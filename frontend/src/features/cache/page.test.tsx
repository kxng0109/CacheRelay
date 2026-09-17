import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { CachePage } from './page.js'

const STATS = {
  l0Size: 10,
  l0Capacity: 100,
  redisConfigured: true,
  exactEntries: 5,
  semanticVectors: 2,
}

describe('CachePage', () => {
  it('requires the admin key first', () => {
    renderApp(<CachePage />)
    expect(screen.getByText(/unlock the admin key/i)).toBeInTheDocument()
  })

  it('renders tier stats and budgets with progress', async () => {
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () =>
        HttpResponse.json({
          budgets: [
            { id: 'b1', name: 'team', limitMicros: 100, spentMicros: 25, remainingMicros: 75 },
          ],
        }),
      ),
    )
    renderApp(<CachePage />, { adminKey: 'master-test' })
    await waitFor(() => {
      expect(screen.getByText('team')).toBeInTheDocument()
    })
    expect(screen.getByText('10/100')).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: /team spend/i })).toHaveAttribute(
      'aria-valuenow',
      '25',
    )
  })

  it('shows the empty budget state', async () => {
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json({ budgets: [] })),
    )
    renderApp(<CachePage />, { adminKey: 'master-test' })
    await waitFor(() => {
      expect(screen.getByText(/no budgets yet/i)).toBeInTheDocument()
    })
  })

  it('purges the cache and announces the outcome', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json({ budgets: [] })),
      http.post('*/v1/admin/cache/purge', () => HttpResponse.json({ purged: true })),
    )
    renderApp(<CachePage />, { adminKey: 'master-test' })
    await user.click(await screen.findByRole('button', { name: /purge cache/i }))
    await waitFor(() => {
      expect(screen.getByText(/cache purged/i)).toBeInTheDocument()
    })
  })

  it('creates a budget', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json({ budgets: [] })),
      http.post('*/v1/admin/budgets', () =>
        HttpResponse.json({
          id: 'b2',
          name: 'new',
          limitMicros: 10,
          spentMicros: 0,
          remainingMicros: 10,
        }),
      ),
    )
    renderApp(<CachePage />, { adminKey: 'master-test' })
    await user.type(await screen.findByLabelText(/^name$/i), 'new')
    await user.type(screen.getByLabelText(/micro-dollars/i), '10')
    await user.click(screen.getByRole('button', { name: /create budget/i }))
    await waitFor(() => {
      expect(screen.queryByText(/budget creation failed/i)).not.toBeInTheDocument()
    })
  })

  it('reports purge failures honestly', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json({ budgets: [] })),
      http.post('*/v1/admin/cache/purge', () => new HttpResponse('x', { status: 500 })),
    )
    renderApp(<CachePage />, { adminKey: 'master-test' })
    await user.click(await screen.findByRole('button', { name: /purge cache/i }))
    await waitFor(() => {
      expect(screen.getByText(/HTTP 500/)).toBeInTheDocument()
    })
  })

  it('reports budget creation failures honestly', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json({ budgets: [] })),
      http.post('*/v1/admin/budgets', () => new HttpResponse('x', { status: 400 })),
    )
    renderApp(<CachePage />, { adminKey: 'master-test' })
    await user.type(await screen.findByLabelText(/^name$/i), 'bad')
    await user.type(screen.getByLabelText(/micro-dollars/i), '5')
    await user.click(screen.getByRole('button', { name: /create budget/i }))
    await waitFor(() => {
      expect(screen.getByText(/invalid request/i)).toBeInTheDocument()
    })
  })

  it('reports stats fetch failures as alerts', async () => {
    server.use(
      http.get('*/v1/admin/cache/stats', () => new HttpResponse('x', { status: 500 })),
      http.get('*/v1/admin/budgets', () => HttpResponse.json({ budgets: [] })),
    )
    renderApp(<CachePage />, { adminKey: 'master-test' })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/HTTP 500/)
    })
  })

  it('reports budgets fetch failures as alerts', async () => {
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => new HttpResponse('x', { status: 503 })),
    )
    renderApp(<CachePage />, { adminKey: 'master-test' })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/temporarily unavailable/i)
    })
  })

  it('shows redis as off and zero-limit budgets honestly', async () => {
    server.use(
      http.get('*/v1/admin/cache/stats', () =>
        HttpResponse.json({ ...STATS, redisConfigured: false }),
      ),
      http.get('*/v1/admin/budgets', () =>
        HttpResponse.json({
          budgets: [
            { id: 'b0', name: 'uncapped', limitMicros: 0, spentMicros: 0, remainingMicros: 0 },
          ],
        }),
      ),
    )
    renderApp(<CachePage />, { adminKey: 'master-test' })
    await waitFor(() => {
      expect(screen.getByText('■ Off')).toBeInTheDocument()
    })
    expect(screen.getByRole('progressbar', { name: /uncapped spend/i })).toHaveAttribute(
      'aria-valuenow',
      '0',
    )
  })

  it('validates the budget form before submitting', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/cache/stats', () => HttpResponse.json(STATS)),
      http.get('*/v1/admin/budgets', () => HttpResponse.json({ budgets: [] })),
    )
    renderApp(<CachePage />, { adminKey: 'master-test' })
    await user.click(await screen.findByRole('button', { name: /create budget/i }))
    await waitFor(() => {
      expect(screen.getByText(/name is required/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/expected number, received NaN/i)).toBeInTheDocument()
  })
})
