# Provider Contracts Dossier (27 providers)

Evidence as of 2026-09-22. Methods: official-docs research per provider (request/response
shapes, auth, quirks) plus one-shot dummy-key liveness probes (no real credentials created).
Nothing here proves live inference, quota, or billing — those need real keys.

Conventions enforced by code: base URLs are prefixes (the adapter appends the chat path,
default `/v1/chat/completions`, overridable per provider). Locked by
`ProviderUrlMatrixTest`; usage envelopes pinned by `UpstreamUsageEnvelopeTest`.

## Self-hosted (no accounts, no keys)

| Provider | Chat URL | Auth | Key quirks | Validation | Probe 2026-09-22 |
|---|---|---|---|---|---|
| ollama | `http://localhost:11434` + default path (own adapter) | none locally | rejects `tool_choice`/`logit_bias`/`user`/`n`/`logprobs`, image URLs (base64 only); native `{"error"}` envelope | CONTRACT_CHECKED | direct chat 200, real inference |
| llamacpp | `http://localhost:8081` + default path (`:8081` avoids the gateway's own 8080) | optional key | root-vs-`/v1` trap; OpenAI error template; extra timing fields | CONTRACT_CHECKED | direct chat 200, real inference |
| lmstudio | `http://localhost:1234` + default path | none by default; headless has no auth flag (bind localhost or proxy) | JIT `/models` semantics; no `/health` (use `/v1/models`) | CONTRACT_CHECKED | direct chat 200 on `lfm2-1.2b`, real inference |
| vllm | `http://localhost:8000` + default path | optional; enforced only on `/v1`, `/v2`, `/inference` (`/invocations` stays open) | `suffix` unsupported, `user` ignored; pin version (tools support varies) | CONTRACT_CHECKED | not installed (excluded as heavy) |

## Managed (account + key required; validated keyless)

| Provider | Chat URL | Auth | Key quirks | Validation | Probe 2026-09-22 |
|---|---|---|---|---|---|
| openai | host + default path | Bearer | 400 on invalid values (never silently ignored); flat + nested usage; SSE `[DONE]`, usage chunk opt-in; 429/503 carry Retry-After | AUTH_REACHABLE | 401 incorrect key |
| openrouter | `…/api` + default path | Bearer + optional attribution headers | IGNORES unsupported params; 402 out of credits; provider failures arrive as HTTP 200 with body error; SSE `: OPENROUTER PROCESSING` keep-alives + non-empty final choices; generic 401 covers invalid keys (verified in docs) | AUTH_REACHABLE | 401 generic message |
| anthropic | native adapter → `/v1/messages` | `x-api-key` + `anthropic-version` (both required) | no `system` role (top-level `system`); temp 0–1; HTTP 529 (not 503); flat usage; named-event SSE, no `[DONE]` | AUTH_REACHABLE | 401 invalid key |
| together | host + default path | Bearer | overflow truncates by default; regex `response_format`; usage is FLAT (no nested details); 429 exposes only reset timestamp | AUTH_REACHABLE | 401 invalid key |
| groq | `…/openai` + default path | Bearer | 400-list: logprobs/logit_bias/top_logprobs/name/`n≠1`/penalties; temp 0 coerced; usage + timing fields; custom 498/499; no structured-output + streaming | AUTH_REACHABLE | 401 invalid key |
| mistral | host + default path | Bearer | 422 validation errors; `object:error` envelope; `model_length` finish reason | AUTH_REACHABLE | 401 invalid key |
| xai | host + default path | Bearer | reasoning models reject penalties/stop; `reasoning_effort` version-dependent; no published error envelope; mixed role order allowed | AUTH_REACHABLE | 400 incorrect key |
| deepinfra | host + override `/v1/openai/chat/completions` | Bearer (scoped JWT alt) | `service_tier`/`fail_fast`/model-fallback fields; 422 FastAPI validation; usage + `estimated_cost`; 200-concurrency cap, no rate headers | AUTH_REACHABLE | 401 invalid key |
| fireworks | `…/inference` + default path | Bearer | LONG ids (`accounts/fireworks/models/…`); usage in final chunk by default; NO OpenAI-style `/models`; 402/412; TPM headers | AUTH_REACHABLE | 404 model (URL proven) |
| cerebras | host + default path | Bearer | validation is 400 (was 422); `n=1` only; `max_tokens` ⊕ `max_completion_tokens`; no json-mode + streaming; nested usage + `time_info`; base64 images only | AUTH_REACHABLE | 401 wrong key |
| sambanova | host + default path | Bearer | temp 0–1; many fields IGNORED (not rejected); extra `top_k` | AUTH_REACHABLE | 404 model_not_found |
| nebius | host + default path | Bearer | `json_object`/`text` only; rich error table incl. 402/529; full rate headers; Studio vs Token Factory hosts (key provenance decides) | AUTH_REACHABLE | 401 |
| novita | `…/openai` + default path | Bearer | `max_tokens` REQUIRED; lowercase ids; non-OpenAI list shape; 401-vs-403 auth split (both exist) | AUTH_REACHABLE | 401 |
| moonshot | host + default path | Bearer | keys NOT interchangeable across regions (`.ai` vs `.cn`); thinking via `extra_body`; cache breakpoint → 400; hybrid usage; `X-RateLimit-*` | AUTH_REACHABLE | 401 |
| zhipu | `…/paas/v4` + override `/chat/completions` | Bearer | system-only input rejected; string business codes; `sensitive`/`model_context_window_exceeded` finish reasons; NO `/models`; stream abort → finish_reason, not error envelope | AUTH_REACHABLE | 401 |
| minimax | host + default path | Bearer | thinking ON by default (M2.x cannot disable); `max_completion_tokens` (not `max_tokens`); `base_resp` + sensitivity flags; nested cached tokens; China host variant | AUTH_REACHABLE | 401 |
| qwen | `…/compatible-mode` + default path (Beijing default; keys region-bound) | Bearer | `n` only on plus-tier; thinking via `extra_body`; stop-array element rule | AUTH_REACHABLE | 401 incorrect key |
| stepfun | host + default path | Bearer | dual regions (`.com` vs `.ai`); Step-Plan identifiers; EN/ZH doc conflicts (penalty ranges, `json_schema`, usage shape) — fixtures accept both | AUTH_REACHABLE | 401 incorrect key |
| cloudflare | `…/accounts/{id}/ai` + default path | token + account ID | `@cf/` or `provider/` ids; `rejectIfBusy`; GPT-OSS responses only + `stream:false`; compat error envelope uncertain | AUTH_REACHABLE | 404 routing shape (empty account id) |
| hyperbolic | host + default path | Bearer | NO `/models` endpoint; penalty-range doc conflicts; error shape uncertain; live 40401 `decommissioned` CONFLICTS with current docs (kept docs URL, flagged) | AUTH_REACHABLE | 404 provider-shaped error |
| ionet | `…/api` + default path | Bearer | paginated non-OpenAI list; quota/credit model; thin SSE docs | AUTH_REACHABLE | 401 invalid key |
| friendli | `…/serverless` + default path | Bearer | dedicated base `…/dedicated/v1` uses endpoint id as model | AUTH_REACHABLE | 401 unauthorized |
| bedrock | `…/amazonaws.com/openai` + default path (explicit regional base) | Bearer, no SigV4 needed | open-weight models only on this route; mantle host alternative; `GET /models` on mantle only | AUTH_REACHABLE | 401 key-prefix error |

## Honest limits

- No row proves inference, model existence, quota, or billing — impossible without keys.
- Uncertain (not encoded): Fireworks exhaustive deny-list; xAI/Cloudflare-compat/Hyperbolic/io.net error shapes;
  DeepInfra per-field rejections; exact Bedrock 4xx bodies; Ollama compat usage JSON (pin version).
- Usage capture limitation: OpenRouter final chunks and StepFun per-chunk usage beside non-empty
  `choices` are not captured for accounting (normalizer requires empty choices). Queued with
  cost-router work — do not silently widen capture on the billing path.
- Fixture-expiry policy: every contract row re-checks against docs on a 90-day cadence or on
  any provider 4xx-shape change observed in production; this file carries the 2026-09-22 baseline.
