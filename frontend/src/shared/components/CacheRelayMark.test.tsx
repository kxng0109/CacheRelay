import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { CacheRelayMark } from './CacheRelayMark.js'

describe('CacheRelayMark', () => {
  it('renders the mark with an accessible label at the requested size', () => {
    render(<CacheRelayMark size={24} />)
    const mark = screen.getByRole('img', { name: /cacherelay/i })
    expect(mark).toHaveAttribute('width', '24')
    expect(mark).toHaveAttribute('viewBox', '0 0 32 32')
  })
})
