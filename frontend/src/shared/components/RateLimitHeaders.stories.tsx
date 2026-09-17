import type { Meta, StoryObj } from '@storybook/react-vite'
import { RateLimitHeaders } from './RateLimitHeaders.js'

/**
 * Rate-limit header strip in its populated and absent states.
 */
const meta: Meta<typeof RateLimitHeaders> = {
  component: RateLimitHeaders,
  title: 'Console/RateLimitHeaders',
}

export default meta
type Story = StoryObj<typeof RateLimitHeaders>

/**
 * Headers present on the last gateway response.
 */
export const Populated: Story = {
  args: {
    snapshot: { limit: 60, remaining: 59, reset: 12, retryAfter: null },
  },
}

/**
 * Gateway sent no rate-limit headers; renders nothing.
 */
export const Absent: Story = {
  args: {
    snapshot: { limit: null, remaining: null, reset: null, retryAfter: null },
  },
}
