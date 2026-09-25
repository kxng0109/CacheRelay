import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderApp } from '../../test/utils.js'
import { SafeOutboundLink } from './SafeOutboundLink.js'

describe('SafeOutboundLink', () => {
  it('renders safe destinations as outbound anchors', () => {
    renderApp(<SafeOutboundLink href="https://ops.example.com/hook">Open</SafeOutboundLink>)
    const link = screen.getByRole('link', { name: /open/i })
    expect(link).toHaveAttribute('href', 'https://ops.example.com/hook')
    expect(link).toHaveAttribute('rel', 'noreferrer')
  })

  it('withholds javascript and data destinations instead of linking', () => {
    renderApp(<SafeOutboundLink href="javascript:alert(1)">Open</SafeOutboundLink>)
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.getByRole('note')).toHaveTextContent(/unsafe destination/i)
  })
})
