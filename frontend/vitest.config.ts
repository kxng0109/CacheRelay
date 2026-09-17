import { defineConfig } from 'vitest/config'

// CacheRelay frontend unit-test gate (Vitest 5, jsdom).
//
// - `environment: jsdom` gives component tests a browser-like DOM.
// - `globals: true` exposes describe/it/expect like Jest; Testing Library
//   relies on globals for auto-cleanup.
// - `clearMocks: true` resets mock history before each test.
// - Coverage uses the v8 provider with the 95% gate on all four metrics;
//   `npm run test:coverage` fails the build below the gate.
export default defineConfig({
  test: {
    environment: 'jsdom',
    globals: true,
    clearMocks: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'lcov', 'json-summary'],
      thresholds: {
        branches: 95,
        functions: 95,
        lines: 95,
        statements: 95,
      },
    },
  },
})
