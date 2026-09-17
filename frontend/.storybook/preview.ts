import type { Preview } from '@storybook/react-vite'
import '../src/index.css'

// Shared preview: every story renders inside the CacheRelay token baseline
// (`src/index.css` paper/ink/night/parchment theme plus tabular numerals),
// so component states always show production styling. `autodocs` keeps docs
// pages generated from stories without per-story configuration.
const preview: Preview = {
  parameters: {
    controls: {
      matchers: {
        color: /(background|color)$/i,
        date: /Date$/i,
      },
    },
  },
  tags: ['autodocs'],
}

export default preview
