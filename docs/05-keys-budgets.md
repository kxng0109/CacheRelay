---
sidebar_position: 5
---

# Keys and budgets

## Virtual keys

Keys are 32 random base64url characters behind a `gw-` prefix (192 bits of entropy, `SecureRandom`). Only the SHA-256 digest is stored; plaintext is shown **once** at creation and never logged.

- `POST /v1/admin/keys` — create with RPM/TPM quotas and allowlists. The response carries `keyId` (the SHA-256 hex digest — the same value the API calls `{keyId}`) plus the single-exposure plaintext key.
- `GET /v1/admin/keys` — list metadata only (optional `?ownerId=`).
- `GET /v1/admin/keys/{keyId}`, `PATCH /v1/admin/keys/{keyId}` — inspect and update quotas, allowlists, status.
- `DELETE /v1/admin/keys/{keyId}` — permanently delete and purge caches.
- `POST /v1/admin/keys/{keyId}/revoke` — irreversible revocation tombstone; nothing can un-revoke.

Two independent limits per key — requests per minute and tokens per minute — checked and consumed in one atomic Redis Lua step. Session owners can act as an owned key without handling key material (`X-Act-As-Key`). Self-service lives under `/v1/me/keys` (list metadata, pick a default, revoke owned keys).

## Budgets (hold-then-settle)

Every admitted stream is charged a **hold** (`prompt + max_tokens × output` micro-dollars, mandatory server-side `max_tokens` ceiling) through an atomic Lua gate, then trued up to measured spend at stream end with exactly-once semantics. Aborts settle the input-known portion; crashes expire the hold to zero and write an append-only gap row — counters can never silently under-count. A 30 s sweeper reclaims orphaned holds across pods. Spend counters live on a dedicated `noeviction` Redis so cache pressure can never erase them.

- `POST /v1/admin/budgets` — hard cap per `KEY` (key hash), `TEAM` (owner slug), or `ORG` (global), with rolling-60 s and calendar-month caps in micro-dollars (`0` = no cap).
- `GET /v1/admin/budgets/{level}/{subject}/balance` — caps plus live counters (Redis outage degrades to `503`, never a zero-spend lie).
- `PUT /v1/admin/budgets/{id}` (optimistic locking), `DELETE /v1/admin/budgets/{id}` (spend snapshotted to audit first).

A background watchdog (static 50/90/100% thresholds, EWMA exhaustion forecast, z-score anomaly detection) delivers to opt-in email, Teams, Slack, or signed webhooks via `POST|GET|DELETE /v1/admin/notifications`. Clients may send `X-CacheRelay-Min-Quality-Tier` and `X-CacheRelay-Tradeoff-Mode` (`quality` default; `eco` accepted but unenforced); cost routing today is observation-only.
