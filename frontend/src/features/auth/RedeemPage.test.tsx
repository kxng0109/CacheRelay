import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { useAuthStore } from '../../shared/auth/store.js'
import { RedeemPage } from './RedeemPage.js'

describe('RedeemPage', () => {
  it('prefills the token from the link and logs in on 201', async () => {
    const user = userEvent.setup()
    server.use(
      http.post('*/v1/auth/redeem', () =>
        HttpResponse.json(
          { accessToken: 'jwt-2', expiresInSeconds: 300, admin: true },
          { status: 201 },
        ),
      ),
    )
    renderApp(<RedeemPage />, { route: '/redeem?token=abc123' })
    expect(screen.getByLabelText(/invite token/i)).toHaveValue('abc123')
    await user.type(screen.getByLabelText(/username/i), 'operator')
    await user.type(screen.getByLabelText(/^password \(12/i), 'correct horse battery staple')
    await user.type(screen.getByLabelText(/confirm password/i), 'correct horse battery staple')
    await user.click(screen.getByRole('button', { name: /create account/i }))
    await waitFor(() => {
      expect(useAuthStore.getState().session?.username).toBe('operator')
    })
  })

  it('answers unknown and consumed invites identically', async () => {
    const user = userEvent.setup()
    server.use(http.post('*/v1/auth/redeem', () => new HttpResponse('x', { status: 410 })))
    renderApp(<RedeemPage />, { route: '/redeem?token=old' })
    await user.type(screen.getByLabelText(/username/i), 'operator')
    await user.type(screen.getByLabelText(/^password \(12/i), 'correct horse battery staple')
    await user.type(screen.getByLabelText(/confirm password/i), 'correct horse battery staple')
    await user.click(screen.getByRole('button', { name: /create account/i }))
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/invalid or already used/i)
    })
    expect(useAuthStore.getState().session).toBeNull()
  })

  it('blocks mismatched passwords client-side', async () => {
    const user = userEvent.setup()
    renderApp(<RedeemPage />)
    await user.type(screen.getByLabelText(/invite token/i), 'tok')
    await user.type(screen.getByLabelText(/username/i), 'operator')
    await user.type(screen.getByLabelText(/^password \(12/i), 'correct horse battery staple')
    await user.type(screen.getByLabelText(/confirm password/i), 'different password here')
    await user.click(screen.getByRole('button', { name: /create account/i }))
    await waitFor(() => {
      expect(screen.getByText(/passwords do not match/i)).toBeInTheDocument()
    })
  })

  it('validates every field before submitting', async () => {
    const user = userEvent.setup()
    renderApp(<RedeemPage />)
    await user.click(screen.getByRole('button', { name: /create account/i }))
    await waitFor(() => {
      expect(screen.getByText(/invite token is required/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/username must be 3-255 characters/i)).toBeInTheDocument()
    expect(screen.getByText(/password must be 12-255 characters/i)).toBeInTheDocument()
  })

  it('posts split-origin to the configured backend base', async () => {
    const user = userEvent.setup()
    vi.stubEnv('VITE_API_BASE_URL', 'http://localhost:8080')
    let seenUrl = ''
    server.use(
      http.post('*/v1/auth/redeem', ({ request }) => {
        seenUrl = request.url
        return HttpResponse.json(
          { accessToken: 'jwt-3', expiresInSeconds: 300, admin: true },
          { status: 201 },
        )
      }),
    )
    try {
      renderApp(<RedeemPage />, { route: '/redeem?token=split1' })
      await user.type(screen.getByLabelText(/username/i), 'operator')
      await user.type(screen.getByLabelText(/^password \(12/i), 'correct horse battery staple')
      await user.type(screen.getByLabelText(/confirm password/i), 'correct horse battery staple')
      await user.click(screen.getByRole('button', { name: /create account/i }))
      await waitFor(() => {
        expect(useAuthStore.getState().session?.username).toBe('operator')
      })
      expect(seenUrl.startsWith('http://localhost:8080/v1/auth/redeem')).toBe(true)
    } finally {
      vi.unstubAllEnvs()
    }
  })
})
