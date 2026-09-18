import { defineConfig, devices } from '@playwright/test'

// CacheRelay E2E gate (Playwright 1.63).
//
// - `testDir: ./e2e` holds critical-path specs plus the `a11y` scan helper.
// - `webServer` boots the Vite dev server (`http://localhost:5173`, see
//   `vite.config.ts`) and reuses a running instance outside CI.
// - `projects` runs one browser per CI matrix leg (chromium, firefox,
//   webkit) via `--project=<browser>`; locally `npx playwright test`
//   runs all three. Firefox and WebKit are first-class, not CI-extended.
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  // Single worker on CI for deterministic runs; local default otherwise.
  ...(process.env.CI === undefined ? {} : { workers: 1 }),
  reporter: 'html',
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'on-first-retry',
    // Local-only escape hatch: PLAYWRIGHT_CHANNEL=chrome runs specs against
    // the installed branded browser when the Playwright CDN is unreachable.
    // CI never sets it and uses the version-pinned bundled Chromium instead.
    ...(process.env.PLAYWRIGHT_CHANNEL === undefined
      ? {}
      : { channel: process.env.PLAYWRIGHT_CHANNEL }),
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'firefox',
      use: { ...devices['Desktop Firefox'] },
    },
    {
      name: 'webkit',
      use: { ...devices['Desktop Safari'] },
    },
  ],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 120 * 1000,
  },
})
