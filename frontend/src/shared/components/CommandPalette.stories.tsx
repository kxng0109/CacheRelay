import type { Meta, StoryObj } from '@storybook/react-vite'
import { MemoryRouter } from 'react-router'
import { CommandPalette } from './CommandPalette.js'

/**
 * Global command palette; stories wrap it in a memory router because every
 * action navigates.
 *
 * @param story - Rendered story element.
 * @returns The story inside routing context.
 */
function withRouter(story: () => React.JSX.Element): React.JSX.Element {
  return <MemoryRouter>{story()}</MemoryRouter>
}

/**
 * Command palette closed state as it appears in the shell header.
 */
const meta: Meta<typeof CommandPalette> = {
  component: CommandPalette,
  title: 'Console/CommandPalette',
  decorators: [withRouter],
}

export default meta
type Story = StoryObj<typeof CommandPalette>

/**
 * Closed trigger button.
 */
export const Closed: Story = {}
