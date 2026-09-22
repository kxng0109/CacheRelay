import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { CircuitsPage } from './page.js'

const STATE = [
  {
    provider: 'openai',
    state: 'CLOSED',
    failures: 0,
    cooldownMsRemaining: 0,
    halfOpenProbe: false,
  },
]

/**
 * Seeds the admin key then renders the circuits board.
 */
function renderBoard() {
  return renderApp(<CircuitsPage />, { adminSession: true })
}

describe('CircuitsPage', () => {
  it('renders provider states with icon and text', async () => {
    server.use(http.get('*/v1/admin/circuits', () => HttpResponse.json(STATE)))
    renderBoard()
    await waitFor(() => {
      expect(screen.getByText('openai')).toBeInTheDocument()
    })
    expect(screen.getByText('● Closed')).toBeInTheDocument()
    expect(screen.getByText(/1 providers · 1 flowing/i)).toBeInTheDocument()
  })

  it('states the traffic consequence in the inspector', async () => {
    const user = userEvent.setup()
    server.use(http.get('*/v1/admin/circuits', () => HttpResponse.json(STATE)))
    renderBoard()
    const table = await screen.findByRole('table')
    await user.click(within(table).getByText('openai'))
    expect(screen.getByText(/requests flow normally/i)).toBeInTheDocument()
  })

  it('closes the inspector from its close button', async () => {
    const user = userEvent.setup()
    server.use(http.get('*/v1/admin/circuits', () => HttpResponse.json(STATE)))
    renderBoard()
    const table = await screen.findByRole('table')
    await user.click(within(table).getByText('openai'))
    const inspector = await screen.findByRole('complementary', { name: /circuit inspector/i })
    await user.click(within(inspector).getByRole('button', { name: /close inspector/i }))
    expect(screen.getByRole('complementary', { name: /circuit inspector/i })).toHaveTextContent(
      /select a row to inspect/i,
    )
  })

  it('resets a circuit and announces the outcome', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/circuits', () => HttpResponse.json(STATE)),
      http.post('*/v1/admin/circuits/*/reset', () =>
        HttpResponse.json({ provider: 'openai', state: 'CLOSED' }),
      ),
    )
    renderBoard()
    await user.click(await screen.findByRole('button', { name: /reset circuit/i }))
    await waitFor(() => {
      expect(screen.getByText('openai: CLOSED')).toBeInTheDocument()
    })
  })

  it('surfaces gateway errors without leaking internals', async () => {
    server.use(
      http.get(
        '*/v1/admin/circuits',
        () => new HttpResponse(JSON.stringify({ title: 'x' }), { status: 503 }),
      ),
    )
    renderBoard()
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/temporarily unavailable/i)
    })
  })

  it('reports reset failures honestly', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/circuits', () => HttpResponse.json(STATE)),
      http.post('*/v1/admin/circuits/*/reset', () => new HttpResponse('x', { status: 500 })),
    )
    renderBoard()
    await user.click(await screen.findByRole('button', { name: /reset circuit/i }))
    await waitFor(() => {
      expect(screen.getByText(/HTTP 500/i)).toBeInTheDocument()
    })
  })

  it('names an empty board honestly', async () => {
    server.use(http.get('*/v1/admin/circuits', () => HttpResponse.json([])))
    renderBoard()
    await waitFor(() => {
      expect(screen.getByText(/no providers reported/i)).toBeInTheDocument()
    })
  })

  it('renders every known state plus unknown future states', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/circuits', () =>
        HttpResponse.json([
          {
            provider: 'a',
            state: 'OPEN',
            failures: 3,
            cooldownMsRemaining: 15000,
            halfOpenProbe: false,
          },
          {
            provider: 'b',
            state: 'HALF_OPEN',
            failures: 0,
            cooldownMsRemaining: 0,
            halfOpenProbe: true,
          },
          {
            provider: 'c',
            state: 'DRAINING',
            failures: 0,
            cooldownMsRemaining: 0,
            halfOpenProbe: false,
          },
        ]),
      ),
    )
    renderBoard()
    await waitFor(() => {
      expect(screen.getByText('■ Open')).toBeInTheDocument()
    })
    expect(screen.getByText('▲ Half-open')).toBeInTheDocument()
    expect(screen.getByText('? Unknown')).toBeInTheDocument()
    expect(screen.getByText('15000')).toBeInTheDocument()
    await user.click(within(screen.getByRole('table')).getByText('b'))
    expect(screen.getByRole('complementary', { name: /circuit inspector/i })).toHaveTextContent(
      'in flight',
    )
  })

  it('renders future states under their own name', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/circuits', () =>
        HttpResponse.json([
          {
            provider: 'c',
            state: 'DRAINING',
            failures: 0,
            cooldownMsRemaining: 0,
            halfOpenProbe: false,
          },
        ]),
      ),
    )
    renderBoard()
    await waitFor(() => {
      expect(screen.getByText('DRAINING')).toBeInTheDocument()
    })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByText('c'))
    const inspector = screen.getByRole('complementary', { name: /circuit inspector/i })
    expect(inspector).toHaveTextContent(/unknown state/i)
    expect(inspector).toHaveTextContent(/treat traffic as suspect/i)
  })

  it('selects an unknown-state row from the keyboard', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/circuits', () =>
        HttpResponse.json([
          {
            provider: 'c',
            state: 'DRAINING',
            failures: 0,
            cooldownMsRemaining: 0,
            halfOpenProbe: false,
          },
        ]),
      ),
    )
    renderBoard()
    const table = await screen.findByRole('table')
    within(table).getByText('c').closest('tr')?.focus()
    await user.keyboard('{Enter}')
    expect(screen.getByRole('complementary', { name: /circuit inspector/i })).toHaveTextContent(
      /unknown state/i,
    )
  })

  it('shows the same-origin base when unconfigured', async () => {
    vi.stubEnv('VITE_API_BASE_URL', '')
    server.use(http.get('*/v1/admin/circuits', () => HttpResponse.json([])))
    renderBoard()
    await waitFor(() => {
      expect(screen.getByText(/same-origin/i)).toBeInTheDocument()
    })
  })

  it('refreshes the board on demand', async () => {
    let calls = 0
    server.use(
      http.get('*/v1/admin/circuits', () => {
        calls += 1
        return HttpResponse.json(STATE)
      }),
    )
    renderBoard()
    await waitFor(() => {
      expect(screen.getByText('openai')).toBeInTheDocument()
    })
    expect(calls).toBe(1)
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /refresh/i }))
    await waitFor(() => {
      expect(calls).toBeGreaterThan(1)
    })
  })

  it('names a filter with zero matches honestly', async () => {
    const user = userEvent.setup()
    server.use(http.get('*/v1/admin/circuits', () => HttpResponse.json(STATE)))
    renderBoard()
    await waitFor(() => {
      expect(screen.getByText('openai')).toBeInTheDocument()
    })
    await user.type(screen.getByLabelText(/filter circuits/i), 'zzz-no-such-provider')
    expect(screen.getByText(/no circuits match this filter/i)).toBeInTheDocument()
  })

  it('deselects a row on second click', async () => {
    const user = userEvent.setup()
    server.use(http.get('*/v1/admin/circuits', () => HttpResponse.json(STATE)))
    renderBoard()
    const table = await screen.findByRole('table')
    await user.click(within(table).getByText('openai'))
    expect(screen.getByRole('complementary', { name: /circuit inspector/i })).toHaveTextContent(
      'Flowing',
    )
    await user.click(within(table).getByText('openai'))
    expect(screen.getByRole('complementary', { name: /circuit inspector/i })).toHaveTextContent(
      /select a row to inspect/i,
    )
  })

  it('filters rows by provider text', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/circuits', () =>
        HttpResponse.json([
          {
            provider: 'openai',
            state: 'CLOSED',
            failures: 0,
            cooldownMsRemaining: 0,
            halfOpenProbe: false,
          },
          {
            provider: 'anthropic',
            state: 'OPEN',
            failures: 0,
            cooldownMsRemaining: 0,
            halfOpenProbe: false,
          },
        ]),
      ),
    )
    renderBoard()
    await waitFor(() => {
      expect(screen.getByText('anthropic')).toBeInTheDocument()
    })
    await user.type(screen.getByLabelText(/filter circuits/i), 'openai')
    expect(screen.queryByText('anthropic')).not.toBeInTheDocument()
    expect(screen.getByText('openai')).toBeInTheDocument()
  })

  it('segments rows by state dimension', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/circuits', () =>
        HttpResponse.json([
          {
            provider: 'openai',
            state: 'CLOSED',
            failures: 0,
            cooldownMsRemaining: 0,
            halfOpenProbe: false,
          },
          {
            provider: 'anthropic',
            state: 'OPEN',
            failures: 0,
            cooldownMsRemaining: 0,
            halfOpenProbe: false,
          },
        ]),
      ),
    )
    renderBoard()
    await waitFor(() => {
      expect(screen.getByText('anthropic')).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: /^open$/i }))
    expect(screen.queryByText('openai')).not.toBeInTheDocument()
    expect(screen.getByText('anthropic')).toBeInTheDocument()
  })

  it('inspects a row and resets from the inspector', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/circuits', () => HttpResponse.json(STATE)),
      http.post('*/v1/admin/circuits/*/reset', () =>
        HttpResponse.json({ provider: 'openai', state: 'CLOSED' }),
      ),
    )
    renderBoard()
    const table = await screen.findByRole('table')
    await user.click(within(table).getByText('openai'))
    expect(screen.getByRole('complementary', { name: /circuit inspector/i })).toHaveTextContent(
      'Flowing',
    )
    const inspector = screen.getByRole('complementary', { name: /circuit inspector/i })
    await user.click(within(inspector).getByRole('button', { name: /reset circuit/i }))
    await waitFor(() => {
      expect(screen.getByText('openai: CLOSED')).toBeInTheDocument()
    })
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})
