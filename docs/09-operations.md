---
sidebar_position: 9
---

# Operations

## Configuration

Everything lives in `backend/src/main/resources/application.yml` (startup-bound; `gateway.*` changes need a container recreate, ~15 s boot). Key prefixes:

| Prefix                                                                                                | Controls                                                                   |
| ----------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| `gateway.providers`, `gateway.aliases`                                                                | Upstreams and model chains.                                                |
| `gateway.budget.settlement.*`, `gateway.budget.detection.*`                                           | Hold-then-settle accounting and the spend watchdog.                        |
| `gateway.cache.*`, `gateway.redis.cache.*`                                                            | Tiers, thresholds, guardrail flags, dedicated evictable Redis.             |
| `gateway.mcp.*`, `gateway.a2a.*`                                                                      | Servers, agents, HITL, breakers.                                           |
| `gateway.provider-jurisdictions`                                                                      | Residency map (`EU`, `US`, `NG`, `UK`, `CH`, `CA`, `ZA`, `GH`).            |
| `gateway.pricing.*`, `gateway.ledger.*`                                                               | Catalog sync source/schedule, dead-letter path, migration retry.           |
| `gateway.embeddings.*`                                                                                | Ollama keep-warm heartbeat, local ONNX embedder.                           |
| `gateway.sso.*`, `gateway.auth.*`, `gateway.notify.*`, `gateway.maintenance.*`, `gateway.dashboard.*` | Identity, invites, alerts, retention, usage views.                         |
| `cacherelay.sse.*`                                                                                    | Adaptive flush strategy and upstream line-guard tunables (hot-reloadable). |

Secrets arrive exclusively via environment (bootstrap keys, provider keys, HITL secret, JWT secret). Plaintext keys never belong in the repository.

## Security posture (operator summary)

Fail-closed Redis/rate-limit/auth paths; SSRF-validated provider URLs with no redirect following; stripped spoofable headers; generic error messages; no production CORS (same-origin SPA); per-response CSP nonces; stealth-404 admin denials; 5-minute admin tokens; no token in `localStorage`. Kubernetes manifests (`backend/deploy/k8s/`) are render-validated; bare-metal uses the provided systemd unit and ops guide.

## Testing gate

`cd backend && ./mvnw clean verify` — 1,666 tests, JaCoCo bundle gates (instruction/branch/line/method/class ≥ 95%, complexity ≥ 90%). Targeted runs (`-Dtest=… -Djacoco.skip=true -q`, `-Plocal`) are iteration-only; only a green `verify` counts.

## Docs versioning

These pages are Docusaurus-ready: each carries `sidebar_position` frontmatter, and the directory versions with `docs:version` (`/docs/next` current, `/docs` latest, `/docs/1.0.0` pinned, all under the `/CacheRelay/` base). Keep one source of truth — generate or contract-check OpenAPI examples from handler code rather than hand-maintaining copies.
