import { screen, waitFor } from '@testing-library/react'
import { HttpResponse, http } from 'msw'
import { describe, expect, it } from 'vitest'
import { useAuthStore } from '../../shared/auth/store.js'
import { server } from '../../test/setup.js'
import { renderApp } from '../../test/utils.js'
import { SsoCallback } from './SsoCallback.js'

function landOn(fragment: string) {
  window.history.replaceState(null, '', `/?sso=1${fragment}`)
}

/**
 * Never settles: simulates a stalled IdP backfill behind the SSO timeout.
 *
 * @returns A promise that never settles.
 */
function hang(): Promise<never> {
  return new Promise<never>(() => undefined)
}

describe('SsoCallback', () => {
  it('shows the pending copy while completing, not an instant failure', () => {
    server.use(http.get('*/v1/auth/me', hang))
    landOn('#access_token=tok&admin=false')
    renderApp(<SsoCallback />, { route: '/?sso=1' })
    expect(screen.getByRole('status')).toHaveTextContent(/completing sso sign-in/i)
    expect(screen.getByText(/syncs idp groups/i)).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('moves the fragment token to memory and clears the URL', async () => {
    server.use(
      http.get('*/v1/auth/me', () =>
        HttpResponse.json({ userId: 'u1', username: 'op', admin: false }),
      ),
    )
    landOn('#access_token=frag-jwt&admin=false')
    renderApp(<SsoCallback />, { route: '/?sso=1' })
    await waitFor(() => {
      expect(useAuthStore.getState().session?.username).toBe('op')
    })
    expect(useAuthStore.getState().session?.accessToken).toBe('frag-jwt')
    expect(window.location.hash).toBe('')
  })

  it('rejects a tokenless landing with a restart path', async () => {
    landOn('')
    renderApp(<SsoCallback />, { route: '/?sso=1' })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/without a token/i)
    })
  })

  it('rejects an empty token exactly like a missing one', async () => {
    landOn('#access_token=&admin=false')
    renderApp(<SsoCallback />, { route: '/?sso=1' })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/without a token/i)
    })
  })

  it('rejects non-SSO landings without fetching', async () => {
    let calls = 0
    server.use(
      http.get('*/v1/auth/me', () => {
        calls += 1
        return HttpResponse.json({ userId: 'u1', username: 'op', admin: false })
      }),
    )
    window.history.replaceState(null, '', '/')
    renderApp(<SsoCallback />, { route: '/' })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/only completes sso/i)
    })
    expect(calls).toBe(0)
  })

  it('hints IdP disablement on 401 instead of looping', async () => {
    server.use(http.get('*/v1/auth/me', () => new HttpResponse('x', { status: 401 })))
    landOn('#access_token=dead&admin=false')
    renderApp(<SsoCallback />, { route: '/?sso=1' })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/idp account may be disabled/i)
    })
    expect(useAuthStore.getState().session).toBeNull()
    expect(window.location.hash).toBe('')
  })

  it('treats 403 like 401 for disabled accounts', async () => {
    server.use(http.get('*/v1/auth/me', () => new HttpResponse('x', { status: 403 })))
    landOn('#access_token=dead&admin=false')
    renderApp(<SsoCallback />, { route: '/?sso=1' })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(/idp account may be disabled/i)
    })
  })

  it('times out with retry copy when backfill stalls', async () => {
    server.use(http.get('*/v1/auth/me', hang))
    landOn('#access_token=slow&admin=false')
    renderApp(<SsoCallback timeoutMs={50} />, { route: '/?sso=1' })
    expect(screen.getByRole('status')).toBeInTheDocument()
    await waitFor(
      () => {
        expect(screen.getByRole('alert')).toHaveTextContent(/timed out after 30 seconds/i)
      },
      { timeout: 5000 },
    )
    expect(window.location.hash).toBe('')
  })
})
