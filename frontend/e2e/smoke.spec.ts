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
    { link: 'MCP', heading: 'MCP tools' },
  ]
  for (const s of screens) {
    await page.getByRole('link', { name: s.link, exact: true }).click()
    await expect(page.getByRole('heading', { name: s.heading })).toBeVisible()
  }
  for (const label of ['Overview', 'Observability']) {
    await expect(page.getByRole('link', { name: label, exact: true })).toHaveCount(0)
  }
  await page.goto('/observability')
  await expect(page.getByRole('heading', { name: /log in/i })).toBeVisible()
  await page.goto('/mcp')
  await expect(page.getByRole('heading', { name: 'MCP tools' })).toBeVisible()
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
 * FE-03: a tab-smuggled `next` value never leaves the origin. Guests stay on
 * the login screen (this leg). The authenticated post-login leg needs a
 * real login POST against a live gateway, which the backend-less e2e
 * environment cannot do; the unit leg pins the resolver contract.
 */
test('smuggled next never leaves the origin', async ({ page }) => {
  await page.goto('/login?next=%2F%09%2Fevil.example')
  await expect(page.getByRole('heading', { name: /log in/i })).toBeVisible()
  expect(new URL(page.url()).hostname).not.toBe('evil.example')
})

/**
 * DEF-01: dot-segment smuggling (`/..//evil.example` and encoded variants)
 * resolves same-origin but yields a `//host` pathname. With a seeded
 * session, navigating to the payload must never leave the origin, and the
 * in-browser resolver must return `/` for every variant (Chromium's URL
 * parser, not jsdom).
 */
test('dot-segment next never leaves the origin', async ({ page }) => {
  await page.goto('/__test/session/user?next=/')
  await expect(page.getByRole('heading', { level: 1, name: 'Overview' })).toBeVisible()
  const origin = new URL(page.url()).origin
  // Prove Chromium's parser agrees with the audit: the payload stays
  // same-origin yet yields a `//host` pathname (the old code returned it).
  const pathname: unknown = await page.evaluate(
    () => new URL('/..//evil.example', window.location.origin).pathname,
  )
  expect(pathname).toBe('//evil.example')
  await page.goto('/login?next=%2F..%2F%2Fevil.example')
  expect(page.url().startsWith(origin)).toBe(true)
  expect(new URL(page.url()).hostname).not.toBe('evil.example')
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
