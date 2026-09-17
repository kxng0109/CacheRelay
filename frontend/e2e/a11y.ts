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
    run: (context: unknown, options: unknown) => Promise<{ violations: unknown[] }>
  }
}

export async function scanForA11yViolations(page: Page, selector?: string): Promise<void> {
  await page.addScriptTag({ path: axePath })
  const violations: unknown[] = await page.evaluate(async (target: string | undefined) => {
    const runner = (window as unknown as AxeWindow).axe
    const context: unknown = target ?? document
    const results = await runner.run(context, { resultTypes: ['violations'] })
    return results.violations
  }, selector)
  expect(violations).toEqual([])
}
