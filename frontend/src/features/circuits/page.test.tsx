import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { CircuitsPage } from './page.js'

const STATE = {
  circuits: [{ provider: 'openai', state: 'CLOSED', lastTransitionAt: null }],
}

/**
 * Seeds the admin key then renders the circuits board.
 */
function renderBoard() {
  return renderApp(<CircuitsPage />, { adminKey: 'master-test' })
}

describe('CircuitsPage', () => {
  it('asks for the admin key when locked', () => {
    renderApp(<CircuitsPage />)
    expect(screen.getByLabelText(/master admin key/i)).toBeInTheDocument()
  })

  it('unlocks with a typed key', async () => {
    const user = userEvent.setup()
    server.use(http.get('*/v1/admin/circuits/state', () => HttpResponse.json({ circuits: [] })))
    renderApp(<CircuitsPage />)
    await user.type(screen.getByLabelText(/master admin key/i), 'master-test')
    await user.click(screen.getByRole('button', { name: /unlock circuits/i }))
    await waitFor(() => {
      expect(screen.getByText(/no providers reported/i)).toBeInTheDocument()
    })
  })

  it('renders provider states with icon and text', async () => {
    server.use(http.get('*/v1/admin/circuits/state', () => HttpResponse.json(STATE)))
    renderBoard()
    await waitFor(() => {
      expect(screen.getByText('openai')).toBeInTheDocument()
    })
    expect(screen.getByText(/closed/i)).toBeInTheDocument()
  })

  it('resets a circuit and announces the outcome', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/circuits/state', () => HttpResponse.json(STATE)),
      http.post('*/v1/admin/circuits/reset', () =>
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
        '*/v1/admin/circuits/state',
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
      http.get('*/v1/admin/circuits/state', () => HttpResponse.json(STATE)),
      http.post('*/v1/admin/circuits/reset', () => new HttpResponse('x', { status: 500 })),
    )
    renderBoard()
    await user.click(await screen.findByRole('button', { name: /reset circuit/i }))
    await waitFor(() => {
      expect(screen.getByText(/HTTP 500/i)).toBeInTheDocument()
    })
  })

  it('stays locked on empty submit', async () => {
    const user = userEvent.setup()
    renderApp(<CircuitsPage />)
    await user.click(screen.getByRole('button', { name: /unlock circuits/i }))
    expect(screen.getByLabelText(/master admin key/i)).toBeInTheDocument()
  })

  it('renders every known state plus unknown future states', async () => {
    server.use(
      http.get('*/v1/admin/circuits/state', () =>
        HttpResponse.json({
          circuits: [
            { provider: 'a', state: 'OPEN', lastTransitionAt: '2026-09-17T01:00:00Z' },
            { provider: 'b', state: 'HALF_OPEN', lastTransitionAt: null },
            { provider: 'c', state: 'DRAINING', lastTransitionAt: null },
          ],
        }),
      ),
    )
    renderBoard()
    await waitFor(() => {
      expect(screen.getByText('■ Open')).toBeInTheDocument()
    })
    expect(screen.getByText('▲ Half-open')).toBeInTheDocument()
    expect(screen.getByText('? Unknown')).toBeInTheDocument()
    expect(screen.getByText('2026-09-17T01:00:00Z')).toBeInTheDocument()
  })

  it('shows the same-origin base when unconfigured', async () => {
    vi.stubEnv('VITE_API_BASE_URL', '')
    server.use(http.get('*/v1/admin/circuits/state', () => HttpResponse.json({ circuits: [] })))
    renderBoard()
    await waitFor(() => {
      expect(screen.getByText(/same-origin/i)).toBeInTheDocument()
    })
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})
