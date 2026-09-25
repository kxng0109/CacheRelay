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

/**
 * Mocks the provider inventory. Names left out of `unconfigured` report a
 * key set, mirroring the backend `keyConfigured` boolean.
 */
function providers(names: string[], unconfigured: string[] = []) {
  return http.get('*/v1/admin/providers', () =>
    HttpResponse.json(
      names.map((name) => ({
        name,
        type: 'openai-compatible',
        baseUrl: null,
        keyConfigured: !unconfigured.includes(name),
        connectTimeoutSeconds: 5,
        requestTimeoutSeconds: 60,
        embeddingSingleAsString: false,
        circuitState: 'CLOSED',
        aliasReferences: 0,
        validationStatus: 'UNVERIFIED',
      })),
    ),
  )
}

describe('CircuitsPage', () => {
  it('renders provider states with icon and text', async () => {
    server.use(
      http.get('*/v1/admin/circuits', () => HttpResponse.json(STATE)),
      providers(['openai']),
    )
    renderBoard()
    await waitFor(() => {
      expect(screen.getByText('openai')).toBeInTheDocument()
    })
    expect(screen.getByText('● Closed')).toBeInTheDocument()
    expect(screen.getByText(/1 of 1 shown · 1 flowing/i)).toBeInTheDocument()
    // FE-22: narrow viewports scroll the table region, never the page.
    expect(screen.getByRole('table').closest('.overflow-x-auto')).not.toBeNull()
  })

  it('renders inherited state names as unknown instead of mislabeling', async () => {
    server.use(
      http.get('*/v1/admin/circuits', () =>
        HttpResponse.json([
          {
            provider: 'weird',
            state: '__proto__',
            failures: 0,
            cooldownMsRemaining: 0,
            halfOpenProbe: false,
          },
        ]),
      ),
      providers(['weird']),
    )
    renderBoard()
    await waitFor(() => {
      expect(screen.getByText('? __proto__')).toBeInTheDocument()
    })
  })

  it('states the traffic consequence in the inspector', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/circuits', () => HttpResponse.json(STATE)),
      providers(['openai']),
    )
    renderBoard()
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect circuit openai/i }))
    expect(screen.getByText(/requests flow normally/i)).toBeInTheDocument()
  })

  it('closes the inspector from its close button', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/circuits', () => HttpResponse.json(STATE)),
      providers(['openai']),
    )
    renderBoard()
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect circuit openai/i }))
    const inspector = await screen.findByRole('dialog', { name: /circuit inspector/i })
    await user.click(within(inspector).getByRole('button', { name: /close inspector/i }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: /circuit inspector/i })).not.toBeInTheDocument()
    })
    expect(screen.getByText(/select a row to inspect a circuit/i)).toBeInTheDocument()
  })

  it('resets a circuit from the inspector and announces the outcome', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/circuits', () => HttpResponse.json(STATE)),
      http.post('*/v1/admin/circuits/*/reset', () =>
        HttpResponse.json({ provider: 'openai', state: 'CLOSED' }),
      ),
      providers(['openai']),
    )
    renderBoard()
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect circuit openai/i }))
    const inspector = await screen.findByRole('dialog', { name: /circuit inspector/i })
    await user.click(within(inspector).getByRole('button', { name: /reset circuit/i }))
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
      providers([]),
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
      providers(['openai']),
    )
    renderBoard()
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect circuit openai/i }))
    const inspector = await screen.findByRole('dialog', { name: /circuit inspector/i })
    await user.click(within(inspector).getByRole('button', { name: /reset circuit/i }))
    await waitFor(() => {
      expect(screen.getByText(/HTTP 500/i)).toBeInTheDocument()
    })
  })

  it('names an empty board honestly', async () => {
    server.use(
      http.get('*/v1/admin/circuits', () => HttpResponse.json([])),
      providers([]),
    )
    renderBoard()
    await waitFor(() => {
      expect(screen.getByText(/no providers reported/i)).toBeInTheDocument()
    })
  })

  it('renders every known state plus other future states', async () => {
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
    expect(screen.getByText('? DRAINING')).toBeInTheDocument()
    expect(screen.getByText('15000')).toBeInTheDocument()
    await user.click(
      within(screen.getByRole('table')).getByRole('button', { name: /inspect circuit b/i }),
    )
    expect(screen.getByRole('dialog', { name: /circuit inspector/i })).toHaveTextContent(
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
      providers(['c']),
    )
    renderBoard()
    await waitFor(() => {
      expect(screen.getByText('DRAINING')).toBeInTheDocument()
    })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect circuit c/i }))
    const inspector = screen.getByRole('dialog', { name: /circuit inspector/i })
    expect(inspector).toHaveTextContent(/not mapped in this ui/i)
    expect(inspector).toHaveTextContent(/treat traffic as suspect/i)
  })

  it('selects a row from the keyboard through its inspect control', async () => {
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
      providers(['c']),
    )
    renderBoard()
    const table = await screen.findByRole('table')
    within(table)
      .getByRole('button', { name: /inspect circuit c/i })
      .focus()
    await user.keyboard('{Enter}')
    expect(screen.getByRole('dialog', { name: /circuit inspector/i })).toHaveTextContent(
      /not mapped in this ui/i,
    )
  })

  it('shows the same-origin base when unconfigured', async () => {
    vi.stubEnv('VITE_API_BASE_URL', '')
    server.use(
      http.get('*/v1/admin/circuits', () => HttpResponse.json([])),
      providers([]),
    )
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
      providers(['openai']),
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
    server.use(
      http.get('*/v1/admin/circuits', () => HttpResponse.json(STATE)),
      providers(['openai']),
    )
    renderBoard()
    await waitFor(() => {
      expect(screen.getByText('openai')).toBeInTheDocument()
    })
    await user.type(screen.getByLabelText(/filter circuits/i), 'zzz-no-such-provider')
    expect(screen.getByText(/no circuits match this filter/i)).toBeInTheDocument()
  })

  it('deselects a row on second click', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/circuits', () => HttpResponse.json(STATE)),
      providers(['openai']),
    )
    renderBoard()
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect circuit openai/i }))
    expect(screen.getByRole('dialog', { name: /circuit inspector/i })).toHaveTextContent('Flowing')
    await user.click(within(table).getByRole('button', { name: /inspect circuit openai/i }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: /circuit inspector/i })).not.toBeInTheDocument()
    })
    expect(screen.getByText(/select a row to inspect a circuit/i)).toBeInTheDocument()
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
      providers(['openai', 'anthropic']),
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
      providers(['openai', 'anthropic']),
    )
    renderBoard()
    await waitFor(() => {
      expect(screen.getByText('anthropic')).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: /^open$/i }))
    expect(screen.queryByText('openai')).not.toBeInTheDocument()
    expect(screen.getByText('anthropic')).toBeInTheDocument()
  })

  it('shows configured providers by default and reveals all on demand', async () => {
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
            provider: 'quiet',
            state: 'CLOSED',
            failures: 0,
            cooldownMsRemaining: 0,
            halfOpenProbe: false,
          },
        ]),
      ),
      providers(['openai', 'quiet'], ['quiet']),
    )
    renderBoard()
    await waitFor(() => {
      expect(screen.getByText('openai')).toBeInTheDocument()
    })
    expect(screen.getByText(/1 of 2 shown/i)).toBeInTheDocument()
    expect(screen.queryByText('quiet')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'All' }))
    expect(screen.getByText('quiet')).toBeInTheDocument()
    expect(screen.getByText(/2 of 2 shown/i)).toBeInTheDocument()
  })

  it('says honestly when no provider has keys', async () => {
    server.use(
      http.get('*/v1/admin/circuits', () => HttpResponse.json(STATE)),
      providers(['openai'], ['openai']),
    )
    renderBoard()
    await waitFor(() => {
      expect(screen.getByText(/no configured providers yet/i)).toBeInTheDocument()
    })
  })

  it('inspects a row and resets from the inspector', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/circuits', () => HttpResponse.json(STATE)),
      http.post('*/v1/admin/circuits/*/reset', () =>
        HttpResponse.json({ provider: 'openai', state: 'CLOSED' }),
      ),
      providers(['openai']),
    )
    renderBoard()
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect circuit openai/i }))
    expect(screen.getByRole('dialog', { name: /circuit inspector/i })).toHaveTextContent('Flowing')
    const inspector = screen.getByRole('dialog', { name: /circuit inspector/i })
    await user.click(within(inspector).getByRole('button', { name: /reset circuit/i }))
    await waitFor(() => {
      expect(screen.getByText('openai: CLOSED')).toBeInTheDocument()
    })
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})
