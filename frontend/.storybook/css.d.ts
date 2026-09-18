/**
 * CSS side-effect imports in tooling scope (Storybook preview). The app
 * scope gets `*.css` declarations from `vite/client` via `src/vite-env.d.ts`;
 * this project-local declaration covers `.storybook/*.ts`, which
 * `tsconfig.node.json` compiles separately.
 */
declare module '*.css'
