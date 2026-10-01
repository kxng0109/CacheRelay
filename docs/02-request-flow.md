---
sidebar_position: 2
---

# How a request flows

An incoming request to `/v1/chat/completions` passes through three ordered stages before any provider is touched. Filter registration and ordering live in `security/filter/SecurityFilterConfig.java`.

## The pipeline

1. **Body buffering (Order 0)** — `RequestBodyCachingFilter` wraps the request so the body can be re-read downstream, up to a configured cap.
2. **Authentication and rate limiting (Order 1)** — `KeyAuthFilter` authenticates the bearer token, checks the model allow list, estimates token cost, and consults the rate limiter. It sets the `X-RateLimit-*` response headers and answers `401`, `403`, `429`, or `503` directly on failure. RPM and TPM are checked and consumed atomically in a single Redis Lua script. If Redis is unreachable, requests fail closed with `503`.
3. **Ingress guardrails (Order 2)** — `IngressSecurityFilter` scans for leaked credentials and prompt-injection attempts. In `ENFORCE` mode violations answer HTTP `422` as an RFC 9457 problem body; PII is encrypted into a request-scoped AES-256-GCM vault and replaced with semantic surrogates (`<PERSON_1>`, `<EMAIL_1>`, …) before forwarding. See [Guardrails](./07-guardrails-sovereignty.md).
4. **Relay** — `ProxyController` resolves the model alias and the failover orchestrator picks a winning provider (filtered by geo-sovereignty policy), then streams the response. Failover happens only before the first byte; once streaming starts, switching providers is impossible.

## Status codes

| Code  | Meaning                                                                                             |
| ----- | --------------------------------------------------------------------------------------------------- |
| `200` | Stream started and is being relayed.                                                                |
| `400` | Body empty, malformed, or missing its model.                                                        |
| `401` | Key missing, malformed, or unknown. Revoked, unowned, and orphaned keys answer byte-identical 401s. |
| `403` | Key disabled, reversibly disabled, or model not allowed for the key.                                |
| `404` | Model has no configured alias.                                                                      |
| `413` | Body exceeds the configured limit.                                                                  |
| `422` | Guardrail or idempotency refusal (see below).                                                       |
| `429` | Rate limit exceeded; `Retry-After` says when to retry.                                              |
| `502` | Every provider returned an error.                                                                   |
| `503` | Nothing usable was reachable, or auth/rate-limiting is unavailable.                                 |
| `504` | Provider chain timed out.                                                                           |

A reused idempotency key with a different body answers `422`; a concurrent duplicate answers `409`. Byte-identical retries replay from store with `Idempotent-Replayed: true` and no second charge.

## Response headers worth reading

- `X-RateLimit-Limit-RPM`, `X-RateLimit-Remaining-RPM`, `X-RateLimit-Reset-RPM` (epoch seconds) plus the TPM trio; `429` carries `Retry-After`.
- `X-CacheRelay-Provider` (winner), `X-CacheRelay-Tried` (walk order with leg outcomes).
- `X-CacheRelay-Audit-Receipt` (HMAC-authenticated, hash-chained) and Zero Data Retention headers.
- `X-Cache: HIT` on served-from-cache responses.
- `X-CacheRelay-Vendor-Verdict` appears only when vendor screening flagged the request (ENFORCE `422` or AUDIT_ONLY `200`); its absence means clean or disabled.
