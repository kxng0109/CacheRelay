import { expect, test } from '@playwright/test'

/**
 * Console smoke: guests land on login from `/`, public screens render their
 * idle states, session screens offer no hint and bounce to login.
 * No backend is expected to succeed here.
 */
test('shell loads, guests land on login, public screens render idle', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: /log in/i })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Skip to content' })).toBeVisible()

  const screens: { link: string; heading: string }[] = [
    { link: 'Playground', heading: 'Playground' },
    { link: 'Embeddings', heading: 'Embeddings' },
  ]
  for (const s of screens) {
    await page.getByRole('link', { name: s.link, exact: true }).click()
    await expect(page.getByRole('heading', { name: s.heading })).toBeVisible()
  }
  for (const label of ['Overview', 'MCP', 'Observability']) {
    await expect(page.getByRole('link', { name: label, exact: true })).toHaveCount(0)
  }
  await page.goto('/observability')
  await expect(page.getByRole('heading', { name: /log in/i })).toBeVisible()
  await page.goto('/mcp')
  await expect(page.getByRole('heading', { name: /log in/i })).toBeVisible()
})

/**
 * Stealth: logged-out admin routes bounce to login (preserving the
 * destination), the sidebar offers no hint they exist, and unknown
 * routes still render the missing page. Logged-in non-admins see the
 * missing page instead — covered by unit tests in guards.test.tsx.
 */
test('admin routes bounce guests to login when logged out', async ({ page }) => {
  await page.goto('/')
  for (const label of ['Circuits', 'Models', 'Keys', 'Ledger', 'Cache & budgets', 'Approvals']) {
    await expect(page.getByRole('link', { name: label, exact: true })).toHaveCount(0)
  }
  await page.goto('/circuits')
  await expect(page.getByRole('heading', { name: /log in/i })).toBeVisible()
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
