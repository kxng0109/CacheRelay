import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { renderApp } from '../../test/utils.js'
import { EmptyTrio } from './EmptyTrio.js'

describe('EmptyTrio', () => {
  it('renders status, cue, and a navigation action', () => {
    renderApp(
      <EmptyTrio
        title="No entries yet"
        cue="Send traffic first."
        action={{ label: 'Open playground', to: '/playground' }}
      />,
    )
    expect(screen.getByRole('status')).toHaveTextContent('No entries yet')
    expect(screen.getByText('Send traffic first.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /open playground/i })).toHaveAttribute(
      'href',
      '/playground',
    )
  })

  it('renders button actions for in-place flows', async () => {
    const user = userEvent.setup()
    const onClick = vi.fn()
    renderApp(
      <EmptyTrio
        title="No runs yet"
        cue="Fill sample text."
        action={{ label: 'Fill sample text', onClick }}
      />,
    )
    await user.click(screen.getByRole('button', { name: /fill sample text/i }))
    expect(onClick).toHaveBeenCalledTimes(1)
  })

  it('renders status and cue alone when no action applies', () => {
    renderApp(<EmptyTrio title="No output yet" cue="Run a prompt first." />)
    expect(screen.getByRole('status')).toHaveTextContent('No output yet')
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })
})
