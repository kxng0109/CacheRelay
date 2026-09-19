import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { renderApp } from '../../test/utils.js'
import { CommandPalette } from './CommandPalette.js'

describe('CommandPalette', () => {
  it('opens the menu and navigates to a screen', async () => {
    const user = userEvent.setup()
    renderApp(<CommandPalette />, { adminSession: true })
    await user.click(screen.getByRole('button', { name: /commands/i }))
    await user.click(await screen.findByText('Go to Circuits'))
    await waitFor(() => {
      expect(screen.queryByText('Go to Circuits')).not.toBeInTheDocument()
    })
  })

  it('runs caller-supplied actions', async () => {
    const user = userEvent.setup()
    let ran = false
    renderApp(
      <CommandPalette
        actions={[
          {
            id: 'x',
            label: 'Purge everything',
            run: () => {
              ran = true
            },
          },
        ]}
      />,
    )
    await user.click(screen.getByRole('button', { name: /commands/i }))
    await user.click(await screen.findByText('Purge everything'))
    expect(ran).toBe(true)
  })

  it('toggles with Ctrl+K', async () => {
    renderApp(<CommandPalette />)
    fireEvent.keyDown(document, { key: 'k', ctrlKey: true })
    await screen.findByText('Go to Playground')
    fireEvent.keyDown(document, { key: 'k', ctrlKey: true })
    await waitFor(() => {
      expect(screen.queryByText('Go to Playground')).not.toBeInTheDocument()
    })
  })

  it('ignores unrelated keys', () => {
    renderApp(<CommandPalette />)
    fireEvent.keyDown(document, { key: 'x', ctrlKey: true })
    expect(screen.queryByText('Go to Playground')).not.toBeInTheDocument()
  })

  it('navigates through every section action', async () => {
    const user = userEvent.setup()
    const labels = [
      'Go to Overview',
      'Go to Playground',
      'Go to Circuits',
      'Go to Keys',
      'Go to Ledger',
      'Go to Cache and budgets',
      'Go to Embeddings',
      'Go to Approvals',
      'Go to MCP',
      'Go to Observability',
    ]
    renderApp(<CommandPalette />, { adminSession: true })
    for (const label of labels) {
      await user.click(screen.getByRole('button', { name: /commands/i }))
      await user.click(await screen.findByText(label))
      await waitFor(() => {
        expect(screen.queryByText(label)).not.toBeInTheDocument()
      })
    }
  })

  it('hides admin actions from non-admins without a hint', async () => {
    const user = userEvent.setup()
    renderApp(<CommandPalette />)
    await user.click(screen.getByRole('button', { name: /commands/i }))
    await user.keyboard('Go to ')
    await waitFor(() => {
      expect(screen.getByText('Go to Playground')).toBeInTheDocument()
    })
    expect(screen.queryByText('Go to Circuits')).not.toBeInTheDocument()
    expect(screen.queryByText('Go to Approvals')).not.toBeInTheDocument()
  })

  it('shows admin actions for admin sessions', async () => {
    const user = userEvent.setup()
    renderApp(<CommandPalette />, { adminSession: true })
    await user.click(screen.getByRole('button', { name: /commands/i }))
    await user.keyboard('Go to ')
    await waitFor(() => {
      expect(screen.getByText('Go to Circuits')).toBeInTheDocument()
    })
  })

  it('renders caller hints beside actions', async () => {
    const user = userEvent.setup()
    let ran = false
    renderApp(
      <CommandPalette
        actions={[
          {
            id: 'h',
            label: 'Reset circuit',
            hint: 'openai',
            run: () => {
              ran = true
            },
          },
        ]}
      />,
    )
    await user.click(screen.getByRole('button', { name: /commands/i }))
    expect(await screen.findByText('openai')).toBeInTheDocument()
    expect(ran).toBe(false)
  })
})
