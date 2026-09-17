import type { Meta, StoryObj } from '@storybook/react-vite'
import { McpPage } from './page.js'

/**
 * MCP catalog boundary: suspended upstream, zero fabricated tools.
 */
const meta: Meta<typeof McpPage> = {
  component: McpPage,
  title: 'Screens/McpPage',
}

export default meta
type Story = StoryObj<typeof McpPage>

/**
 * Default suspended state.
 */
export const Suspended: Story = {}
