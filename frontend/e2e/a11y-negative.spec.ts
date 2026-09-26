import { expect, test } from '@playwright/test'
import { scanForA11yViolations } from './a11y.js'

/**
 * DEF-05: negative control for the axe gate. Injects a deliberate
 * low-contrast paragraph, then asserts the scanner rejects it — proving
 * `scanForA11yViolations` can fail. The injection is removed afterwards so
 * no shipped surface changes. If the `expect(violations).toEqual([])` line
 * is ever deleted from `a11y.ts`, this test fails (nothing rejects).
 */
test('axe gate rejects a deliberate contrast violation', async ({ page, browserName }) => {
  // Contrast-rule carve-out (see a11y.ts): on Firefox the color-contrast
  // rule is disabled (axe cannot parse Firefox's oklab serialization), so
  // a contrast probe cannot reject there. Structural checks stay on
  // Firefox via the main gate; this control runs where the rule runs.
  test.skip(
    browserName === 'firefox',
    'color-contrast rule disabled on Firefox; covered on Chromium/WebKit',
  )
  await page.goto('/playground')
  await expect(page.getByRole('heading', { level: 1, name: 'Playground' })).toBeVisible()
  await page.evaluate(() => {
    const p = document.createElement('p')
    p.id = 'axe-negative-probe'
    p.textContent = 'deliberate low contrast probe text for the negative control'
    p.setAttribute('style', 'color: #f0f0f0; background-color: #ffffff;')
    document.querySelector('#main')?.appendChild(p)
  })
  let rejected = false
  try {
    await scanForA11yViolations(page, '#axe-negative-probe')
  } catch {
    rejected = true
  } finally {
    await page.evaluate(() => {
      document.querySelector('#axe-negative-probe')?.remove()
    })
  }
  expect(rejected).toBe(true)
})
