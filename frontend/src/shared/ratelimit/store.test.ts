import { beforeEach, describe, expect, it } from 'vitest'
import { useRateLimitStore } from './store.js'

beforeEach(() => {
  useRateLimitStore.getState().clear()
})

describe('useRateLimitStore', () => {
  it('starts with no snapshot before any gateway response', () => {
    expect(useRateLimitStore.getState().snapshot).toBeNull()
  })

  it('replaces the snapshot with freshly parsed headers', () => {
    useRateLimitStore
      .getState()
      .setSnapshot({ dimension: 'RPM', limit: 60, remaining: 41, reset: 12, retryAfter: null })
    expect(useRateLimitStore.getState().snapshot).toEqual({
      dimension: 'RPM',
      limit: 60,
      remaining: 41,
      reset: 12,
      retryAfter: null,
    })
  })

  it('overwrites stale snapshots instead of merging', () => {
    useRateLimitStore
      .getState()
      .setSnapshot({ dimension: 'RPM', limit: 60, remaining: 41, reset: 12, retryAfter: null })
    useRateLimitStore
      .getState()
      .setSnapshot({ dimension: null, limit: null, remaining: null, reset: null, retryAfter: 9 })
    expect(useRateLimitStore.getState().snapshot).toEqual({
      dimension: null,
      limit: null,
      remaining: null,
      reset: null,
      retryAfter: 9,
    })
  })

  it('clears back to null on sign-out', () => {
    useRateLimitStore
      .getState()
      .setSnapshot({ dimension: 'RPM', limit: 60, remaining: 41, reset: 12, retryAfter: null })
    useRateLimitStore.getState().clear()
    expect(useRateLimitStore.getState().snapshot).toBeNull()
  })
})
