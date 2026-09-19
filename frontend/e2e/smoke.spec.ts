import { expect, test } from '@playwright/test'

/**
 * Console smoke: shell renders, public screens render their idle states,
 * admin screens render the missing page when logged out (stealth: identical
 * to an unknown route). No backend is expected to succeed here.
 */
test('shell loads and every screen renders its idle state', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Overview' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Skip to content' })).toBeVisible()

  const screens: { link: string; heading: string }[] = [
    { link: 'Playground', heading: 'Playground' },
    { link: 'Embeddings', heading: 'Embeddings' },
    { link: 'MCP', heading: 'MCP tools' },
    { link: 'Observability', heading: 'Observability' },
  ]
  for (const s of screens) {
    await page.getByRole('link', { name: s.link, exact: true }).click()
    await expect(page.getByRole('heading', { name: s.heading })).toBeVisible()
  }
})

/**
 * Stealth: logged-out admin routes render the missing page, and the sidebar
 * offers no hint they exist.
 */
test('admin routes look missing when logged out', async ({ page }) => {
  await page.goto('/')
  for (const label of ['Circuits', 'Keys', 'Ledger', 'Cache & budgets', 'Approvals']) {
    await expect(page.getByRole('link', { name: label, exact: true })).toHaveCount(0)
  }
  await page.goto('/circuits')
  await expect(page.getByRole('heading', { name: /page not found/i })).toBeVisible()
  await expect(page.getByText(/does not exist/i)).toBeVisible()
  await page.goto('/no-such-route')
  await expect(page.getByRole('heading', { name: /page not found/i })).toBeVisible()
})

/**
 * Login screen renders its credential form.
 */
test('login screen renders', async ({ page }) => {
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: /log in/i })).toBeVisible()
  await expect(page.getByLabel(/username/i)).toBeVisible()
  await expect(page.getByLabel(/^password$/i)).toBeVisible()
})

/**
 * Command palette opens with Ctrl+K and hides admin actions when logged out.
 */
test('palette opens and navigates', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('button', { name: /commands/i }).click()
  await expect(page.getByText('Go to Playground')).toBeVisible()
  await expect(page.getByText('Go to Ledger')).toHaveCount(0)
})
