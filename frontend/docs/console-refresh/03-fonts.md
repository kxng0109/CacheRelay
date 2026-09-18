# Direction: Fonts (Phase 1)

## Hypothesis

We believe self-hosted variable fonts will give the console its
identity without punishing unstable networks, because three
`wght`-axis-only woff2 files total ~100–150 kB and load with zero
third-party requests (verified: Fraunces latin wght-only = 36,620
bytes via jsDelivr file listing, `@fontsource-variable/fraunces@5.3.0`).

## What ships

- Packages: `@fontsource-variable/fraunces` (headlines, serif ceremony),
  `@fontsource-variable/martian-mono` (telemetry, tabular figures),
  `@fontsource-variable/inter` (UI, with `cv01` alternate-one and
  `ss03` round quotes — both verified real on Inter's own lab page).
- Imports: `wght`-only latin CSS entries in `src/main.tsx` (or the CSS
  entry), `font-display: swap` (Fontsource default) so text never blocks.
- `@theme` stacks: `--font-display: 'Fraunces Variable', Georgia, serif`;
  `--font-sans: 'Inter Variable', system-ui, sans-serif` with
  `font-feature-settings: 'cv01', 'ss03'`; `--font-mono:
'Martian Mono Variable', ui-monospace, monospace` with tabular figures.
- System stacks remain as fallback after each custom family.

## Exit checks

- `npm audit` clean; licenses verified OFL in package payloads.
- Build chunk delta reviewed (fonts are separate hashed assets, never
  inlined into JS).
- Both themes render the stacks; axe passes with new type (line-height
  and contrast re-verified, never assumed).
