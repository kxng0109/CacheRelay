import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderApp } from '../../test/utils.js'
import { McpPage } from './page.js'

describe('McpPage', () => {
  it('declares the suspension honestly and performs no live call', () => {
    renderApp(<McpPage />)
    expect(screen.getByText(/suspended upstream/i)).toBeInTheDocument()
    expect(screen.getByText(/will not invent tools/i)).toBeInTheDocument()
  })
})
