import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { KeysPage } from './page.js'

const KEYS = {
  keys: [
    {
      id: 'k1',
      name: 'ci-key',
      rpmLimit: 60,
      dailyQuota: 1000,
      models: ['gpt-4o-mini'],
      createdAt: '2026-09-17T00:00:00Z',
    },
  ],
}

describe('KeysPage', () => {
  it('requires the admin key first', () => {
    renderApp(<KeysPage />)
    expect(screen.getByText(/unlock the admin key/i)).toBeInTheDocument()
  })

  it('lists keys with limits', async () => {
    server.use(http.get('*/v1/admin/keys', () => HttpResponse.json(KEYS)))
    renderApp(<KeysPage />, { adminKey: 'master-test' })
    await waitFor(() => {
      expect(screen.getByText('ci-key')).toBeInTheDocument()
    })
    expect(screen.getByText('gpt-4o-mini')).toBeInTheDocument()
  })

  it('shows the empty state before any key exists', async () => {
    server.use(http.get('*/v1/admin/keys', () => HttpResponse.json({ keys: [] })))
    renderApp(<KeysPage />, { adminKey: 'master-test' })
    await waitFor(() => {
      expect(screen.getByText(/no keys yet/i)).toBeInTheDocument()
    })
  })

  it('creates a key and exposes plaintext exactly once', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/keys', () => HttpResponse.json({ keys: [] })),
      http.post('*/v1/admin/keys', () =>
        HttpResponse.json({ ...KEYS.keys[0], plaintext: 'gw-secret-once' }),
      ),
    )
    renderApp(<KeysPage />, { adminKey: 'master-test' })
    await user.type(screen.getByLabelText(/^name$/i), 'ci-key')
    await user.type(screen.getByLabelText(/models/i), 'gpt-4o-mini')
    await user.type(screen.getByLabelText(/requests per minute/i), '60')
    await user.type(screen.getByLabelText(/daily quota/i), '1000')
    await user.click(screen.getByRole('button', { name: /create key/i }))
    await waitFor(() => {
      expect(screen.getByText('gw-secret-once')).toBeInTheDocument()
    })
    expect(screen.getByText(/never shown again/i)).toBeInTheDocument()
  })

  it('validates the form before submitting', async () => {
    const user = userEvent.setup()
    server.use(http.get('*/v1/admin/keys', () => HttpResponse.json({ keys: [] })))
    renderApp(<KeysPage />, { adminKey: 'master-test' })
    await user.click(screen.getByRole('button', { name: /create key/i }))
    await waitFor(() => {
      expect(screen.getByText(/name is required/i)).toBeInTheDocument()
    })
  })

  it('deletes a key and refreshes the list', async () => {
    const user = userEvent.setup()
    let listed = 1
    server.use(
      http.get('*/v1/admin/keys', () => HttpResponse.json({ keys: listed === 0 ? [] : KEYS.keys })),
      http.delete('*/v1/admin/keys/:id', () => {
        listed = 0
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderApp(<KeysPage />, { adminKey: 'master-test' })
    await user.click(await screen.findByRole('button', { name: /delete/i }))
    await waitFor(() => {
      expect(screen.getByText(/no keys yet/i)).toBeInTheDocument()
    })
  })

  it('reports creation failures without exposing the form data', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/admin/keys', () => HttpResponse.json({ keys: [] })),
      http.post('*/v1/admin/keys', () => new HttpResponse('x', { status: 400 })),
    )
    renderApp(<KeysPage />, { adminKey: 'master-test' })
    await user.type(screen.getByLabelText(/^name$/i), 'bad')
    await user.type(screen.getByLabelText(/models/i), 'm')
    await user.type(screen.getByLabelText(/requests per minute/i), '1')
    await user.type(screen.getByLabelText(/daily quota/i), '1')
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
    renderApp(<KeysPage />, { adminKey: 'master-test' })
    await user.click(await screen.findByRole('button', { name: /delete/i }))
    await waitFor(() => {
      expect(screen.getByText(/not found/i)).toBeInTheDocument()
    })
  })
})
