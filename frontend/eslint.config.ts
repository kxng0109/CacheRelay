import { defineConfig } from 'eslint/config'
import type { ESLint } from 'eslint'
import js from '@eslint/js'
import tseslint from 'typescript-eslint'
import reactHooks from 'eslint-plugin-react-hooks'
import tailwindcss from 'eslint-plugin-tailwindcss'
import prettier from 'eslint-config-prettier/flat'

// CacheRelay frontend quality gate (ESLint 10 flat config).
//
// Order matters: `eslint-config-prettier` stays LAST so it disables every
// stylistic rule that would fight Prettier. Keep `--max-warnings=0`
// (`npm run lint`) green: this config uses `error` severity for custom rules
// and keeps upstream presets at their shipped severities.
//
// TypeScript config loading: `eslint.config.ts` needs the `jiti` loader
// (present transitively at 2.7.0, Node 24 natively strips types).
// Type-aware rules use `projectService`; root tooling files outside any
// tsconfig (`*.config.ts`, `.storybook`, `e2e`) fall back to the default
// project and get `disableTypeChecked` so they lint without type info.
export default defineConfig([
  {
    name: 'cacherelay/ignores',
    ignores: [
      'node_modules/**',
      'dist/**',
      'coverage/**',
      'playwright-report/**',
      'test-results/**',
      'storybook-static/**',
      '.tmp-interact/**',
      '*.log',
    ],
  },
  {
    name: 'cacherelay/javascript',
    files: ['**/*.{js,cjs,mjs}'],
    extends: [js.configs.recommended],
  },
  {
    name: 'cacherelay/typescript',
    files: ['**/*.{ts,tsx,mts,cts}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.strict,
      tseslint.configs.strictTypeChecked,
      tseslint.configs.stylisticTypeChecked,
      reactHooks.configs.flat.recommended,
    ],
    languageOptions: {
      parserOptions: {
        projectService: {
          allowDefaultProject: ['*.config.ts', '.storybook/*.ts', 'e2e/*.ts'],
        },
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      // `verbatimModuleSyntax` makes type-only imports mandatory.
      '@typescript-eslint/consistent-type-imports': [
        'error',
        {
          disallowTypeAnnotations: true,
          fixStyle: 'separate-type-imports',
          prefer: 'type-imports',
        },
      ],
      'no-restricted-syntax': [
        'error',
        {
          message:
            'React.FC is deprecated. Declare props with an interface and annotate the return type instead.',
          selector:
            'TSTypeReference[typeName.type="TSQualifiedName"][typeName.left.name="React"][typeName.right.name="FC"]',
        },
        {
          message:
            'React.FC is deprecated. Declare props with an interface and annotate the return type instead.',
          selector: 'TSTypeReference[typeName.type="Identifier"][typeName.name="FC"]',
        },
        {
          message: 'forwardRef is deprecated in React 19. Accept ref as a normal prop instead.',
          selector: 'Identifier[name="forwardRef"]',
        },
        {
          message: 'React.FormEvent is deprecated. Use the DOM SubmitEvent type instead.',
          selector:
            'TSTypeReference[typeName.type="TSQualifiedName"][typeName.left.name="React"][typeName.right.name="FormEvent"]',
        },
        {
          message: 'FormEvent is deprecated. Use the DOM SubmitEvent type instead.',
          selector: 'TSTypeReference[typeName.type="Identifier"][typeName.name="FormEvent"]',
        },
        {
          message:
            'React.FormEventHandler is deprecated. Use the DOM SubmitEventHandler type instead.',
          selector:
            'TSTypeReference[typeName.type="TSQualifiedName"][typeName.left.name="React"][typeName.right.name="FormEventHandler"]',
        },
        {
          message: 'FormEventHandler is deprecated. Use the DOM SubmitEventHandler type instead.',
          selector: 'TSTypeReference[typeName.type="Identifier"][typeName.name="FormEventHandler"]',
        },
        {
          message:
            'dangerouslySetInnerHTML requires prior DOMPurify.sanitize() output. Document the sanitization at the call site.',
          selector: 'JSXAttribute[name.name="dangerouslySetInnerHTML"]',
        },
      ],
    },
  },
  {
    name: 'cacherelay/tailwindcss',
    // The plugin's own `recommended` preset is typed against an older
    // flat-config shape and no longer satisfies `exactOptionalPropertyTypes`,
    // so the rule set is declared explicitly (same rules, error severity to
    // match `--max-warnings=0`). The plugin object itself carries the same
    // stale `configs` typing, hence the compat cast below — runtime behavior
    // is unaffected (the plugin executes, only its types lag).
    plugins: { tailwindcss: tailwindcss as unknown as ESLint.Plugin },
    settings: {
      tailwindcss: { cssConfigPath: './src/index.css' },
    },
    rules: {
      // Custom one-off utilities (`.tnum`, `.skip-link`) live beside Tailwind
      // v4 tokens in `src/index.css`, and arbitrary values are used
      // deliberately — both stay allowed by design.
      'tailwindcss/classnames-order': 'error',
      'tailwindcss/enforces-canonical-classname': 'error',
      'tailwindcss/enforces-negative-arbitrary-values': 'error',
      'tailwindcss/enforces-shorthand': 'error',
      'tailwindcss/important-modifier-suffix': 'error',
      'tailwindcss/no-arbitrary-value': 'off',
      'tailwindcss/no-custom-classname': 'off',
      'tailwindcss/no-contradicting-classname': 'error',
      'tailwindcss/no-unnecessary-arbitrary-value': 'error',
    },
  },
  {
    name: 'cacherelay/tooling-config',
    files: ['*.config.ts', '.storybook/*.ts', 'e2e/*.ts'],
    extends: [tseslint.configs.disableTypeChecked],
  },
  // Prettier conflict killer. Must remain the final entry.
  prettier,
])
