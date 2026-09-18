# Direction: Token Foundation (Phase 1)

## Hypotheses

1. We believe flipping the default canvas to dark (`night`) while keeping
   light (`paper`) fully supported will make long operator sessions
   sustainable, because NOC/SOC use is low-light, long-duration, and
   frequent — the exact conditions under which dark stops being cosmetic
   (NN/g dark-mode criteria, applied against consumer-web framing).
2. We believe keeping the repo easing `(0.22, 1, 0.36, 1)` over the PDF's
   `(0.23, 1, 0.32, 1)` will ship the identical feel, because the curves
   are perceptually indistinguishable and a token change without visible
   effect is churn, not craft.
3. We believe self-hosting fonts via Fontsource (Fraunces wght-only
   ~37 kB, Martian Mono, Inter Variable) will keep the console usable on
   unstable networks, because no third-party request can block or degrade
   rendering.

## What changes

- `src/index.css`: dark-first `@theme` (canvas stack night-first,
  hairline tokens, status triad untouched), font stacks
  (Fraunces headlines, Inter Variable UI with `cv01`/`ss03`, Martian Mono
  telemetry with tabular figures), existing easing token kept.
- `index.html`: `class="dark"` default + validated anti-FOUC script
  (allow-list `light`/`dark`, anything else ignored).
- Store default flips to dark; toggle persists via validated
  `localStorage` (see `02-theme-persistence.md`).

## What explicitly does NOT change

- Status-triad semantics, ember accent role, focus-ring construction,
  spacing scale, component markup. No screen is redesigned in this phase.
- Light theme is maintained (Option A), not deleted.

## Exit checks

- `tsc`, ESLint, format, full suite + 95% gates, audit, build, axe both
  themes.
- Cold load with cleared storage renders dark with zero flash; stored
  `light` renders light; corrupt stored value renders dark default.
- No `localStorage` read ever influences auth, API URLs, or markup.
