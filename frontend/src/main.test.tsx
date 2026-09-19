import { act, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { router } from './app/router.js'
import { server } from './test/setup.js'
import { useAuthStore } from './shared/auth/store.js'

const LEGS: [string, string][] = [
  ['/playground', 'Playground'],
  ['/embeddings', 'Embeddings'],
  ['/mcp', 'MCP'],
  ['/observability', 'Observability'],
]

const AUTH_LEGS: [string, RegExp][] = [
  ['/login', /log in/i],
  ['/redeem', /redeem invite/i],
]

const ADMIN_LEGS = ['/circuits', '/keys', '/ledger', '/cache', '/approvals']

describe('application boot', () => {
  it('renders the shell from #root', async () => {
    server.use(http.post('*/v1/auth/refresh', () => new HttpResponse('x', { status: 401 })))
    document.body.innerHTML = '<div id="root"></div>'
    await import('./main.js')
    await waitFor(() => {
      expect(screen.getByText('CacheRelay')).toBeInTheDocument()
    })
  })

  it('loads every route chunk without crashing', async () => {
    server.use(
      http.get('*/actuator/health', () => HttpResponse.json({ status: 'UP' })),
      http.get(
        '*/actuator/prometheus',
        () => new HttpResponse('', { headers: { 'Content-Type': 'text/plain' } }),
      ),
      http.post('*/v1/auth/refresh', () => new HttpResponse('x', { status: 401 })),
    )
    for (const [path, label] of LEGS) {
      await act(async () => {
        await router.navigate(path)
      })
      await waitFor(() => {
        expect(screen.getByRole('link', { current: 'page' })).toHaveTextContent(label)
      })
    }
    for (const [path, heading] of AUTH_LEGS) {
      await act(async () => {
        await router.navigate(path)
      })
      await waitFor(() => {
        expect(screen.getByRole('heading', { name: heading })).toBeInTheDocument()
      })
    }
    for (const path of ADMIN_LEGS) {
      useAuthStore
        .getState()
        .setSession({ accessToken: 'test-admin-jwt', admin: true, username: 'test-admin' })
      await act(async () => {
        await router.navigate(path)
      })
      await waitFor(() => {
        expect(screen.getByRole('link', { current: 'page' })).toBeInTheDocument()
      })
      useAuthStore.getState().clear()
    }
  })
})
