import { expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { createRequire } from 'node:module'

// axe-core scan helper for Playwright specs. Uses the pinned `axe-core`
// package directly (no extra dependency): the engine is injected into the
// page under test, run with violations-only reporting, and any violation
// fails the spec. Pass a CSS selector to scope the scan to part of a page.
const require = createRequire(import.meta.url)
const axePath: string = require.resolve('axe-core/axe.min.js')

interface AxeWindow {
  axe: {
    run: (context: unknown, options: unknown) => Promise<AxeResults>
  }
}

interface AxeCheck {
  id: string
  data: { messageKey?: string }
}

interface AxeNode {
  target: string[]
  /** Per-node checks; incomplete contrast carries `nonBmp` for glyphs. */
  any: AxeCheck[]
  all: AxeCheck[]
}

interface AxeIssue {
  id: string
  description: string
  nodes: AxeNode[]
}

interface AxeResults {
  violations: AxeIssue[]
  incomplete: AxeIssue[]
}

/**
 * Builds the axe `run` options for the current browser.
 *
 * @remarks Firefox carve-out (verified 2026-09-26): Firefox serializes
 * Tailwind v4 `color-mix()` results in scientific-notation `oklab()`
 * (observed: `oklab(1 0 5.96046e-8 / 0.6)`), which axe-core 4.13.0 — the
 * latest stable — cannot parse (`color-contrast` violation, check
 * `colorParse`: "Could not parse color string …"). oklab support
 * supposedly shipped in 4.8.0 (#4020, #4092) and 4.12.0 (#4959), so this
 * is a parser gap, not a real contrast defect: the same screens pass on
 * Chromium and WebKit, and token-level contrast stays pinned by the
 * browser-independent unit test (`shared/a11y/contrast.test.ts`,
 * DEF-07). Only the unparseable rule is disabled, only on Firefox; every
 * other rule (ARIA, names, landmarks, structure) still runs there.
 * Retire this when axe parses the string (repro: file upstream with the
 * exact string + Firefox version, then drop the branch).
 *
 * @param browserName - Playwright browser name, or undefined when unknown.
 * @returns The axe `run` options for this browser.
 */
function axeRunOptions(browserName: string | undefined): {
  resultTypes: string[]
  rules?: Record<string, { enabled: boolean }>
} {
  const options: {
    resultTypes: string[]
    rules?: Record<string, { enabled: boolean }>
  } = { resultTypes: ['violations', 'incomplete'] }
  if (browserName === 'firefox') {
    options.rules = { 'color-contrast': { enabled: false } }
  }
  return options
}

/**
 * Reviewed `incomplete` results: axe could not decide without a human, and
 * a human reviewed each one and recorded why it passes here. Entries key
 * on rule id plus the check message key, so a *genuine* failure under the
 * same rule (different key, or a real violation) still fails the gate.
 * Never blanket-ignore.
 */
const ALLOWED_INCOMPLETE: { id: string; messageKey: string; reason: string }[] = [
  {
    id: 'color-contrast',
    messageKey: 'nonBmp',
    reason:
      'Prompt/status glyphs (❯ ● ■ ○ › ✕) are aria-hidden non-text decoration; ' +
      'real text contrast is pinned by the flattened-pair unit test (FE-08).',
  },
]

export async function scanForA11yViolations(page: Page, selector?: string): Promise<void> {
  await page.addScriptTag({ path: axePath })
  const browserName = page.context().browser()?.browserType().name()
  const options = axeRunOptions(browserName)
  const results: AxeResults = await page.evaluate(
    async (args: { target: string | undefined; options: unknown }) => {
      const runner = (window as unknown as AxeWindow).axe
      const context: unknown = args.target ?? document
      return runner.run(context, args.options)
    },
    { target: selector, options },
  )
  expect(results.violations).toEqual([])
  // An issue is reviewed only when EVERY reported check on EVERY node
  // matches an allow-list entry: one genuine sub-check still fails.
  const unreviewed = results.incomplete.filter((issue) => {
    const keys = new Set<string>()
    for (const node of issue.nodes) {
      for (const check of [...node.any, ...node.all]) {
        if (typeof check.data?.messageKey === 'string') keys.add(check.data.messageKey)
      }
    }
    if (keys.size === 0) return true
    return ![...keys].every((messageKey) =>
      ALLOWED_INCOMPLETE.some(
        (allowed) => allowed.id === issue.id && allowed.messageKey === messageKey,
      ),
    )
  })
  expect(unreviewed).toEqual([])
}
