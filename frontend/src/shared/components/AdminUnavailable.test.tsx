import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/client.js'
import { isStealth404 } from './AdminUnavailable.js'

function apiError(status: number): ApiError {
  return new ApiError({
    message: 'x',
    status,
    requestId: null,
    rateLimit: { dimension: null, limit: null, remaining: null, reset: null, retryAfter: null },
    cacheStatus: null,
    debugId: null,
    code: null,
  })
}

describe('isStealth404', () => {
  it('flags ApiError 404s only', () => {
    expect(isStealth404(apiError(404))).toBe(true)
    expect(isStealth404(apiError(403))).toBe(false)
    expect(isStealth404(apiError(500))).toBe(false)
    expect(isStealth404(new Error('nope'))).toBe(false)
    expect(isStealth404(null)).toBe(false)
  })
})
