import { act, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { renderApp } from '../../test/utils.js'
import { RateLimitHeaders } from './RateLimitHeaders.js'

describe('RateLimitHeaders', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.runOnlyPendingTimers()
    vi.useRealTimers()
  })

  it('renders nothing without gateway headers', () => {
    const { container } = renderApp(
      <RateLimitHeaders
        snapshot={{ dimension: null, limit: null, remaining: null, reset: null, retryAfter: null }}
      />,
    )
    expect(container).toBeEmptyDOMElement()
  })

  it('surfaces limit values as tabular figures', () => {
    renderApp(
      <RateLimitHeaders
        snapshot={{ dimension: 'RPM', limit: 60, remaining: 59, reset: null, retryAfter: null }}
      />,
    )
    const status = screen.getByRole('status')
    expect(status).toHaveTextContent('Limit: 60')
    expect(status).toHaveTextContent('Remaining: 59')
  })

  it('captions the binding dimension words carry, not color alone', () => {
    const { unmount } = renderApp(
      <RateLimitHeaders
        snapshot={{
          dimension: 'TPM',
          limit: 100000,
          remaining: 99950,
          reset: null,
          retryAfter: null,
        }}
      />,
    )
    expect(screen.getByRole('status')).toHaveTextContent('Token quota')
    unmount()
    renderApp(
      <RateLimitHeaders
        snapshot={{ dimension: 'RPM', limit: 60, remaining: 59, reset: null, retryAfter: null }}
      />,
    )
    expect(screen.getByRole('status')).toHaveTextContent('Request quota')
  })

  it('counts down live from the reset epoch', () => {
    const reset = Math.floor(Date.now() / 1000) + 41
    renderApp(
      <RateLimitHeaders
        snapshot={{ dimension: 'RPM', limit: 60, remaining: 3, reset, retryAfter: null }}
      />,
    )
    expect(screen.getByRole('status')).toHaveTextContent('Resets in: 41s')
    act(() => {
      vi.advanceTimersByTime(1000)
    })
    expect(screen.getByRole('status')).toHaveTextContent('Resets in: 40s')
  })

  it('floors the countdown at zero once the window passes', () => {
    const reset = Math.floor(Date.now() / 1000) + 1
    renderApp(
      <RateLimitHeaders
        snapshot={{ dimension: 'RPM', limit: 60, remaining: 0, reset, retryAfter: 9 }}
      />,
    )
    act(() => {
      vi.advanceTimersByTime(5000)
    })
    expect(screen.getByRole('status')).toHaveTextContent('Resets in: 0s')
    expect(screen.getByRole('status')).toHaveTextContent('Retry after (s): 9')
  })

  it('renders an em-dash when the reset epoch is absent', () => {
    renderApp(
      <RateLimitHeaders
        snapshot={{ dimension: 'RPM', limit: 60, remaining: 59, reset: null, retryAfter: null }}
      />,
    )
    expect(screen.getByRole('status')).toHaveTextContent('Resets in: —')
  })
})
