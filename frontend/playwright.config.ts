import { defineConfig, devices } from '@playwright/test'

// CacheRelay E2E gate (Playwright 1.63).
//
// - `testDir: ./e2e` holds critical-path specs plus the `a11y` scan helper.
// - `webServer` boots the Vite dev server (`http://localhost:3000`, see
//   `vite.config.ts`) and reuses a running instance outside CI.
// - `projects` runs chromium locally for fast feedback. Firefox and WebKit
//   are CI-extended: add Desktop Firefox / Desktop Safari projects to run
//   cross-browser in CI (documented in frontend README).
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: 'html',
  use: {
    baseURL: 'http://localhost:3000',
    trace: 'on-first-retry',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:3000',
    reuseExistingServer: !process.env.CI,
    timeout: 120 * 1000,
  },
})
