import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { server } from '../../test/setup.js'
import { useAuthStore } from './store.js'
import {
  login,
  logout,
  redeemInvite,
  refreshSession,
  restoreSession,
  startSessionHeartbeat,
} from './session.js'

beforeEach(() => {
  useAuthStore.getState().clear()
  vi.useRealTimers()
})

afterEach(() => {
  useAuthStore.getState().clear()
  vi.useRealTimers()
})

describe('login', () => {
  it('stores a memory-only session on success', async () => {
    server.use(
      http.post('*/v1/auth/login', () =>
        HttpResponse.json({ accessToken: 'jwt-1', expiresInSeconds: 300, admin: true }),
      ),
    )
    const session = await login('operator', 'correct horse battery staple')
    expect(session).toEqual({ accessToken: 'jwt-1', admin: true, username: 'operator' })
    expect(useAuthStore.getState().session).toEqual(session)
  })

  it('rejects wrong credentials without storing', async () => {
    server.use(http.post('*/v1/auth/login', () => new HttpResponse('x', { status: 401 })))
    await expect(login('operator', 'nope')).rejects.toThrow(/invalid credentials/i)
    expect(useAuthStore.getState().session).toBeNull()
  })

  it('names lockouts distinctly from bad passwords', async () => {
    server.use(http.post('*/v1/auth/login', () => new HttpResponse('x', { status: 429 })))
    await expect(login('operator', 'nope')).rejects.toThrow(/try again later/i)
  })

  it('rejects malformed success bodies', async () => {
    server.use(http.post('*/v1/auth/login', () => HttpResponse.json({ admin: true })))
    await expect(login('operator', 'x')).rejects.toThrow(/HTTP 200/)
  })

  it('surfaces backend detail and message payloads', async () => {
    server.use(
      http.post('*/v1/auth/login', () =>
        HttpResponse.json({ detail: 'Account locked.' }, { status: 400 }),
      ),
    )
    await expect(login('operator', 'x')).rejects.toThrow(/account locked/i)
    server.use(
      http.post('*/v1/auth/login', () =>
        HttpResponse.json({ message: 'Bad shape.' }, { status: 400 }),
      ),
    )
    await expect(login('operator', 'x')).rejects.toThrow(/bad shape/i)
  })

  it('falls back to status text for unusable bodies', async () => {
    server.use(http.post('*/v1/auth/login', () => new HttpResponse('nope', { status: 400 })))
    await expect(login('operator', 'x')).rejects.toThrow(/HTTP 400/)
    server.use(http.post('*/v1/auth/login', () => new HttpResponse('', { status: 400 })))
    await expect(login('operator', 'x')).rejects.toThrow(/HTTP 400/)
  })

  it('rejects wrong-shaped success payloads', async () => {
    for (const body of [null, [], 'jwt-string', { accessToken: '', admin: true }]) {
      server.use(http.post('*/v1/auth/login', () => HttpResponse.json(body)))
      await expect(login('operator', 'x')).rejects.toThrow(/HTTP 200/)
    }
    server.use(http.post('*/v1/auth/login', () => HttpResponse.json({ accessToken: 'j' })))
    await expect(login('operator', 'x')).rejects.toThrow(/HTTP 200/)
  })

  it('rejects unparseable success bodies', async () => {
    server.use(http.post('*/v1/auth/login', () => new HttpResponse('not-json{{{', { status: 200 })))
    await expect(login('operator', 'x')).rejects.toThrow(/HTTP 200/)
  })

  it('reads bodies that fail mid-stream as empty', async () => {
    server.use(
      http.post('*/v1/auth/login', () => {
        const stream = new ReadableStream<Uint8Array>({
          start(ctrl) {
            ctrl.error(new Error('truncated'))
          },
        })
        return new HttpResponse(stream, { status: 400 })
      }),
    )
    await expect(login('operator', 'x')).rejects.toThrow(/HTTP 400/)
  })
})

describe('redeemInvite', () => {
  it('logs in immediately on 201', async () => {
    server.use(
      http.post('*/v1/auth/redeem', () =>
        HttpResponse.json(
          { accessToken: 'jwt-2', expiresInSeconds: 300, admin: true },
          { status: 201 },
        ),
      ),
    )
    const session = await redeemInvite('tok', 'operator', 'correct horse battery staple')
    expect(session.admin).toBe(true)
  })

  it('answers unknown and consumed invites identically', async () => {
    server.use(http.post('*/v1/auth/redeem', () => new HttpResponse('x', { status: 404 })))
    await expect(redeemInvite('bad', 'op', 'correct horse battery staple')).rejects.toThrow(
      /invalid or already used/i,
    )
    server.use(http.post('*/v1/auth/redeem', () => new HttpResponse('x', { status: 410 })))
    await expect(redeemInvite('old', 'op', 'correct horse battery staple')).rejects.toThrow(
      /invalid or already used/i,
    )
    expect(useAuthStore.getState().session).toBeNull()
  })

  it('rejects unparseable redeem bodies', async () => {
    server.use(
      http.post('*/v1/auth/redeem', () => new HttpResponse('not-json{{{', { status: 201 })),
    )
    await expect(redeemInvite('tok', 'operator', 'correct horse battery staple')).rejects.toThrow(
      /HTTP 201/,
    )
  })
})

describe('refreshSession', () => {
  it('sends the required refresh CSRF marker header', async () => {
    let marker: string | null = null
    server.use(
      http.post('*/v1/auth/refresh', ({ request }) => {
        marker = request.headers.get('X-CacheRelay-Refresh')
        return HttpResponse.json({ accessToken: 'jwt-h', expiresInSeconds: 300, admin: false })
      }),
    )
    await refreshSession()
    expect(marker).toBe('1')
  })

  it('restores the session and shares one flight', async () => {
    let calls = 0
    server.use(
      http.post('*/v1/auth/refresh', () => {
        calls += 1
        return HttpResponse.json({ accessToken: 'jwt-3', expiresInSeconds: 300, admin: false })
      }),
    )
    const [a, b] = await Promise.all([refreshSession(), refreshSession()])
    expect(calls).toBe(1)
    expect(a?.accessToken).toBe('jwt-3')
    expect(b?.accessToken).toBe('jwt-3')
  })

  it('clears the session when rotation fails', async () => {
    useAuthStore.getState().setSession({ accessToken: 'stale', admin: true, username: 'op' })
    server.use(http.post('*/v1/auth/refresh', () => new HttpResponse('x', { status: 401 })))
    expect(await refreshSession()).toBeNull()
    expect(useAuthStore.getState().session).toBeNull()
  })

  it('clears the session on network failure', async () => {
    useAuthStore.getState().setSession({ accessToken: 'stale', admin: true, username: 'op' })
    server.use(http.post('*/v1/auth/refresh', () => HttpResponse.error()))
    expect(await refreshSession()).toBeNull()
    expect(useAuthStore.getState().session).toBeNull()
  })
})

describe('restoreSession', () => {
  it('stays silent without a cookie', async () => {
    server.use(http.post('*/v1/auth/refresh', () => new HttpResponse('x', { status: 401 })))
    await restoreSession()
    expect(useAuthStore.getState().session).toBeNull()
  })

  it('restores a live session without a flash', async () => {
    server.use(
      http.post('*/v1/auth/refresh', () =>
        HttpResponse.json({ accessToken: 'jwt-5', expiresInSeconds: 300, admin: true }),
      ),
    )
    await restoreSession()
    expect(useAuthStore.getState().session?.username).toBe('')
  })
})

describe('startSessionHeartbeat', () => {
  it('renews while a session exists and stops on demand', async () => {
    vi.useFakeTimers()
    try {
      useAuthStore.getState().setSession({ accessToken: 'old', admin: true, username: 'op' })
      let calls = 0
      server.use(
        http.post('*/v1/auth/refresh', () => {
          calls += 1
          return HttpResponse.json({ accessToken: 'jwt-4', expiresInSeconds: 300, admin: true })
        }),
      )
      const stop = startSessionHeartbeat()
      await vi.advanceTimersByTimeAsync(4 * 60 * 1000)
      expect(calls).toBe(1)
      expect(useAuthStore.getState().session?.accessToken).toBe('jwt-4')
      stop()
      await vi.advanceTimersByTimeAsync(4 * 60 * 1000)
      expect(calls).toBe(1)
    } finally {
      vi.useRealTimers()
    }
  })

  it('skips ticks without a session', async () => {
    vi.useFakeTimers()
    try {
      let calls = 0
      server.use(
        http.post('*/v1/auth/refresh', () => {
          calls += 1
          return HttpResponse.json({ accessToken: 'x', expiresInSeconds: 1, admin: false })
        }),
      )
      const stop = startSessionHeartbeat()
      try {
        await vi.advanceTimersByTimeAsync(4 * 60 * 1000)
        expect(calls).toBe(0)
      } finally {
        stop()
      }
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('logout', () => {
  it('revokes server-side and always clears memory', async () => {
    let calls = 0
    server.use(
      http.post('*/v1/auth/logout', () => {
        calls += 1
        return new HttpResponse(null, { status: 204 })
      }),
    )
    useAuthStore.getState().setSession({ accessToken: 'jwt-9', admin: true, username: 'op' })
    await logout()
    expect(calls).toBe(1)
    expect(useAuthStore.getState().session).toBeNull()
  })

  it('clears memory even when offline', async () => {
    server.use(http.post('*/v1/auth/logout', () => HttpResponse.error()))
    useAuthStore.getState().setSession({ accessToken: 'jwt-9', admin: true, username: 'op' })
    await logout()
    expect(useAuthStore.getState().session).toBeNull()
  })

  it('logs out cleanly without a session', async () => {
    let calls = 0
    server.use(
      http.post('*/v1/auth/logout', () => {
        calls += 1
        return new HttpResponse(null, { status: 204 })
      }),
    )
    await logout()
    expect(calls).toBe(1)
    expect(useAuthStore.getState().session).toBeNull()
  })
})
