import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { afterEach, describe, expect, it } from 'vitest'
import { useAuthStore } from '../shared/auth/store.js'
import { TestSessionSeed } from './router.js'

/** Renders the landed pathname for assertions. */
function LocationProbe(): React.JSX.Element {
  const location = useLocation()
  return <p data-testid="landed">{location.pathname}</p>
}

afterEach(() => {
  useAuthStore.getState().clear()
})

/**
 * Renders the seed route in isolation (no shell, no guards): the unit
 * proves seeding + forwarding; the Playwright gate proves the guarded
 * arrival with real chrome.
 *
 * @param entry - Seed URL including role and `next`.
 */
function renderSeed(entry: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path="__test/session/:role" element={<TestSessionSeed />} />
          <Route path="*" element={<LocationProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('TestSessionSeed', () => {
  it('seeds an admin session and forwards to the target', async () => {
    renderSeed('/__test/session/admin?next=/circuits')
    await waitFor(() => {
      expect(screen.getByTestId('landed')).toHaveTextContent('/circuits')
    })
    expect(useAuthStore.getState().session).toMatchObject({ admin: true, username: 'playwright' })
  })

  it('seeds a non-admin session for the user role', async () => {
    renderSeed('/__test/session/user?next=/')
    await waitFor(() => {
      expect(screen.getByTestId('landed')).toHaveTextContent('/')
    })
    expect(useAuthStore.getState().session).toMatchObject({ admin: false })
  })

  it('falls back home for cross-origin and protocol-relative next values', async () => {
    const { unmount } = renderSeed('/__test/session/user?next=https://evil.example/')
    await waitFor(() => {
      expect(screen.getByTestId('landed')).toHaveTextContent('/')
    })
    unmount()
    renderSeed('/__test/session/user?next=//evil.example/')
    await waitFor(() => {
      expect(screen.getByTestId('landed')).toHaveTextContent('/')
    })
  })

  it('falls back home without a next value', async () => {
    renderSeed('/__test/session/user')
    await waitFor(() => {
      expect(screen.getByTestId('landed')).toHaveTextContent('/')
    })
  })
})
