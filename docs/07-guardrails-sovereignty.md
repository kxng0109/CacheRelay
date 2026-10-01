---
sidebar_position: 7
---

# Guardrails and sovereignty

## Ingress screening

Every request body is scanned for leaked credentials (provider keys, cloud keys, tokens, private keys — entropy plus checksum verified, zero false positives) and prompt-injection attempts (Unicode homoglyph flattening, cascaded screening, system-prompt exfiltration protection via token shingling and Bloom filters).

- **ENFORCE mode**: violations answer HTTP `422` with an RFC 9457 problem body plus an `X-CacheRelay-Vendor-Verdict: <vendor>:<reason>` header. Render the title and detail; show vendor/reason as attribution.
- **AUDIT_ONLY mode**: the request succeeds normally (`200`) with the verdict header attached and no body marker — surface it as a non-blocking screening badge wherever request metadata is shown. No header means clean or disabled.
- **Screening outage (either mode)**: HTTP `500` with a generic failure body — no vendor/reason, no verdict header. Render as a screening-outage error, never as a content verdict.

## PII vault

PII is anonymized before upstream forwarding with semantic surrogates and an ephemeral AES-256-GCM request-scoped vault, then reconstituted in outbound streams on the fly with sub-millisecond overhead. Nigerian PII (NCC numbering, NIN, BVN, Verve Luhn, Tax IDs) runs a 4-tier disambiguation pipeline. Streaming JSON outputs are validated byte-by-byte, and a mid-stream kill switch terminates violating streams and stops upstream billing at the socket.

## Data residency

Per-request geo-sovereignty policies — `STRICT_SOVEREIGN`, `SOVEREIGN_CASCADE`, `PERMISSIVE_FAILOVER_WITH_AUDIT` — filter provider chains by jurisdiction (`gateway.provider-jurisdictions`, e.g. `openai: US`). Unregistered providers fail closed under strict modes. Every stream carries an HMAC-authenticated SHA-256 hash-chained audit receipt (`X-CacheRelay-Audit-Receipt`) plus Zero Data Retention headers.
