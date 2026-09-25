import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import type { UserEvent } from '@testing-library/user-event'
import type { ReactElement } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach } from 'vitest'
import { useAuthStore } from '../shared/auth/store.js'

/**
 * Picks an option from the shared listbox by visible labels.
 *
 * @param user - Initialized user-event instance.
 * @param comboboxName - Accessible name of the dropdown trigger.
 * @param optionName - Visible label of the option to pick.
 */
export async function selectOption(
  user: UserEvent,
  comboboxName: string | RegExp,
  optionName: string,
): Promise<void> {
  await user.click(screen.getByRole('combobox', { name: comboboxName }))
  await user.click(screen.getByRole('option', { name: optionName }))
}

/**
 * Creates an isolated query client per test: no retries (failures surface
 * immediately) and no refetch timers leaking between tests.
 *
 * @returns A fresh query client.
 */
function freshClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0, staleTime: 0 } },
  })
}

export interface RenderAppOptions {
  /** Initial entries for the memory router. Defaults to `['/']`. */
  route?: string
  /** Gateway key seeded into the memory store before render. */
  gatewayKey?: string
  /** Seeds a test admin session (`test-admin` / `test-admin-jwt`). */
  adminSession?: boolean
  /** Seeds a test non-admin session (tiles and admin routes stay hidden). */
  nonAdminSession?: boolean
}

/**
 * Renders UI inside a fresh query client, a memory router, and a cleared
 * auth store.
 *
 * @param ui - Element under test.
 * @param options - Router route and credential overrides.
 * @returns The Testing Library render result.
 */
export function renderApp(ui: ReactElement, options?: RenderAppOptions) {
  useAuthStore.getState().clear()
  if (options?.gatewayKey !== undefined) useAuthStore.getState().setGatewayKey(options.gatewayKey)
  if (options?.adminSession === true) {
    useAuthStore
      .getState()
      .setSession({ accessToken: 'test-admin-jwt', admin: true, username: 'test-admin' })
  }
  if (options?.nonAdminSession === true) {
    useAuthStore
      .getState()
      .setSession({ accessToken: 'test-user-jwt', admin: false, username: 'test-user' })
  }
  const client = freshClient()
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[options?.route ?? '/']}>
        <Routes>
          <Route path="*" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  useAuthStore.getState().clear()
})

afterEach(() => {
  useAuthStore.getState().clear()
})
