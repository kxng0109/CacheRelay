import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll } from 'vitest'

// Shared MSW server for unit and integration tests. Individual test files add
// scenario handlers with `server.use(...)`; the hooks below keep scenarios
// isolated. Unhandled requests fail loudly so missing mocks surface at once.
export const server = setupServer()

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' })
})

afterEach(() => {
  server.resetHandlers()
  cleanup()
})

afterAll(() => {
  server.close()
})
