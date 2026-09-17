import type { StorybookConfig } from '@storybook/react-vite'

// CacheRelay component gallery (Storybook 10, ESM).
//
// Chromatic visual tests run through `@chromatic-com/storybook`.
const config: StorybookConfig = {
  stories: ['../src/**/*.mdx', '../src/**/*.stories.@(js|jsx|mjs|ts|tsx)'],
  addons: ['@chromatic-com/storybook', '@storybook/addon-docs'],
  framework: {
    name: '@storybook/react-vite',
    options: {},
  },
  staticDirs: ['../public'],
}

export default config
