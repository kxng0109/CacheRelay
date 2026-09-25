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
  const results: AxeResults = await page.evaluate(async (target: string | undefined) => {
    const runner = (window as unknown as AxeWindow).axe
    const context: unknown = target ?? document
    return runner.run(context, { resultTypes: ['violations', 'incomplete'] })
  }, selector)
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
