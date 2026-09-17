import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { renderApp } from '../test/utils.js'
import { Layout } from './layout.js'

describe('Layout', () => {
  it('renders the product header, nav, and skip link', () => {
    renderApp(<Layout />)
    expect(screen.getByText('CacheRelay')).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: /primary/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /skip to content/i })).toHaveAttribute('href', '#main')
  })

  it('toggles the dark theme on the document root', async () => {
    const user = userEvent.setup()
    renderApp(<Layout />)
    const toggle = screen.getByRole('button', { name: /dark theme/i })
    await user.click(toggle)
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    await user.click(screen.getByRole('button', { name: /light theme/i }))
    expect(document.documentElement.classList.contains('dark')).toBe(false)
  })
})
