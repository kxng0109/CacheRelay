import { defineConfig } from 'eslint/config'
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
    extends: [tailwindcss.configs.recommended],
    settings: {
      tailwindcss: { cssConfigPath: './src/index.css' },
    },
    rules: {
      // The scaffold keeps plain CSS (App.css) beside Tailwind v4 tokens,
      // so custom class names are intentional and stay allowed.
      'tailwindcss/no-custom-classname': 'off',
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
