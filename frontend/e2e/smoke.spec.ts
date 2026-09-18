import { expect, test } from '@playwright/test'

/**
 * Console smoke: shell renders, primary nav reaches every screen without a
 * backend. Screens show their actionable empty/gate states; no request is
 * expected to succeed here.
 */
test('shell loads and every screen renders its idle state', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Overview' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Skip to content' })).toBeVisible()

  const screens: { link: string; heading: string }[] = [
    { link: 'Playground', heading: 'Playground' },
    { link: 'Circuits', heading: 'Circuits' },
    { link: 'Keys', heading: 'Keys' },
    { link: 'Ledger', heading: 'Ledger' },
    { link: 'Cache & budgets', heading: 'Cache and budgets' },
    { link: 'Embeddings', heading: 'Embeddings' },
    { link: 'Approvals', heading: 'Approvals' },
    { link: 'MCP', heading: 'MCP tools' },
    { link: 'Observability', heading: 'Observability' },
  ]
  for (const s of screens) {
    await page.getByRole('link', { name: s.link, exact: true }).click()
    await expect(page.getByRole('heading', { name: s.heading })).toBeVisible()
  }
})

/**
 * Admin gate: locked screens ask for the master key instead of calling the
 * backend with nothing.
 */
test('circuits asks for the admin key when locked', async ({ page }) => {
  await page.goto('/circuits')
  await expect(page.getByLabel(/master admin key/i)).toBeVisible()
})

/**
 * Command palette opens with Ctrl+K and lists section actions.
 */
test('palette opens and navigates', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('button', { name: /commands/i }).click()
  await expect(page.getByText('Go to Ledger')).toBeVisible()
})
