import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, vi } from 'vitest'

// jsdom lacks ResizeObserver (cmdk and charts rely on it) and
// Element.scrollIntoView (cmdk scrolls the active item into view). Minimal
// faithful stubs keep component tests honest without a real layout engine.
if (typeof ResizeObserver === 'undefined') {
  globalThis.ResizeObserver = class {
    private readonly targets = new Set<Element>()
    observe(target: Element): void {
      this.targets.add(target)
    }
    unobserve(target: Element): void {
      this.targets.delete(target)
    }
    disconnect(): void {
      this.targets.clear()
    }
  }
  vi.stubGlobal('ResizeObserver', globalThis.ResizeObserver)
}

// jsdom never implements scrolling, so patch unconditionally there but leave
// real browsers (vitest browser mode) untouched.
const isJsdom = typeof navigator !== 'undefined' && navigator.userAgent.includes('jsdom')
if (typeof Element !== 'undefined' && isJsdom) {
  Element.prototype.scrollIntoView = function scrollIntoView(this: Element): void {
    scrolledIntoView.push(this)
  }
}

/**
 * Elements the UI asked to scroll into view. jsdom has no layout engine, so
 * the stub records requests instead of scrolling; tests can assert focus
 * movement through this log.
 */
export const scrolledIntoView: Element[] = []

// Shared MSW server for unit and integration tests. Individual test files add
// scenario handlers with `server.use(...)`; the hooks below keep scenarios
// isolated. Unhandled requests fail loudly so missing mocks surface at once.
export const server = setupServer()

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' })
})

afterEach(() => {
  server.resetHandlers()
  scrolledIntoView.length = 0
  cleanup()
})

afterAll(() => {
  server.close()
})
