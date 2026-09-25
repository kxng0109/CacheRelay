# CacheRelay Frontend — Enterprise Console

Vite 8.3.1 + React 19.3.0 + TypeScript 6.0.2 + Tailwind CSS 4.3.3.
Sixteen lazy routes mapped to real gateway surfaces (`backend/docs/BACKEND_API_REFERENCE.md`):
Overview, Usage (personal dashboard), Teams, Playground (live SSE), Circuits, Keys,
Ledger (+ admin user drill-down `/ledger/user/:userId`), Cache & budgets,
Embeddings, Approvals (HITL), MCP, Observability, Login (password + SSO),
Redeem. Product name resolves at runtime from `/v3/api-docs` `info.title`
(`CacheRelay AI Gateway & Resilient Reverse Proxy`); the static
`<title>CacheRelay</title>` in `index.html` is the offline fallback.

## Scripts

| Command                       | Purpose                                                                                                                                                                                                                                                      |
| ----------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `npm.cmd run dev`             | Vite dev server (`http://localhost:5173`)                                                                                                                                                                                                                    |
| `npm.cmd run typecheck`       | `tsc -b`, strict, zero errors (the real gate)                                                                                                                                                                                                                |
| `npm.cmd run build`           | `tsc -b` + `vite build` (production, sourcemaps off)                                                                                                                                                                                                         |
| `npm.cmd run test`            | Vitest 5 unit run (jsdom)                                                                                                                                                                                                                                    |
| `npm.cmd run test:coverage`   | Vitest v8 coverage, 95% gate on all metrics                                                                                                                                                                                                                  |
| `npm.cmd run test:e2e`        | Playwright 1.63 smoke (`e2e/smoke.spec.ts`)                                                                                                                                                                                                                  |
| `npm.cmd run size`            | Production build + size-limit gate, gzip (`"gzip": true` per entry — the plugin defaults to brotli-11): entry JS ≤160 kB, shared preload chunk ≤40 kB, CSS ≤50 kB; the JS entries sum to the 150–200 KB first-load gzip budget (currently ≈133.3 + ≈31.6 kB) |
| `npm.cmd run storybook`       | Storybook 10.6.0 gallery on `:6006`                                                                                                                                                                                                                          |
| `npm.cmd run build-storybook` | Static Storybook build (`storybook-static/`)                                                                                                                                                                                                                 |

## Env (non-secret only — `VITE_*` is bundle-inlined)

| Var                        | Default                 | Meaning                                                                                                                   |
| -------------------------- | ----------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| `VITE_API_BASE_URL`        | `http://localhost:8080` | Gateway base URL (contract: `backend/docs/BACKEND_API_REFERENCE.md`)                                                      |
| `VITE_MANAGEMENT_BASE_URL` | `http://localhost:9091` | Actuator base URL (SEC-15 management port; loopback pages default here when unset, other hosts fall back to same-origin)  |
| `VITE_FEATURE_STREAMING`   | `true`                  | SSE streaming paths (`data: <json>`, terminal `data: [DONE]`)                                                             |
| `VITE_SSO_PROVIDERS`       | _(empty)_               | SSO providers (comma-separated Spring registration ids, e.g. `google,github`); empty greys SSO out with "SSO not enabled" |

Copy `.env.example` to `.env.local` (gitignored) for local overrides.
Never commit tokens, keys, or credentials.

## Security notes

- CSP nonces are per-response: the edge replaces `CSP_NONCE_PLACEHOLDER`
  (`html.cspNonce`) and `__CSP_NONCE__` (script-tag placeholder) with a
  random value per request and serves the matching `Content-Security-Policy`
  header. Never serve `dist/index.html` with placeholders intact.
- `build.assetsInlineLimit: 0` (no `data:` inlining). ECharts is
  tree-shaken (`shared/echarts/setup.ts`) and lands per route only: the
  Observability latency chart (`LatencyChart`, `React.lazy` + `Suspense`)
  keeps the `echarts-vendor` chunk out of the initial bundle, never global.
- Auth model: the gateway `gw-` key (user-pasted per form) and the human
  session (short-lived access JWT) live in JS memory only (zustand, never
  `localStorage`/cookies/IndexedDB). The refresh token lives in an httpOnly
  cookie the browser sends automatically; refresh calls carry the required
  `X-CacheRelay-Refresh: 1` CSRF marker (dev also needs the backend to
  allow-list it for CORS — flagged, prod same-origin is unaffected). The
  master secret has no UI path by design (terminal/curl-only). Route tiers:
  public (Playground, Embeddings, MCP, Login, Redeem), session
  (`RequireAuth`: Overview, Usage, Teams, Observability), admin
  (`RequireAdmin` stealth 404: Circuits, Keys, Ledger, drill-down, Cache,
  Approvals). The MCP catalog is paste-key first like the Run screens, so
  guests may open it; pasted sends ignore the session so a logged-in paste
  never 401s. Guests hitting `/` land on `/login?next=<original>` and return
  after signing in; authed visits to `/login`/`/redeem` bounce home. Sidebar,
  palette, and G-chords all hide what the session may not see — no hints. See
  `backend/docs/BACKEND_API_REFERENCE.md` §1–§3.
- Login offers username/password plus SSO entry buttons driven by the
  non-secret `VITE_SSO_PROVIDERS` allow-list (unconfigured SSO renders
  greyed-out with "SSO not enabled", never a dead link). SSO success lands
  on `/?sso=1#access_token=…&admin=…`; the shell intercepts the landing
  before guards can bounce it, completes identity via `GET /v1/auth/me`,
  moves the token to memory, and clears the fragment immediately (first
  logins show a pending state with 30s timeout copy since IdP backfill
  blocks). 401/403 at completion names IdP disablement instead of looping.
- The Observability route renders a live latency centerpiece (`LatencyChart`,
  `React.lazy` + `Suspense` so the `echarts-vendor` chunk stays out of the
  initial bundle): management-port `/actuator/prometheus` (no auth, 15s poll,
  `VITE_MANAGEMENT_BASE_URL` with a loopback `:9091` default when unset),
  P50/P95 from per-interval histogram deltas (never cumulative counters),
  theme follows the app toggle via v6 `setTheme`, container resizes via
  `ResizeObserver`, textual P50/P95/RPS summary for assistive tech. Both
  probes validate content types before claiming health, so a wrong base URL
  names itself (`not JSON` / `not Prometheus text`) instead of leaking
  parser errors or a false `scrape ok`.
- The Ledger route reads the real contract: `/v1/admin/ledger/entries` with
  the `PageResponse` envelope (`content` + `hasNext` drives paging),
  `costUsdMicros` per row, and summary cards (requests, billed µ$ via
  `totalCostUsdMicros`, avg duration via `averageDurationMs`). There is no
  `cacheHitRate` field — the backend never emitted one. Rows open a shared
  overlay inspector drawer (420px, backdrop + `Esc` dismiss, focus return):
  model/provider/cached chips, Cost/Duration/Tokens/derived-throughput
  cards, sectioned receipt facts, single-receipt hydration
  (`GET /v1/admin/ledger/entries/{id}`, gone receipts read as unavailable),
  receipt + markdown copies, raw JSON, and prev/next walk. The table adds
  short dates, grouped micro-costs, filter match counts with clear, and
  jump-to-page. Every table in the console (Circuits, Keys, Models,
  Embeddings, MCP, Cache budgets) shares the same drawer shell.
- Usage dashboards compute on open, nothing precomputes: `/v1/me/usage`
  (owner derived server-side from the session, trailing 7d default, 90d max)
  and the admin drill-down `/v1/admin/ledger/user/{userId}/summary`
  (audit-logged, admin-gated client-side too) share one board — freshness
  from `X-Dashboard-Generated-At` ("updated Xs ago"), `byOwner`/`byModel`/
  `byProvider` breakdowns, empty windows render "no usage in range" (never
  an error), 400s carry narrow-the-window guidance, headerless 429s back
  off client-side (retry ≤2, 1s/2s/8s-cap). Stealth 404s render a single
  "admin unavailable" screen that never distinguishes no-access from
  no-route and never retry-loops.
- Teams are boundary proof, member lists only (no team-usage route exists
  yet): `/v1/me/teams` (ACTIVE only, `[]` is normal) plus the admin org
  picker (`GET /v1/admin/teams?org=`, 404 names unknown orgs). Sudden
  cross-key 401s name IdP disablement ("contact your admin") instead of
  "re-login and retry".
- Every collection screen shares one empty trio (`shared/components/
EmptyTrio.tsx`: status line + learning cue + optional link/button
  action, identical container/type/action chrome). Probe failures
  distinguish answered HTTP errors (red, e.g. `failed: HTTP 503`) from
  unreachable endpoints (muted + retry — dev CORS gaps and real outages
  look identical from here), and the header chip reads `probes:up/down`
  since actuator reachability alone never proves gateway liveness. The
  Models table drops its Actions column when no database-managed row is
  visible; file-bound cells read muted `read-only` instead of dashes.
- Shared number/date formatting (`shared/utils/format.ts`, Intl-only):
  compact counts (`6.7K`), byte buckets (`256 MB`), significant-decimal
  dollars (`$4.525`, `$0.00`), grouped micro-costs (`1,184µ$`), short
  local dates with full-ISO hover titles. Measured zeros render as words
  (`free`, `no cap`); unknowns render as `n/a`.
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
  `E`mbeddings `U`sage `T`eams `B` observability `C`ircuits `K`eys `L`edger
  `A`pprovals `M`CP, never while typing, never beyond the session tier), `Ctrl/⌘+Enter` sends
  the playground prompt, `?` opens the shortcut sheet, `Esc` walks the
  ladder (dialog → drawer). Post-login focus lands on the screen heading.
- Playground runs as blocks: each submission renders a `run #N · model`
  section (Rerun resubmits as a fresh run) around the `SseStreamViewer`
  (`role="log"`, static `▍` caret while live, Copy with inline confirmation,
  Stop settles the budget hold). History rail reloads past prompts; new runs
  scroll into view instantly. Sessions choose per run between an owned
  account key (picked by name, never displayed, sent as `X-Act-As-Key`
  with the session JWT) and a pasted key (memory only); guests paste.
  Pasted sends ignore the session so a logged-in paste never 401s.
  Embeddings offers the same two key sources.
- Every dropdown is the shared listbox (`shared/components/Select.tsx`):
  input-matching chrome, ember chevron (native arrows render OS chrome
  that breaks both themes), flat hairline menu, full keyboard contract
  (open/move/pick/close, Esc, click-outside, focus return), ARIA
  `listbox` semantics. The menu portals to the body so dialog scroll
  never clips it, flips upward near viewport edges, and dismisses on
  outside scroll while menu-internal scroll keeps it open. Console
  scrollbars are thin themed thumbs on transparent tracks in both
  canvases. No native `<select>` remains in the console.
- Model aliases are administered against the live provider inventory
  (`GET /v1/admin/providers` envelope): chain steps pick providers from
  the shared listbox with circuit state in each label, never free text.
  Loading, failure, or empty inventory disables the dropdowns and blocks
  Create alias plus Replace plan; failure carries Retry. A stored step
  whose provider retired stays visible as `name (removed)` until picked
  again. Model catalog failures name the cause: credential rejections
  (401) point at Reload or pasting a key, anything else stays muted.
- Usage windows use a preset picker defaulting to the past 7 days
  (Today, Yesterday, Past 3/7/30/90 days, This month, Custom range);
  presets apply immediately, custom dates validate before applying.
- Mutation feedback is toasts (`shared/toast` store + `Toasts` viewport):
  success `role="status"`, errors `role="alert"`, 4s auto-dismiss, `Esc`
  clears all, stack capped at five. Approvals decisions hold their buttons
  (`aria-busy`, `Working…`) and confirm with a UTC timestamp toast.
- Motion tokens (`--dur-micro/ui/panel/shimmer`, enter/exit easings) drive
  the only permitted flourish (card lift, toast entrance, inspector-drawer
  slide-fade, caret blink, skeleton shimmer); keyboard-initiated actions
  stay instant and a `prefers-reduced-motion` switch kills every animation
  while keeping all state changes. Motion orients only — state is never
  conveyed by animation.

## Audit remediation (FE-01…FE-40, frontend halves)

- Transport validates, never casts: zod schemas at the client boundary
  degrade drifted read payloads to empty states with a muted drift notice
  (`shared/drift` store + shell notice) instead of blanking the console;
  drifted mutation bodies (keys, aliases, circuits, budgets, cache,
  completions) reject with a safe "changed shape" error through the
  caller's existing error UI — mutations have no honest empty, so they
  never degrade silently and never fabricate records. A route-level error
  boundary (`RouteError`) with reload covers render throws. Bare 204s
  read as empty, never undefined.
- Streams bill once: one `Idempotency-Key` per run reused across retries,
  retries only before the first frame, terminal `incomplete` phase with a
  manual Retry (fresh key) afterwards, visible retry count, 2 MB transcript
  cap with truncation notice, CRLF/CR framing, 1 MiB frame and 8 MiB stream
  budgets, and handshake retries only for 408/429/5xx and network errors.
  Pressing Stop settles neutrally (`stopped`, "Stopped by user" status —
  never red, never an error); genuine failures still alert.
- Credentials stay scoped: MCP and paste-mode catalogs send `ignoreSession`
  with the pasted key; query keys carry a non-secret key fingerprint so key
  switches refetch; no credential means no `Authorization` header at all.
  Prototypes cannot smuggle through chord or circuit lookups (`Map` /
  `Object.hasOwn`); refresh bodies re-validate `admin` and keep (never
  invent) the username; `?next=` rejects control characters and resolves
  against the origin, then guards the returned string itself (dot-segment
  shortening can yield a `//host` pathname while staying same-origin);
  SSO fragments scrub before any network call; curl
  snippets build from `JSON.stringify` plus shell quoting against the
  configured base; budget webhooks require `https:` with non-local hosts.
- Overlays share one focus contract (`useOverlayFocus`, all portalled):
  origin capture, initial focus, Tab trap, background `inert`, focus return
  on close start; each overlay tracks exactly the elements it marked, so
  stacked overlays restore only their own marks. Inspector drawers are real dialogs; table rows are static
  with named Inspect controls; every table scrolls in its own region and
  stat grids collapse below `sm`. Toasts clear on global Esc with per-toast
  timers; the rate strip and live eyebrow are plain text, never live
  regions; forms link errors via `aria-describedby`; routes set titles and
  move focus on navigation; the Select ticks the value (with type-ahead)
  and stops Esc at an open menu.
- One Prometheus scrape (`useScrape`, 8 MB byte budget, incremental line
  scan) feeds pulse, chart, and probe via `select`. A CSP-violation
  listener toasts each distinct block once (the `connect-src` decision
  itself is backend-owned). Dynamic links render through
  `SafeOutboundLink`; the env types declare `VITE_SSO_PROVIDERS` with
  strict import-meta env. Dependencies track the audited pins
  (react-router 8 and TypeScript 7 stay separate migrations).

## Quality gates

| Command                       | Gate                                                         |
| ----------------------------- | ------------------------------------------------------------ |
| `npm.cmd run lint`            | ESLint 10 flat, zero warnings (`--max-warnings=0`)           |
| `npm.cmd run format:check`    | Prettier 3.9.9 exact, check only                             |
| `npm.cmd run typecheck`       | `tsc -b` (solution build; bare `--noEmit` is vacuous here)   |
| `npm.cmd run test`            | Vitest 5 unit run (jsdom)                                    |
| `npm.cmd run test:coverage`   | Vitest v8 coverage, 95% gate (currently 97.0/96.1/96.0/97.5) |
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
67 suites / 764 tests: pure-unit (formatters, SSE parser, rate-limit parser/selector/store,
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
dependency) and fails on any violation **plus** any unreviewed `incomplete`
result. The only reviewed incomplete is `color-contrast/nonBmp` (aria-hidden
decoration glyphs; real text contrast is pinned by the flattened-pair unit
test) — anything else fails until a human allow-lists it with a reason.
`e2e/a11y.spec.ts` seeds a DEV-only in-memory session per tier
(`/__test/session/<role>`, eliminated from production builds, and the boot
restore path is exempt so it never wipes the seed) and asserts the target
`h1` before scanning, so the gate can never pass by scanning the login
page again.
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
`npm audit` (any severity fails — npm audit and OSV.dev draw on the same
underlying advisory data for npm packages, namely the GitHub Advisory
Database, so a second scanner would re-check the same source rather than
add coverage; see the workflow comment), Playwright on Chromium,
token-gated Chromatic.
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
for dark-mode text and `-deep` variants for light-mode badge text on
translucent tints (5.6–6.0:1 measured), muted `ink-soft`/`parchment-soft`
(also the explicit placeholder color in both themes, 7.0:1/7.5:1), motion
`--dur-micro/ui/panel/shimmer` + enter/exit easings), `@custom-variant dark`,
`.tnum` tabular figures, `.lift` hover, `.stream-caret` blink,
`.skeleton` shimmer, `.toast-stack`/`.toast-enter`, a 3:1 `:focus-visible`
ring, 24px minimum pointer targets, and the `prefers-reduced-motion`
kill-switch. Every text/background pair holds WCAG 2.2 AA 4.5:1 in both
themes (flattened badge pairs pinned by `shared/a11y/contrast.test.ts`;
the Playwright axe suite re-proves it on every run). No global margin/padding reset.
