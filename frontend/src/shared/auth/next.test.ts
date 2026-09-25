import { describe, expect, it } from 'vitest'
import { resolveNext } from './next.js'

describe('resolveNext', () => {
  it('keeps plain in-app paths including nested routes and queries', () => {
    expect(resolveNext('/observability')).toBe('/observability')
    expect(resolveNext('/ledger?page=2')).toBe('/ledger?page=2')
  })

  it('falls back to home for absent or empty values', () => {
    expect(resolveNext(null)).toBe('/')
    expect(resolveNext('')).toBe('/')
  })

  it('rejects external, protocol-relative, and backslash destinations', () => {
    expect(resolveNext('https://evil.example/steal')).toBe('/')
    expect(resolveNext('//evil.example/steal')).toBe('/')
    expect(resolveNext('/\\evil.example')).toBe('/')
    expect(resolveNext('java\\script:alert(1)')).toBe('/')
  })

  it('refuses auth-screen loops and oversized values', () => {
    expect(resolveNext('/login')).toBe('/')
    expect(resolveNext('/login?next=/mcp')).toBe('/')
    expect(resolveNext('/redeem?token=abc')).toBe('/')
    expect(resolveNext(`/${'a'.repeat(3000)}`)).toBe('/')
  })

  it('rejects tab and newline smuggling that the URL parser strips', () => {
    expect(resolveNext('/\t/evil.example')).toBe('/')
    expect(resolveNext('/\n/evil.example')).toBe('/')
    expect(resolveNext('/\r/evil.example')).toBe('/')
    expect(resolveNext('/%09/evil.example')).toBe('/%09/evil.example')
  })

  it('caps expansion from percent-encoded wide characters', () => {
    // 2048 chars in, far more out: the resolved path still bounces home.
    expect(resolveNext(`/${'é'.repeat(2047)}`)).toBe('/')
    expect(resolveNext('/ledger?page=2')).toBe('/ledger?page=2')
    expect(resolveNext('/ledger#row')).toBe('/ledger#row')
  })

  it('rejects dot-segment smuggling that resolves to a //host pathname', () => {
    expect(resolveNext('/..//evil.example')).toBe('/')
    expect(resolveNext('/%2e%2e//evil.example')).toBe('/')
    expect(resolveNext('/.//evil.example')).toBe('/')
    expect(resolveNext('/x/%2e%2e//evil.example')).toBe('/')
  })
})
