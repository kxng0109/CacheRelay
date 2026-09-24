import { describe, expect, it } from 'vitest'
import { dateToIso, validateWindow } from './window.js'

describe('dateToIso', () => {
  it('bounds start and end of day', () => {
    expect(dateToIso('2026-09-24', false)).toBe('2026-09-24T00:00:00Z')
    expect(dateToIso('2026-09-24', true)).toBe('2026-09-24T23:59:59Z')
  })

  it('nulls blank and malformed input', () => {
    expect(dateToIso('', false)).toBeNull()
    expect(dateToIso('24/09/2026', false)).toBeNull()
    expect(dateToIso('2026-13-40', false)).toBe('2026-13-40T00:00:00Z')
  })
})

describe('validateWindow', () => {
  it('accepts blanks as backend defaults', () => {
    expect(validateWindow('', '')).toEqual({ error: null })
  })

  it('rejects malformed dates', () => {
    expect(validateWindow('yesterday', '').error).toMatch(/YYYY-MM-DD/)
  })

  it('rejects reversed windows', () => {
    expect(validateWindow('2026-09-24', '2026-09-01').error).toMatch(/cannot be after/)
  })

  it('rejects oversize windows with the narrow copy', () => {
    expect(validateWindow('2026-01-01', '2026-09-24').error).toMatch(/narrow the window/i)
  })

  it('accepts the widest inclusive span under the ceiling', () => {
    // End-of-day bounds consume the margin: 89 date-days land at 89.99
    // instants-days, inside the backend 90-day Duration check.
    const result = validateWindow('2026-06-27', '2026-09-24')
    expect(result.error).toBeNull()
    expect(result.fromIso).toBe('2026-06-27T00:00:00Z')
    expect(result.toIso).toBe('2026-09-24T23:59:59Z')
  })
})
