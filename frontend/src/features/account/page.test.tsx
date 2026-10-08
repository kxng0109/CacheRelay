import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { AccountPage } from './page.js'

const OWNED = [
  {
    keyId: 'a'.repeat(64),
    name: 'dev',
    allowedModels: [],
    enabled: true,
  },
]

describe('AccountPage', () => {
  it('sets the default key with confirmation copy', async () => {
    const user = userEvent.setup()
    let put = 0
    server.use(
      http.get('*/v1/me/keys', () => HttpResponse.json(OWNED)),
      http.put('*/v1/me/keys/default', () => {
        put += 1
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderApp(<AccountPage />, { nonAdminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /use dev as default/i }))
    await waitFor(() => {
      expect(screen.getByText(/default key updated/i)).toBeInTheDocument()
    })
    expect(put).toBe(1)
  })

  it('revokes owned keys only after explicit confirmation', async () => {
    const user = userEvent.setup()
    let posts = 0
    server.use(
      http.get('*/v1/me/keys', () => HttpResponse.json(OWNED)),
      http.post('*/v1/me/keys/:id/revoke', () => {
        posts += 1
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderApp(<AccountPage />, { nonAdminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /^revoke dev$/i }))
    expect(posts).toBe(0)
    await user.click(within(table).getByRole('button', { name: /yes, revoke/i }))
    await waitFor(() => {
      expect(screen.getByText(/cannot be undone/i)).toBeInTheDocument()
    })
    expect(posts).toBe(1)
  })

  it('names foreign keys without an oracle', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/me/keys', () => HttpResponse.json(OWNED)),
      http.put('*/v1/me/keys/default', () => new HttpResponse('x', { status: 404 })),
    )
    renderApp(<AccountPage />, { nonAdminSession: true })
    const table = await screen.findByRole('table')
    await user.click(within(table).getByRole('button', { name: /use dev as default/i }))
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/key not found/i)
    })
  })

  it('shows the empty state before any key exists', async () => {
    server.use(http.get('*/v1/me/keys', () => HttpResponse.json([])))
    renderApp(<AccountPage />, { nonAdminSession: true })
    await waitFor(() => {
      expect(screen.getByText(/no owned keys/i)).toBeInTheDocument()
    })
  })

  it('renders admin unavailable on stealth denials', async () => {
    server.use(http.get('*/v1/me/keys', () => new HttpResponse('x', { status: 404 })))
    renderApp(<AccountPage />, { nonAdminSession: true })
    expect(await screen.findByText(/admin unavailable/i)).toBeInTheDocument()
  })

  it('names models and disabled states on owned rows', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('*/v1/me/keys', () =>
        HttpResponse.json([
          {
            keyId: 'b'.repeat(64),
            name: 'quiet',
            allowedModels: ['gpt-4o-mini'],
            enabled: false,
          },
        ]),
      ),
      http.put('*/v1/me/keys/default', () => new HttpResponse(null, { status: 204 })),
      http.post('*/v1/me/keys/:id/revoke', () => new HttpResponse(null, { status: 204 })),
    )
    renderApp(<AccountPage />, { nonAdminSession: true })
    const table = await screen.findByRole('table')
    expect(table).toHaveTextContent('gpt-4o-mini')
    expect(table).toHaveTextContent('disabled')
    await user.click(within(table).getByRole('button', { name: /use quiet as default/i }))
    await waitFor(() => {
      expect(screen.getByText(/default key updated/i)).toBeInTheDocument()
    })
  })
})
