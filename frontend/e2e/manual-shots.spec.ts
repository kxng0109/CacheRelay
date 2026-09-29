import { expect, test } from '@playwright/test'

test.use({ viewport: { width: 1280, height: 800 } })

const OUT = '.tmp-interact'

/**
 * Manual full-console screenshot pass. Skips in gates unless
 * `MANUAL_SHOTS=1` is set — run explicitly against a live backend:
 *
 * ```powershell
 * $env:MANUAL_SHOTS='1'
 * $env:SCREENSHOT_USERNAME='admin'; $env:SCREENSHOT_PASSWORD='<secret>'
 * $env:PLAYWRIGHT_CHANNEL='chrome'
 * npx.cmd playwright test e2e/manual-shots.spec.ts --project=chromium
 * ```
 *
 * Credentials come from the environment only — never hardcoded here.
 * Shots land in `frontend/.tmp-interact/` (gitignored).
 */
const USERNAME = process.env.SCREENSHOT_USERNAME ?? ''
const PASSWORD = process.env.SCREENSHOT_PASSWORD ?? ''

const ROUTES: [string, string, string][] = [
  ['Go to Overview', 'Overview', 's-01-overview.png'],
  ['Go to Playground', 'Playground', 's-02-playground.png'],
  ['Go to Embeddings', 'Embeddings', 's-03-embeddings.png'],
  ['Go to Teams', 'Teams', 's-04-teams.png'],
  ['Go to Circuits', 'Circuits', 's-05-circuits.png'],
  ['Go to Models', 'Models', 's-06-models.png'],
  ['Go to Approvals', 'Approvals', 's-07-approvals.png'],
  ['Go to Cache and budgets', 'Cache', 's-08-cache.png'],
  ['Go to Keys', 'Keys', 's-09-keys.png'],
  ['Go to Ledger', 'Ledger', 's-10-ledger.png'],
  ['Go to MCP', 'MCP', 's-11-mcp.png'],
  ['Go to A2A', 'A2A', 's-12-a2a.png'],
  ['Go to Observability', 'Observability', 's-13-observability.png'],
  ['Go to Usage', 'Usage', 's-14-usage.png'],
  ['Go to Notifications', 'Notifications', 's-15-notifications.png'],
  ['Go to Invites', 'Invites', 's-16-invites.png'],
  ['Go to Users', 'Users', 's-17-users.png'],
]

test('manual full-console screenshots', async ({ page }) => {
  test.skip(
    process.env.MANUAL_SHOTS !== '1',
    'Manual only: rerun with MANUAL_SHOTS=1 in the environment.',
  )
  if (USERNAME.length === 0 || PASSWORD.length === 0) {
    throw new Error('Set SCREENSHOT_USERNAME and SCREENSHOT_PASSWORD in the environment first.')
  }

  await page.goto('/redeem')
  await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible()
  await page.screenshot({ path: `${OUT}/s-00-redeem.png` })

  await page.goto('/login')
  await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible()
  await page.screenshot({ path: `${OUT}/s-00-login.png` })
  await page.getByLabel(/username/i).fill(USERNAME)
  // Two controls match "password" (field + show toggle): fill the field.
  await page
    .getByLabel(/password/i)
    .first()
    .fill(PASSWORD)
  await page.getByRole('button', { name: /^log in$/i }).dispatchEvent('click')
  await expect(page.getByRole('heading', { level: 1 }).first()).toBeVisible({ timeout: 15000 })

  for (const [label, heading, file] of ROUTES) {
    // Palette nav keeps the in-memory session (a `goto` would drop it).
    await page.keyboard.press('Control+k')
    await page.getByPlaceholder(/search actions/i).pressSequentially(label, { delay: 10 })
    await page.keyboard.press('Enter')
    // Route-specific heading waits past the lazy-chunk suspense fallback.
    await expect(
      page.getByRole('heading', { level: 1, name: new RegExp(heading, 'i') }),
    ).toBeVisible({ timeout: 15000 })
    await page.evaluate(() => document.fonts.ready)
    await page.screenshot({ path: `${OUT}/${file}` })
  }
})
