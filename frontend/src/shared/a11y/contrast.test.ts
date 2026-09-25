import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

/**
 * WCAG 2.2 relative luminance for one sRGB channel value.
 *
 * @param hex - Six-digit hex color (with or without `#`).
 * @returns Relative luminance in [0, 1].
 */
function luminance(hex: string): number {
  const c = hex.replace('#', '')
  const channel = (i: number): number => {
    const v = parseInt(c.slice(i, i + 2), 16) / 255
    return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4
  }
  return 0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4)
}

/**
 * WCAG contrast ratio between two colors.
 *
 * @param fg - Foreground hex.
 * @param bg - Background hex.
 * @returns Ratio (1–21).
 */
function ratio(fg: string, bg: string): number {
  const hi = Math.max(luminance(fg), luminance(bg))
  const lo = Math.min(luminance(fg), luminance(bg))
  return (hi + 0.05) / (lo + 0.05)
}

/**
 * Flattens a translucent tint over an opaque base (sRGB lerp).
 *
 * @param tint - Tint hex.
 * @param base - Base hex.
 * @param alpha - Tint opacity.
 * @returns Flattened hex.
 */
function flatten(tint: string, base: string, alpha: number): string {
  const t = tint.replace('#', '')
  const b = base.replace('#', '')
  const channel = (i: number): string => {
    const v = Math.round(
      parseInt(t.slice(i, i + 2), 16) * alpha + parseInt(b.slice(i, i + 2), 16) * (1 - alpha),
    )
    return v.toString(16).padStart(2, '0')
  }
  return `#${channel(0)}${channel(2)}${channel(4)}`
}

/**
 * Reads the shipped design tokens from `src/index.css` (single source).
 * Token hexes are never duplicated here: a token rename, removal, or
 * contrast regression in the stylesheet fails this suite (DEF-07).
 *
 * @returns Map of custom property name to six-digit hex value.
 */
function readTokens(): Map<string, string> {
  const cssPath = resolve(dirname(fileURLToPath(import.meta.url)), '../../index.css')
  const css = readFileSync(cssPath, 'utf8')
  const tokens = new Map<string, string>()
  for (const match of css.matchAll(/--color-([\w-]+)\s*:\s*(#[0-9a-fA-F]{6})/g)) {
    tokens.set(match[1] ?? '', (match[2] ?? '').toLowerCase())
  }
  return tokens
}

const tokens = readTokens()

/**
 * Looks up one parsed token, failing loudly when the stylesheet no
 * longer defines it (a rename without a test update must break).
 *
 * @param name - Token name without the `--color-` prefix.
 * @returns The six-digit hex value.
 */
function token(name: string): string {
  const value = tokens.get(name)
  if (value === undefined) throw new Error(`Design token --color-${name} missing from index.css`)
  return value
}

describe('flattened badge contrast (WCAG 2.2 SC 1.4.3, 4.5:1 floor)', () => {
  it('keeps light badge text readable on its translucent tint', () => {
    // Deep badge text over its tint flattened on paper — every value
    // derived from the parsed stylesheet, only the 4.5 floor is fixed.
    const paper = token('paper')
    const pairs: [name: string, fg: string, bg: string][] = [
      ['success badge', token('success-deep'), flatten(token('success'), paper, 0.15)],
      ['danger badge', token('danger-deep'), flatten(token('danger'), paper, 0.15)],
      ['warn badge', token('warn-deep'), flatten(token('warn'), paper, 0.2)],
    ]
    for (const [name, fg, bg] of pairs) {
      expect(ratio(fg, bg), `${name} ${fg} on ${bg}`).toBeGreaterThanOrEqual(4.5)
    }
  })

  it('keeps placeholder text readable in both themes', () => {
    expect(ratio(token('ink-soft'), token('paper'))).toBeGreaterThanOrEqual(4.5)
    expect(ratio(token('parchment-soft'), token('night'))).toBeGreaterThanOrEqual(4.5)
  })
})
