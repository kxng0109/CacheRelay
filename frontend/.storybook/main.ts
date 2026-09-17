import type { StorybookConfig } from '@storybook/react-vite'

// CacheRelay component gallery (Storybook 10, ESM).
//
// Requires the `@storybook/react-vite` framework package plus docs support
// (`@storybook/addon-docs`); neither is installed yet, so `storybook dev`
// cannot run until the manual install step in frontend README is done.
// This file is written against that framework API so no change is needed
// after installing. Chromatic visual tests run through
// `@chromatic-com/storybook` (installed).
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
