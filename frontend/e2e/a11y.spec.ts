import { expect, test } from '@playwright/test'
import { scanForA11yViolations } from './a11y.js'

/**
 * WCAG 2.2 AA gate over every screen's backend-independent idle state.
 * Session and admin routes seed a DEV-only in-memory session first
 * (`/__test/session/<role>`, eliminated from production builds); the
 * expected `h1` assertion proves the scan ran against the real screen,
 * never the login page (FE-05). Any axe violation fails the spec; fix
 * the app, never the assertion.
 */
const SCREENS: { route: string; heading: string; seed?: 'user' | 'admin' }[] = [
  { route: '/', heading: 'Overview', seed: 'user' },
  { route: '/playground', heading: 'Playground' },
  { route: '/circuits', heading: 'Circuits', seed: 'admin' },
  { route: '/keys', heading: 'Keys', seed: 'admin' },
  { route: '/ledger', heading: 'Ledger', seed: 'admin' },
  { route: '/cache', heading: 'Cache and budgets', seed: 'admin' },
  { route: '/embeddings', heading: 'Embeddings' },
  { route: '/approvals', heading: 'Approvals', seed: 'admin' },
  { route: '/mcp', heading: 'MCP tools' },
  { route: '/observability', heading: 'Observability', seed: 'user' },
]

for (const { route, heading, seed } of SCREENS) {
  test(`a11y clean: ${route}`, async ({ page }) => {
    if (seed === undefined) {
      await page.goto(route)
    } else {
      // No backend in the e2e environment: the DEV-only seed route sets
      // the memory session, then lands on the target screen.
      await page.goto(`/__test/session/${seed}?next=${route}`)
    }
    // Wait for the lazy route chunk (past the Suspense fallback, which has
    // no heading) and prove this is the target screen, not a guard bounce.
    await expect(page.getByRole('heading', { level: 1, name: heading })).toBeVisible()
    await scanForA11yViolations(page)
    // Same screen in the other theme: proves both the `-soft` dark text
    // variants and the light paper tokens. The app boots dark by default,
    // so the toggle reads "light theme" on first paint.
    await page.getByRole('button', { name: /theme/i }).click()
    // Let the 180ms theme transition settle so axe measures resting
    // tokens, never a mid-flight blend.
    await page.waitForTimeout(350)
    await expect(page.getByRole('heading', { level: 1, name: heading })).toBeVisible()
    await scanForA11yViolations(page)
  })
}
