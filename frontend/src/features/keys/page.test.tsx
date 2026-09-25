import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { KeysPage } from './page.js'

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

describe('KeysPage', () => {
  it('mounts the board without a session (router guards access)', () => {
    renderApp(<KeysPage />)
    expect(screen.getByText(/loading keys/i)).toBeInTheDocument()
  })

  it('lists keys with limits', async () => {
    server.use(http.get('*/v1/admin/keys', () => HttpResponse.json(KEYS)))
    renderApp(<KeysPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText('ci-key')).toBeInTheDocument()
    })
    expect(screen.getByText('gpt-4o-mini')).toBeInTheDocument()
    expect(screen.getByText('100K')).toBeInTheDocument()
  })

  it('degrades a drifted key payload to the empty state without crashing', async () => {
    server.use(http.get('*/v1/admin/keys', () => HttpResponse.json({ keys: {} })))
    renderApp(<KeysPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText('No keys yet')).toBeInTheDocument()
    })
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('renders unlimited for zero limits', async () => {
    server.use(
      http.get('*/v1/admin/keys', () =>
        HttpResponse.json([{ ...KEYS[0], rpmLimit: 0, allowedModels: [] }]),
      ),
    )
    renderApp(<KeysPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getAllByText('unlimited')[0]).toBeInTheDocument()
    })
    expect(screen.getByText('all')).toBeInTheDocument()
  })

  it('rejects a non-UUID owner account before submitting', async () => {
    const user = userEvent.setup()
    let posts = 0
    server.use(
      http.get('*/v1/admin/keys', () => HttpResponse.json([])),
      http.post('*/v1/admin/keys', () => {
        posts += 1
        return HttpResponse.json({}, { status: 201 })
      }),
    )
    renderApp(<KeysPage />, { adminSession: true })
    const triggers = await screen.findAllByRole('button', { name: /new key/i })
    const trigger = triggers[0]
    if (trigger === undefined) throw new Error('New key trigger not found')
    await user.click(trigger)
    await user.type(screen.getByLabelText(/^owner$/i), 'tenant-corp')
    await user.type(screen.getByLabelText(/owner account uuid/i), 'not-a-uuid')
    await user.type(screen.getByLabelText(/^name$/i), 'bad-owner')
    await user.click(screen.getByRole('button', { name: /^create key$/i }))
    expect(await screen.findByText(/valid uuid/i)).toBeInTheDocument()
    expect(posts).toBe(0)
  })

  it('surfaces an unknown-owner 400 at the owner field', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/keys', () => HttpResponse.json([])),
      http.post(
        '*/v1/admin/keys',
        () =>
          new HttpResponse(
            JSON.stringify({ status: 400, error: 'Bad Request', message: 'unknown owner account' }),
            { status: 400 },
          ),
      ),
    )
    renderApp(<KeysPage />, { adminSession: true })
    const triggers = await screen.findAllByRole('button', { name: /new key/i })
    const trigger = triggers[0]
    if (trigger === undefined) throw new Error('New key trigger not found')
    await user.click(trigger)
    await user.type(screen.getByLabelText(/^owner$/i), 'tenant-corp')
    await user.type(
      screen.getByLabelText(/owner account uuid/i),
      '123e4567-e89b-12d3-a456-426614174000',
    )
    await user.type(screen.getByLabelText(/^name$/i), 'orphan')
    await user.type(screen.getByLabelText(/requests per minute/i), '60')
    await user.type(screen.getByLabelText(/tokens per minute/i), '100000')
    await user.click(screen.getByRole('button', { name: /^create key$/i }))
    await waitFor(() => {
      expect(screen.getAllByText(/unknown owner account/i).length).toBeGreaterThanOrEqual(2)
    })
  })

  it('revokes terminally with no undo path', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/keys', () => HttpResponse.json(KEYS)),
      http.post('*/v1/admin/keys/:id/revoke', () =>
        HttpResponse.json({ ...KEYS[0], enabled: false }),
      ),
    )
    renderApp(<KeysPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect key ci-key/i }))
    const inspector = await screen.findByRole('dialog', { name: /key inspector/i })
    await user.click(within(inspector).getByRole('button', { name: /revoke key/i }))
    await user.click(within(inspector).getByRole('button', { name: /yes, revoke/i }))
    await waitFor(() => {
      expect(screen.getByText(/cannot be undone/i)).toBeInTheDocument()
    })
    expect(screen.queryByRole('button', { name: /enable key/i })).not.toBeInTheDocument()
  })

  it('toggles enabled state reversibly', async () => {
    const user = userEvent.setup()
    let enabled = true
    server.use(
      http.get('*/v1/admin/keys', () => HttpResponse.json([{ ...KEYS[0], enabled }])),
      http.patch('*/v1/admin/keys/:id', () => {
        enabled = false
        return HttpResponse.json({ ...KEYS[0], enabled: false })
      }),
    )
    renderApp(<KeysPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect key ci-key/i }))
    const inspector = await screen.findByRole('dialog', { name: /key inspector/i })
    await user.click(within(inspector).getByRole('button', { name: /disable key/i }))
    await waitFor(() => {
      expect(screen.getAllByText(/disabled/i).length).toBeGreaterThanOrEqual(2)
    })
  })

  it('selects a disabled key row from the keyboard', async () => {
    const user = userEvent.setup()
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: vi.fn(() => Promise.resolve()) },
      configurable: true,
    })
    server.use(
      http.get('*/v1/admin/keys', () =>
        HttpResponse.json([
          {
            ...KEYS[0],
            keyId: 'k9',
            name: 'old-key',
            enabled: false,
            rpmLimit: 0,
            tpmLimit: 0,
          },
        ]),
      ),
    )
    renderApp(<KeysPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    within(table)
      .getByRole('button', { name: /inspect key old-key/i })
      .focus()
    await user.keyboard('{Enter}')
    const inspector = await screen.findByRole('dialog', { name: /key inspector/i })
    expect(inspector).toHaveTextContent('disabled')
    expect(inspector).toHaveTextContent('all')
    expect(inspector).toHaveTextContent('unlimited / unlimited')
    await user.click(within(inspector).getByRole('button', { name: /close inspector/i }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(screen.getByText(/select a row to inspect a key/i)).toBeInTheDocument()
  })

  it('shows the empty state before any key exists', async () => {
    server.use(http.get('*/v1/admin/keys', () => HttpResponse.json([])))
    renderApp(<KeysPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText(/no keys yet/i)).toBeInTheDocument()
    })
  })

  it('creates a key and exposes plaintext exactly once', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/keys', () => HttpResponse.json([])),
      http.post('*/v1/admin/keys', () => HttpResponse.json({ ...KEYS[0], key: 'gw-secret-once' })),
    )
    renderApp(<KeysPage />, { adminSession: true })
    const dialogTriggers = await screen.findAllByRole('button', { name: /^new key$/i })
    const dialogTrigger = dialogTriggers[0]
    if (dialogTrigger === undefined) throw new Error('New key trigger not found')
    await user.click(dialogTrigger)
    expect(await screen.findByLabelText(/^owner$/i)).toBeInTheDocument()
    await user.type(screen.getByLabelText(/^owner$/i), 'tenant-corp')
    await user.type(
      screen.getByLabelText(/owner account uuid/i),
      '123e4567-e89b-12d3-a456-426614174000',
    )
    await user.type(screen.getByLabelText(/^name$/i), 'ci-key')
    await user.type(screen.getByLabelText(/models/i), 'gpt-4o-mini')
    await user.type(screen.getByLabelText(/requests per minute/i), '60')
    await user.type(screen.getByLabelText(/tokens per minute/i), '100000')
    await user.click(screen.getByRole('button', { name: /create key/i }))
    await waitFor(() => {
      expect(screen.getByText('gw-secret-once')).toBeInTheDocument()
    })
    expect(screen.getByText(/never shown again/i)).toBeInTheDocument()
  })

  it('validates the form before submitting', async () => {
    const user = userEvent.setup()
    server.use(http.get('*/v1/admin/keys', () => HttpResponse.json([])))
    renderApp(<KeysPage />, { adminSession: true })
    const dialogTriggers = await screen.findAllByRole('button', { name: /^new key$/i })
    const dialogTrigger = dialogTriggers[0]
    if (dialogTrigger === undefined) throw new Error('New key trigger not found')
    await user.click(dialogTrigger)
    await user.click(await screen.findByRole('button', { name: /create key/i }))
    await waitFor(() => {
      expect(screen.getByText(/name is required/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/owner is required/i)).toBeInTheDocument()
    expect(screen.getByText(/must be a valid uuid/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/requests per minute/i)).toHaveAttribute('min', '0')
    expect(screen.getByLabelText(/tokens per minute/i)).toHaveAttribute('min', '0')
  })

  it('deletes a key and refreshes the list', async () => {
    const user = userEvent.setup()
    let listed = 1
    server.use(
      http.get('*/v1/admin/keys', () => HttpResponse.json(listed === 0 ? [] : KEYS)),
      http.delete('*/v1/admin/keys/:id', () => {
        listed = 0
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderApp(<KeysPage />, { adminSession: true })
    await user.click(await screen.findByRole('button', { name: /^delete$/i }))
    await user.click(await screen.findByRole('button', { name: /^yes$/i }))
    await waitFor(() => {
      expect(screen.getByText(/no keys yet/i)).toBeInTheDocument()
    })
  })

  it('clears selection when the selected row is deleted', async () => {
    const user = userEvent.setup()
    let listed = 1
    server.use(
      http.get('*/v1/admin/keys', () => HttpResponse.json(listed === 0 ? [] : KEYS)),
      http.delete('*/v1/admin/keys/:id', () => {
        listed = 0
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderApp(<KeysPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect key ci-key/i }))
    expect(screen.getByRole('dialog', { name: /key inspector/i })).toBeInTheDocument()
    await user.click(within(table).getByRole('button', { name: /^delete$/i }))
    await user.click(await screen.findByRole('button', { name: /^yes$/i }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: /key inspector/i })).not.toBeInTheDocument()
    })
  })

  it('cancels deletion from the confirm step', async () => {
    const user = userEvent.setup()
    server.use(http.get('*/v1/admin/keys', () => HttpResponse.json(KEYS)))
    renderApp(<KeysPage />, { adminSession: true })
    await user.click(await screen.findByRole('button', { name: /^delete$/i }))
    await user.click(await screen.findByRole('button', { name: /^no$/i }))
    expect(screen.getByText('ci-key')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^yes$/i })).not.toBeInTheDocument()
  })

  it('reports creation failures without exposing the form data', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/keys', () => HttpResponse.json([])),
      http.post('*/v1/admin/keys', () => new HttpResponse('x', { status: 400 })),
    )
    renderApp(<KeysPage />, { adminSession: true })
    const dialogTriggers = await screen.findAllByRole('button', { name: /^new key$/i })
    const dialogTrigger = dialogTriggers[0]
    if (dialogTrigger === undefined) throw new Error('New key trigger not found')
    await user.click(dialogTrigger)
    await user.type(await screen.findByLabelText(/^owner$/i), 'tenant-corp')
    await user.type(
      screen.getByLabelText(/owner account uuid/i),
      '123e4567-e89b-12d3-a456-426614174000',
    )
    await user.type(screen.getByLabelText(/^name$/i), 'bad')
    await user.type(screen.getByLabelText(/requests per minute/i), '1')
    await user.type(screen.getByLabelText(/tokens per minute/i), '1')
    await user.click(screen.getByRole('button', { name: /create key/i }))
    await waitFor(() => {
      expect(screen.getByText(/invalid request/i)).toBeInTheDocument()
    })
  })

  it('reports deletion failures honestly', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/keys', () => HttpResponse.json(KEYS)),
      http.delete('*/v1/admin/keys/:id', () => new HttpResponse('x', { status: 404 })),
    )
    renderApp(<KeysPage />, { adminSession: true })
    await user.click(await screen.findByRole('button', { name: /^delete$/i }))
    await user.click(await screen.findByRole('button', { name: /^yes$/i }))
    await waitFor(() => {
      expect(screen.getByText(/not found/i)).toBeInTheDocument()
    })
  })

  it('filters rows by name or model', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/keys', () =>
        HttpResponse.json([
          KEYS[0],
          { ...KEYS[0], keyId: 'k2', name: 'other-key', allowedModels: ['claude'] },
        ]),
      ),
    )
    renderApp(<KeysPage />, { adminSession: true })
    const triggers = await screen.findAllByRole('button', { name: /new key/i })
    const trigger = triggers[0]
    if (trigger === undefined) throw new Error('New key trigger not found')
    await user.click(trigger)
    await user.click(screen.getByRole('button', { name: /cancel/i }))
    await user.type(screen.getByLabelText(/filter keys/i), 'claude')
    expect(screen.queryByText('ci-key')).not.toBeInTheDocument()
    expect(screen.getByText('other-key')).toBeInTheDocument()
  })

  it('inspects a row and copies its key ID', async () => {
    const user = userEvent.setup()
    const writeText = vi.fn(() => Promise.resolve())
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    server.use(http.get('*/v1/admin/keys', () => HttpResponse.json(KEYS)))
    renderApp(<KeysPage />, { adminSession: true })
    await screen.findByRole('table')
    await user.click(screen.getByRole('button', { name: /inspect key ci-key/i }))
    const inspector = await screen.findByRole('dialog', { name: /key inspector/i })
    expect(inspector).toHaveTextContent('tenant-corp')
    expect(inspector).toHaveTextContent('all')
    await user.click(within(inspector).getByRole('button', { name: /copy key id/i }))
    await waitFor(() => {
      expect(writeText).toHaveBeenCalledWith('k1')
    })
    expect(within(inspector).getByRole('button', { name: /copied/i })).toBeInTheDocument()
  })

  it('names a filter with zero matches honestly', async () => {
    const user = userEvent.setup()
    server.use(http.get('*/v1/admin/keys', () => HttpResponse.json(KEYS)))
    renderApp(<KeysPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText('ci-key')).toBeInTheDocument()
    })
    await user.type(screen.getByLabelText(/filter keys/i), 'zzz-no-key')
    expect(screen.getByText(/no keys match this filter/i)).toBeInTheDocument()
  })

  it('deselects a row on second click', async () => {
    const user = userEvent.setup()
    server.use(http.get('*/v1/admin/keys', () => HttpResponse.json(KEYS)))
    renderApp(<KeysPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect key ci-key/i }))
    expect(screen.getByRole('dialog', { name: /key inspector/i })).toHaveTextContent('tenant-corp')
    const tableAgain = await screen.findByRole('table')
    await user.click(within(tableAgain).getByRole('button', { name: /inspect key ci-key/i }))
    expect(screen.queryByRole('dialog', { name: /key inspector/i })).not.toBeInTheDocument()
    expect(screen.getByText(/select a row to inspect a key/i)).toBeInTheDocument()
  })

  it('renders mixed limits and model sets honestly', async () => {
    server.use(
      http.get('*/v1/admin/keys', () =>
        HttpResponse.json([{ ...KEYS[0], tpmLimit: 0, allowedModels: ['a', 'b'] }]),
      ),
    )
    renderApp(<KeysPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    expect(table).toHaveTextContent('unlimited')
    expect(table).toHaveTextContent('a, b')
  })

  it('opens the creation dialog on demand when keys exist', async () => {
    const user = userEvent.setup()
    server.use(http.get('*/v1/admin/keys', () => HttpResponse.json(KEYS)))
    renderApp(<KeysPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByText('ci-key')).toBeInTheDocument()
    })
    expect(screen.queryByLabelText(/^owner$/i)).not.toBeInTheDocument()
    const triggers = await screen.findAllByRole('button', { name: /new key/i })
    const trigger = triggers[0]
    if (trigger === undefined) throw new Error('New key trigger not found')
    await user.click(trigger)
    expect(screen.getByLabelText(/^owner$/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /^cancel$/i }))
    expect(screen.queryByLabelText(/^owner$/i)).not.toBeInTheDocument()
  })

  it('copies the reveal plaintext and dismisses it', async () => {
    const user = userEvent.setup()
    const writeText = vi.fn(() => Promise.resolve())
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    server.use(
      http.get('*/v1/admin/keys', () => HttpResponse.json([])),
      http.post('*/v1/admin/keys', () => HttpResponse.json({ ...KEYS[0], key: 'gw-secret-once' })),
    )
    renderApp(<KeysPage />, { adminSession: true })
    const dialogTriggers = await screen.findAllByRole('button', { name: /^new key$/i })
    const dialogTrigger = dialogTriggers[0]
    if (dialogTrigger === undefined) throw new Error('New key trigger not found')
    await user.click(dialogTrigger)
    await user.type(await screen.findByLabelText(/^owner$/i), 'tenant-corp')
    await user.type(
      screen.getByLabelText(/owner account uuid/i),
      '123e4567-e89b-12d3-a456-426614174000',
    )
    await user.type(screen.getByLabelText(/^name$/i), 'ci-key')
    await user.type(screen.getByLabelText(/requests per minute/i), '60')
    await user.type(screen.getByLabelText(/tokens per minute/i), '60')
    await user.click(screen.getByRole('button', { name: /create key/i }))
    await user.click(await screen.findByRole('button', { name: /^copy$/i }))
    await waitFor(() => {
      expect(writeText).toHaveBeenCalledWith('gw-secret-once')
    })
    expect(screen.getByRole('button', { name: /copied/i })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /dismiss/i }))
    expect(screen.queryByText('gw-secret-once')).not.toBeInTheDocument()
  })

  it('keeps session keys when copies are denied', async () => {
    const user = userEvent.setup()
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: vi.fn(() => Promise.reject(new Error('denied'))) },
      configurable: true,
    })
    server.use(http.get('*/v1/admin/keys', () => HttpResponse.json(KEYS)))
    renderApp(<KeysPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect key ci-key/i }))
    const inspector = await screen.findByRole('dialog', { name: /key inspector/i })
    expect(inspector).toHaveTextContent('tenant-corp')
    expect(inspector).toHaveTextContent('all')
    await user.click(within(inspector).getByRole('button', { name: /copy key id/i }))
    await waitFor(() => {
      expect(within(inspector).getByRole('button', { name: /copy key id/i })).toBeInTheDocument()
    })
  })

  it('reports key-query failures without crashing', async () => {
    server.use(http.get('*/v1/admin/keys', () => new HttpResponse('x', { status: 500 })))
    renderApp(<KeysPage />, { adminSession: true })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/HTTP 500/)
    })
  })

  it('selects rows from the keyboard', async () => {
    const user = userEvent.setup()
    server.use(http.get('*/v1/admin/keys', () => HttpResponse.json(KEYS)))
    renderApp(<KeysPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    within(table)
      .getByRole('button', { name: /inspect key ci-key/i })
      .focus()
    await user.keyboard('{Enter}')
    expect(await screen.findByRole('dialog', { name: /key inspector/i })).toHaveTextContent(
      'tenant-corp',
    )
    within(table)
      .getByRole('button', { name: /inspect key ci-key/i })
      .focus()
    await user.keyboard('{ }')
    await waitFor(() => {
      expect(screen.queryByRole('dialog', { name: /key inspector/i })).not.toBeInTheDocument()
    })
  })

  it('shows non-empty model and provider allow-lists in the inspector', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/keys', () =>
        HttpResponse.json([{ ...KEYS[0], allowedProviders: ['openai'] }]),
      ),
    )
    renderApp(<KeysPage />, { adminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /inspect key ci-key/i }))
    const inspector = await screen.findByRole('dialog', { name: /key inspector/i })
    expect(inspector).toHaveTextContent('gpt-4o-mini')
    expect(inspector).toHaveTextContent('openai')
  })
})
