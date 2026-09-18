import { act } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { parseTheme, useUiStore } from './store.js'

function setTheme(dark: boolean): void {
  act(() => {
    if (useUiStore.getState().dark !== dark) useUiStore.getState().toggleDark()
  })
}

describe('parseTheme', () => {
  it('passes the two allow-listed literals through', () => {
    expect(parseTheme('light')).toBe('light')
    expect(parseTheme('dark')).toBe('dark')
  })

  it('rejects everything else, including injection shapes', () => {
    expect(parseTheme(null)).toBeNull()
    expect(parseTheme(undefined)).toBeNull()
    expect(parseTheme('')).toBeNull()
    expect(parseTheme('Dark')).toBeNull()
    expect(parseTheme('LIGHT')).toBeNull()
    expect(parseTheme('dark ')).toBeNull()
    expect(parseTheme('__proto__')).toBeNull()
    expect(parseTheme('<script>alert(1)</script>')).toBeNull()
    expect(parseTheme('{"theme":"dark"}')).toBeNull()
    expect(parseTheme(1)).toBeNull()
    expect(parseTheme({ theme: 'dark' })).toBeNull()
    expect(parseTheme(['dark'])).toBeNull()
    expect(parseTheme('d'.repeat(10_001))).toBeNull()
  })
})

describe('useUiStore persistence', () => {
  beforeEach(() => {
    window.localStorage.clear()
    setTheme(true)
  })

  it('persists the literal theme string on toggle', () => {
    act(() => {
      useUiStore.getState().toggleDark()
    })
    expect(window.localStorage.getItem('cacherelay.theme')).toBe('light')
    expect(document.documentElement.classList.contains('dark')).toBe(false)
    act(() => {
      useUiStore.getState().toggleDark()
    })
    expect(window.localStorage.getItem('cacherelay.theme')).toBe('dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('boots dark when storage throws', async () => {
    vi.resetModules()
    const throwing = {
      getItem: (): null => {
        throw new Error('denied')
      },
      setItem: (): void => {
        throw new Error('denied')
      },
      removeItem: vi.fn(),
      clear: vi.fn(),
      key: (): null => null,
      length: 0,
    }
    vi.stubGlobal('localStorage', throwing)
    try {
      const mod = await import('./store.js')
      expect(mod.useUiStore.getState().dark).toBe(true)
      expect(document.documentElement.classList.contains('dark')).toBe(true)
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('keeps session theme when writes are denied', () => {
    const real = window.localStorage
    const throwing = {
      getItem: (key: string): string | null => real.getItem(key),
      setItem: (): void => {
        throw new Error('denied')
      },
      removeItem: vi.fn(),
      clear: vi.fn(),
      key: (): null => null,
      length: 0,
    }
    vi.stubGlobal('localStorage', throwing)
    try {
      setTheme(true)
      act(() => {
        useUiStore.getState().toggleDark()
      })
      expect(useUiStore.getState().dark).toBe(false)
      expect(document.documentElement.classList.contains('dark')).toBe(false)
    } finally {
      vi.unstubAllGlobals()
      setTheme(true)
    }
  })
})
