import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderApp } from '../../test/utils.js'
import { RateLimitHeaders } from './RateLimitHeaders.js'

describe('RateLimitHeaders', () => {
  it('renders nothing without gateway headers', () => {
    const { container } = renderApp(
      <RateLimitHeaders
        snapshot={{ limit: null, remaining: null, reset: null, retryAfter: null }}
      />,
    )
    expect(container).toBeEmptyDOMElement()
  })

  it('surfaces limit values as tabular figures', () => {
    renderApp(
      <RateLimitHeaders snapshot={{ limit: 60, remaining: 59, reset: 12, retryAfter: null }} />,
    )
    const status = screen.getByRole('status')
    expect(status).toHaveTextContent('Limit: 60')
    expect(status).toHaveTextContent('Remaining: 59')
  })
})
