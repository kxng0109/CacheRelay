# CacheRelay Frontend — Enterprise Console

Vite 8.3.0 + React 19.3.0 + TypeScript 6.0.2 + Tailwind CSS 4.3.3.
Twelve lazy routes mapped to real gateway surfaces (`backend/docs/BACKEND_API_REFERENCE.md`):
Overview, Playground (live SSE), Circuits, Keys, Ledger, Cache & budgets,
Embeddings, Approvals (HITL), MCP, Observability, Login, Redeem.
Product name resolves at runtime from `/v3/api-docs` `info.title`
(`CacheRelay AI Gateway & Resilient Reverse Proxy`); the static
`<title>CacheRelay</title>` in `index.html` is the offline fallback.

## Scripts

| Command                       | Purpose                                              |
| ----------------------------- | ---------------------------------------------------- |
| `npm.cmd run dev`             | Vite dev server (`http://localhost:5173`)            |
| `npm.cmd run typecheck`       | `tsc -b`, strict, zero errors (the real gate)        |
| `npm.cmd run build`           | `tsc -b` + `vite build` (production, sourcemaps off) |
| `npm.cmd run test`            | Vitest 5 unit run (jsdom)                            |
| `npm.cmd run test:coverage`   | Vitest v8 coverage, 95% gate on all metrics          |
| `npm.cmd run test:e2e`        | Playwright 1.63 smoke (`e2e/smoke.spec.ts`)          |
| `npm.cmd run storybook`       | Storybook 10.6.0 gallery on `:6006`                  |
| `npm.cmd run build-storybook` | Static Storybook build (`storybook-static/`)         |

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
- `build.assetsInlineLimit: 0` (no `data:` inlining). ECharts is set up
  tree-shaken (`shared/echarts/setup.ts`) but no screen charts yet — import it
  lazily per route when the first chart lands, never globally.
- Auth model: the gateway `gw-` key (user-pasted per form) and the human
  session (short-lived access JWT) live in JS memory only (zustand, never
  `localStorage`/cookies/IndexedDB). The refresh token lives in an httpOnly
  cookie the browser sends automatically; refresh calls carry the required
  `X-CacheRelay-Refresh: 1` CSRF marker (dev also needs the backend to
  allow-list it for CORS — flagged, prod same-origin is unaffected). The
  master secret has no UI path by design (terminal/curl-only). Route tiers:
  public (Playground, Embeddings, Login, Redeem), session
  (`RequireAuth`: Overview, MCP, Observability), admin (`RequireAdmin`
  stealth 404: Circuits, Keys, Ledger, Cache, Approvals). Guests hitting `/`
  land on `/login?next=<original>` and return after signing in; authed visits
  to `/login`/`/redeem` bounce home. Sidebar, palette, and G-chords all hide
  what the session may not see — no hints. See
  `backend/docs/BACKEND_API_REFERENCE.md` §1–§3.
- The Observability route renders a live latency centerpiece (`LatencyChart`,
  `React.lazy` + `Suspense` so the `echarts-vendor` chunk stays out of the
  initial bundle): same-origin `/actuator/prometheus` (no auth), 15s poll,
  P50/P95 from per-interval histogram deltas (never cumulative counters),
  theme follows the app toggle via v6 `setTheme`, container resizes via
  `ResizeObserver`, textual P50/P95/RPS summary for assistive tech.
- The Ledger route reads the real contract: `/v1/admin/ledger/entries` with
  the `PageResponse` envelope (`content` + `hasNext` drives paging),
  `costUsdMicros` per row, and summary cards (requests, billed µ$ via
  `totalCostUsdMicros`, avg duration via `averageDurationMs`). There is no
  `cacheHitRate` field — the backend never emitted one.
- The shell shows a live rate-limit strip below the header once a credential
  is present and a gateway response has been observed (`RateLimitHeaders`
  over `shared/ratelimit` memory-only state). One smart row shows the binding
  dimension (captioned request vs token quota): the backend-named 429
  `error.code` (`RPM_EXCEEDED`/`TPM_EXCEEDED`) wins, otherwise the
  most-constrained capped dimension leads (ties → RPM, `unlimited` never
  races). The reset cell counts down live from the reset epoch (1s interval
  only while a future reset shows); the snapshot clears on key switch and
  sign-out since quota is identity-bound. Wired via `RequestOptions.onHeaders`
  / process-wide `setHeadersReporter` (per-call wins) plus the SSE handshake
  hook. Dev cross-origin reads need backend `Access-Control-Expose-Headers`;
  prod is same-origin and unaffected.
- Keyboard-first shell: `Ctrl/⌘+K` palette (tier-filtered actions with a
  session-context footer), `G then <key>` chords (`O`verview `P`layground
  `E`mbeddings `B` observability `C`ircuits `K`eys `L`edger `A`pprovals `M`CP,
  never while typing, never beyond the session tier), `Ctrl/⌘+Enter` sends
  the playground prompt, `?` opens the shortcut sheet, `Esc` walks the
  ladder (dialog → drawer). Post-login focus lands on the screen heading.
- Playground runs as blocks: each submission renders a `run #N · model`
  section (Rerun resubmits as a fresh run) around the `SseStreamViewer`
  (`role="log"`, static `▍` caret while live, Copy with inline confirmation,
  Stop settles the budget hold). History rail reloads past prompts; new runs
  scroll into view instantly.
- Mutation feedback is toasts (`shared/toast` store + `Toasts` viewport):
  success `role="status"`, errors `role="alert"`, 4s auto-dismiss, `Esc`
  clears all, stack capped at five. Approvals decisions hold their buttons
  (`aria-busy`, `Working…`) and confirm with a UTC timestamp toast.
- Motion tokens (`--dur-micro/ui/panel/shimmer`, enter/exit easings) drive
  the only permitted flourish (card lift, toast entrance, caret blink,
  skeleton shimmer); keyboard-initiated actions stay instant and a
  `prefers-reduced-motion` switch kills every animation while keeping all
  state changes.

## Quality gates

| Command                       | Gate                                                         |
| ----------------------------- | ------------------------------------------------------------ |
| `npm.cmd run lint`            | ESLint 10 flat, zero warnings (`--max-warnings=0`)           |
| `npm.cmd run format:check`    | Prettier 3.9.7 exact, check only                             |
| `npm.cmd run typecheck`       | `tsc -b` (solution build; bare `--noEmit` is vacuous here)   |
| `npm.cmd run test`            | Vitest 5 unit run (jsdom)                                    |
| `npm.cmd run test:coverage`   | Vitest v8 coverage, 95% gate (currently 98.6/97.0/98.7/99.2) |
| `npm.cmd run test:e2e`        | Playwright 1.63 smoke, chromium, Vite dev reuse              |
| `npm.cmd run build-storybook` | Storybook 10.6.0 static build                                |

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
`npx lefthook install`. Windows note: the hooks shell out to `npx`/`npm`,
which resolve to blocked `.ps1` shims under PowerShell's default policy —
run hooks from Git Bash, or invoke the same commands with `npx.cmd`
directly. Pre-commit runs parallel under 10s on staged
files only: ESLint, Prettier check, and a pure-git secrets pickaxe
(`git diff --cached -G <pattern>` fails the hook when staged lines look
like keys/tokens). No gitleaks dependency; for stronger coverage install
gitleaks and run `gitleaks protect --staged` alongside. Pre-push runs
sequentially under 2min: `npm run typecheck` then `npm run test`.

### Unit tests (Vitest 5)

`vitest.config.ts`: `environment: jsdom`, `globals: true`,
`clearMocks: true`, `setupFiles: ./src/test/setup.ts`,
`include: src/**/*.test.{ts,tsx}`, coverage provider v8 with 95%
thresholds on statements/branches/functions/lines plus a `json-summary`
reporter for CI step summaries.
`src/test/setup.ts` loads `@testing-library/jest-dom/vitest`, stubs
`ResizeObserver`/`scrollIntoView` for jsdom, starts a shared MSW server
(`onUnhandledRequest: error`), resets handlers plus Testing Library
`cleanup()` after each test, and closes the server at the end.
`src/test/utils.tsx` renders UI with a fresh query client (no retries),
memory router, and seeded memory-only credentials.
30 suites / 363 tests: pure-unit (SSE parser, rate-limit parser/selector/store,
Prometheus histogram parser/quantiles, ECharts registration, app boot,
error mapping, URL allow-list, `?next=` validation, chord map, toast store)
plus MSW integration per screen (happy/error/empty/adversarial).

### E2E (Playwright 1.63)

`playwright.config.ts`: `testDir: ./e2e`, `webServer` boots
`npm run dev` at `http://localhost:5173` with
`reuseExistingServer: !process.env.CI`, `projects` runs chromium
(`Desktop Chrome`), firefox (`Desktop Firefox`), and webkit
(`Desktop Safari`) — locally all three, in CI one per matrix leg
(`--project=<browser>`, `fail-fast: false`). `e2e/a11y.ts` exports `scanForA11yViolations(page,
selector?)`, which injects the pinned `axe-core` bundle (no new
dependency) and fails on any violation.
`PLAYWRIGHT_CHANNEL=chrome` runs specs against the installed branded
browser (local escape hatch when the Playwright CDN is unreachable; CI
always uses the version-pinned bundled Chromium).
`e2e/smoke.spec.ts` walks the guest landing (login redirect), public
screens, the stealth admin gate, and the palette.
First run needs browsers:
`npx playwright install chromium` (`--with-deps` on CI).

### Storybook 10 and Chromatic

`.storybook/main.ts` (stories `../src/**/*.stories.*`, addons
`@chromatic-com/storybook` + docs, framework `@storybook/react-vite`,
`staticDirs: ../public`) and `.storybook/preview.ts` (imports
`src/index.css` tokens, `autodocs` tag, color/date control matchers).
Framework + docs packages are pinned exact (`10.6.0`).
Chromatic: `CHROMATIC_PROJECT_TOKEN` is env-only, never committed (CI
secret). TurboSnap via `onlyChanged`; the CI job checks the secret first
and skips green without it (never pass an empty token — the action treats
it as invalid, see codecov-action#1487).

## CI (`.github/workflows/ci-frontend.yml`) and release

Separate frontend workflow (backend `ci.yml` untouched): typecheck, lint,
coverage-gate test (+ Codecov when configured), static build artifact,
`npm audit` (any severity fails — it reads the same GitHub Advisory DB as
OSV.dev for npm, so no second scanner runs; see the workflow comment),
Playwright on Chromium, token-gated Chromatic.
All actions SHA-pinned (the repo's SHA-sweep covers the file); `act`
verifies locally with an empty env file because the repo-root Docker
`.env` (BOM) breaks act's dotenv parser:
`act -W .github/workflows/ci-frontend.yml --env-file <empty>`.
Release (`release.yml`, tag `v*`) additionally ships
`cacherelay-frontend-<tag>.tar.gz` + `frontend-bom.json` (anchore
Syft, CycloneDX) with SLSA attestations and checksums next to the jar.

## Tokens & a11y

`src/index.css` defines the `@theme` baseline (paper `#F7F5F0` / ink
`#16130E`, night `#0E0D0B` / parchment `#F5F1E8`, ember `#C7431F`, status
success `#2E7D32` / warn `#8A5E14` / danger `#C0392B` with `-soft` variants
for dark-mode text, muted `ink-soft`/`parchment-soft`, motion
`--dur-micro/ui/panel/shimmer` + enter/exit easings), `@custom-variant dark`,
`.tnum` tabular figures, `.lift` hover, `.stream-caret` blink,
`.skeleton` shimmer, `.toast-stack`/`.toast-enter`, a 3:1 `:focus-visible`
ring, 24px minimum pointer targets, and the `prefers-reduced-motion`
kill-switch. Every text/background pair holds WCAG 2.2 AA 4.5:1 in both
themes (verified by computation; the Playwright axe suite re-proves it on
every run). No global margin/padding reset.
