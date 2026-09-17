# CacheRelay Frontend — Core Config

Vite 8.3.0 + React 19.3.0 + TypeScript 6.0.2 + Tailwind CSS 4.3.3.
Product name resolves at runtime from `/v3/api-docs` `info.title`
(`CacheRelay AI Gateway & Resilient Reverse Proxy`); the static
`<title>CacheRelay</title>` in `index.html` is the offline fallback.

## Scripts

| Command                 | Purpose                                              |
| ----------------------- | ---------------------------------------------------- |
| `npm.cmd run dev`       | Vite dev server (`http://localhost:3000`)            |
| `npm.cmd run typecheck` | `tsc --noEmit`, strict, zero errors                  |
| `npm.cmd run build`     | `tsc -b` + `vite build` (production, sourcemaps off) |

## Env (non-secret only — `VITE_*` is bundle-inlined)

| Var                      | Default                 | Meaning                                                              |
| ------------------------ | ----------------------- | -------------------------------------------------------------------- |
| `VITE_API_BASE_URL`      | `http://localhost:8080` | Gateway base URL (contract: `backend/docs/BACKEND_API_REFERENCE.md`) |
| `VITE_FEATURE_STREAMING` | `true`                  | SSE streaming paths (`data: <json>`, terminal `data: [DONE]`)        |

Copy `.env.example` to `.env.local` (gitignored) for local overrides.
Never commit tokens, keys, or credentials.

## Security notes

- CSP nonces are per-response: the edge replaces `CSP_NONCE_PLACEHOLDER`
  (`html.cspNonce`) and `__CSP_NONCE__` (script-tag placeholder) with a
  random value per request and serves the matching `Content-Security-Policy`
  header. Never serve `dist/index.html` with placeholders intact.
- `build.assetsInlineLimit: 0` (no `data:` inlining); ECharts ships only in
  the lazy `echarts-vendor` chunk — never import it globally.
- Access JWT lives in JS memory only; refresh travels in an `httpOnly`
  cookie. See `backend/docs/BACKEND_API_REFERENCE.md` §1–§3 for auth surfaces.

## Quality gates

| Command                     | Gate                                                          |
| --------------------------- | ------------------------------------------------------------- |
| `npm.cmd run lint`          | ESLint 10 flat, zero warnings (`--max-warnings=0`)            |
| `npm.cmd run format:check`  | Prettier 3.9.7 exact, check only                              |
| `npm.cmd run typecheck`     | `tsc --noEmit`, strict, zero errors                           |
| `npm.cmd run test`          | Vitest 5 unit run (jsdom)                                     |
| `npm.cmd run test:coverage` | Vitest v8 coverage, 95% gate on all metrics                   |
| `npm.cmd run test:e2e`      | Playwright 1.63, chromium, Vite dev reuse                     |
| `npm.cmd run storybook`     | Storybook 10 gallery on `:6006` (needs manual install, below) |

### ESLint

`eslint.config.ts` (flat `defineConfig` from `eslint/config`): `@eslint/js`
recommended + `typescript-eslint` strict + strictTypeChecked +
stylisticTypeChecked + `eslint-plugin-react-hooks` flat recommended +
`eslint-plugin-tailwindcss` (settings `cssConfigPath: ./src/index.css`)

- `eslint-config-prettier/flat` LAST. Bans via `no-restricted-syntax`:
  `React.FC`, `forwardRef`, `FormEvent`/`FormEventHandler` (use
  `SubmitEvent`), and `dangerouslySetInnerHTML` without prior
  `DOMPurify.sanitize()` (document the sanitization at the call site).
  `consistent-type-imports` enforces `import type`.
  `tailwindcss/no-custom-classname` is off: the scaffold keeps plain
  `App.css` beside Tailwind tokens. The `.ts` config loads via `jiti`
  (present transitively at 2.7.0; Node 24 strips types natively).

### Prettier and editor

`.prettierrc`: `semi: false`, `singleQuote: true`, `printWidth: 100`,
`trailingComma: all`, `endOfLine: lf` (mirrors scaffold style and
`.editorconfig`). `.prettierignore` excludes build/coverage/report dirs.
`.vscode/settings.json` sets Prettier as default formatter with
`formatOnSave`, and ESLint `source.fixAll.eslint: explicit` on save
(auto-fix only, no other save actions). Recommended extensions live in
`.vscode/extensions.json`. Note: repo `.gitignore` tracks only
`.vscode/extensions.json`, so `settings.json` is local-only.

### Git hooks (lefthook 2)

`lefthook.yml` (`min_version: 2.1.12`). Install once:
`npx lefthook install`. Pre-commit runs parallel under 10s on staged
files only: ESLint, Prettier check, and a pure-git secrets pickaxe
(`git diff --cached -G <pattern>` fails the hook when staged lines look
like keys/tokens). No gitleaks dependency; for stronger coverage install
gitleaks and run `gitleaks protect --staged` alongside. Pre-push runs
sequentially under 2min: `npm run typecheck` then `npm run test`.

### Unit tests (Vitest 5)

`vitest.config.ts`: `environment: jsdom`, `globals: true`,
`clearMocks: true`, `setupFiles: ./src/test/setup.ts`,
`include: src/**/*.test.{ts,tsx}`, coverage provider v8 with 95%
thresholds on statements/branches/functions/lines.
`src/test/setup.ts` loads `@testing-library/jest-dom/vitest`, starts a
shared MSW server (`onUnhandledRequest: error`), resets handlers plus
Testing Library `cleanup()` after each test, and closes the server at
the end. No feature suites exist yet, so `vitest run` reports no test
files until the first `*.test.{ts,tsx}` lands.

### E2E (Playwright 1.63)

`playwright.config.ts`: `testDir: ./e2e`, `webServer` boots
`npm run dev` at `http://localhost:3000` with
`reuseExistingServer: !process.env.CI`, `projects` runs chromium
(`Desktop Chrome`) only for fast local feedback. Firefox/WebKit are
CI-extended: add `Desktop Firefox` / `Desktop Safari` projects to run
cross-browser in CI. `e2e/a11y.ts` exports `scanForA11yViolations(page,
selector?)`, which injects the pinned `axe-core` bundle (no new
dependency) and fails on any violation. First run needs browsers:
`npx playwright install chromium` (`--with-deps` on CI).

### Storybook 10 and Chromatic

`.storybook/main.ts` (stories `../src/**/*.stories.*`, addons
`@chromatic-com/storybook` + docs, framework `@storybook/react-vite`,
`staticDirs: ../public`) and `.storybook/preview.ts` (imports
`src/index.css` tokens, `autodocs` tag, color/date control matchers).
MANUAL STEP (deps intentionally not added): run
`npm install --save-dev @storybook/react-vite @storybook/addon-docs`
and re-resolve exact versions before installing; `storybook dev` cannot
run until then, but lint/format/vitest stay green without it.
Chromatic: `CHROMATIC_PROJECT_TOKEN` is env-only, never committed (CI
secret). TurboSnap is on by default for Storybook projects (only
changed stories snapshot). CI must skip when the token is absent, e.g.
guard the step with `if: env.CHROMATIC_PROJECT_TOKEN != ''` so forks
without the secret stay green.

## Tokens & a11y

`src/index.css` defines the `@theme` baseline (paper `#F7F5F0` / ink
`#16130E`, night `#0E0D0B` / parchment `#F5F1E8`, ember `#E4572E`, cyan
`#2AA198`, success / warn / danger / slate info), `@custom-variant dark`,
`.tnum` / `.operational-nums` tabular figures, a 3:1 `:focus-visible` ring,
and 24px minimum pointer targets. No global margin/padding reset.
