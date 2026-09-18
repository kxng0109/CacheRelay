import { expect, test } from '@playwright/test'
import { scanForA11yViolations } from './a11y.js'

/**
 * WCAG 2.2 AA gate over every screen's backend-independent idle state.
 * Any axe violation fails the spec; fix the app, never the assertion.
 */
const SCREENS = [
  '/',
  '/circuits',
  '/keys',
  '/ledger',
  '/cache',
  '/embeddings',
  '/approvals',
  '/mcp',
  '/observability',
]

for (const route of SCREENS) {
  test(`a11y clean: ${route}`, async ({ page }) => {
    await page.goto(route)
    // Wait for the lazy route chunk (past the Suspense fallback, which has
    // no heading) so axe scans the real screen, not the loader.
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await scanForA11yViolations(page)
    // Same screen in the other theme: proves both the `-soft` dark text
    // variants and the light paper tokens. The app boots dark by default,
    // so the toggle reads "light theme" on first paint.
    await page.getByRole('button', { name: /theme/i }).click()
    await scanForA11yViolations(page)
  })
}
