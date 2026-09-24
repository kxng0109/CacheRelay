import { act, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import { router } from './app/router.js'
import { server } from './test/setup.js'
import { useAuthStore } from './shared/auth/store.js'

const { mockInit } = vi.hoisted(() => {
  const mockChart = {
    setOption: vi.fn(),
    setTheme: vi.fn(),
    resize: vi.fn(),
    dispose: vi.fn(),
  }
  return { mockInit: vi.fn(() => mockChart) }
})

// Routing tests assert navigation and guards, not canvas pixels: the real
// ECharts instance needs a layout engine jsdom does not provide, so the
// chart module is stubbed here exactly like the chart's own test files do.
vi.mock('./shared/echarts/setup.js', () => ({
  echarts: { init: mockInit },
}))

const PUBLIC_LEGS: [string, string][] = [
  ['/playground', 'Playground'],
  ['/embeddings', 'Embeddings'],
]

const SESSION_LEGS: [string, string][] = [
  ['/mcp', 'MCP'],
  ['/observability', 'Observability'],
  ['/usage', 'Usage'],
  ['/teams', 'Teams'],
]

const AUTH_LEGS: [string, RegExp][] = [
  ['/login', /log in/i],
  ['/redeem', /redeem invite/i],
]

const ADMIN_LEGS = [
  '/circuits',
  '/keys',
  '/ledger',
  '/ledger/user/123e4567-e89b-12d3-a456-426614174000',
  '/cache',
  '/approvals',
]

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
    for (const [path, label] of PUBLIC_LEGS) {
      await act(async () => {
        await router.navigate(path)
      })
      await waitFor(() => {
        expect(screen.getByRole('link', { current: 'page' })).toHaveTextContent(label)
      })
    }
    useAuthStore
      .getState()
      .setSession({ accessToken: 'test-user-jwt', admin: false, username: 'test-user' })
    for (const [path, label] of SESSION_LEGS) {
      await act(async () => {
        await router.navigate(path)
      })
      await waitFor(() => {
        expect(screen.getByRole('link', { current: 'page' })).toHaveTextContent(label)
      })
    }
    useAuthStore.getState().clear()
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

  it('sends guests from / to login and keeps overview for sessions', async () => {
    server.use(
      http.get('*/actuator/health', () => HttpResponse.json({ status: 'UP' })),
      http.get(
        '*/actuator/prometheus',
        () => new HttpResponse('', { headers: { 'Content-Type': 'text/plain' } }),
      ),
      http.post('*/v1/auth/refresh', () => new HttpResponse('x', { status: 401 })),
    )
    useAuthStore.getState().clear()
    await act(async () => {
      await router.navigate('/')
    })
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /log in/i })).toBeInTheDocument()
    })
    useAuthStore
      .getState()
      .setSession({ accessToken: 'test-user-jwt', admin: false, username: 'test-user' })
    await act(async () => {
      await router.navigate('/')
    })
    await waitFor(() => {
      expect(screen.getByRole('link', { current: 'page' })).toHaveTextContent('Overview')
    })
    useAuthStore.getState().clear()
  })
})
