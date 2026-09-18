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
 * Headers present on the last gateway response. The reset epoch is null here
 * on purpose: a live countdown would drift between Chromatic captures, and
 * the ticking behavior is pinned by fake-timer unit tests instead.
 */
export const Populated: Story = {
  args: {
    snapshot: { dimension: 'RPM', limit: 60, remaining: 59, reset: null, retryAfter: null },
  },
}

/**
 * Gateway sent no rate-limit headers; renders nothing.
 */
export const Absent: Story = {
  args: {
    snapshot: { dimension: null, limit: null, remaining: null, reset: null, retryAfter: null },
  },
}
