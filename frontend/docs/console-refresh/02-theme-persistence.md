# Direction: Theme Persistence (Phase 1)

## Hypothesis

We believe persisting the theme in `localStorage` will remove the
daily re-toggle tax, because operators reload and deep-link constantly
and the theme is pure presentation state with zero security surface —
provided the stored value is validated against a strict allow-list and
can never reach markup, URLs, or auth paths.

## Threat model (why this cannot become an exploit)

- Stored value is a single string under one key (`cacherelay.theme`).
- Read path: `parseTheme` accepts **only** the exact strings `light`
  and `dark`. Everything else (`null`, objects, `__proto__`,
  `<script>`, JSON blobs, 10 MB strings) maps to `null` → dark default.
  No `JSON.parse`, no `eval`, no `innerHTML`, no URL/query injection.
- Write path: only the toggle writes, and only those two literals.
- Consumption: toggles `documentElement.classList` and zustand state.
  Never rendered as text, never interpolated into stylesheets or URLs.
- Access wrapped in `try/catch` (private-mode throws); failure means
  session-only theme, never a crash.
- Credentials, API base, and tokens stay memory-only per standing
  doctrine. Theme persistence does not touch them.

## Tests (required)

- `parseTheme`: `light`/`dark` pass through; `null`, `undefined`,
  numbers, objects, `__proto__`, `<script>`, JSON strings, empty
  string, oversized input all return `null`.
- Store: toggle writes the literal; boot with corrupt value falls back
  to dark; `localStorage` throwing still boots dark.
- Anti-FOUC script: same allow-list inline (no shared import possible
  pre-bundle), verified by cold-load E2E in both themes.
