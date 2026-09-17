import { describe, expect, it } from 'vitest'
import { isSafeUrl } from './safe-url.js'

describe('isSafeUrl', () => {
  it.each([
    'https://example.com/x',
    'http://localhost:8080/v3/api-docs',
    'mailto:a@b.c',
    'tel:+123',
    '/v1/models',
    '#main',
  ])('accepts %s', (url) => {
    expect(isSafeUrl(url)).toBe(true)
  })

  it.each([
    'javascript:alert(1)',
    'JaVaScRiPt:alert(1)',
    'data:text/html,<script>alert(1)</script>',
    'vbscript:msgbox(1)',
    '',
    '   ',
    '::::not a url',
  ])('rejects %s', (url) => {
    expect(isSafeUrl(url)).toBe(false)
  })
})
