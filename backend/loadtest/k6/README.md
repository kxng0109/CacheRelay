# k6 local-Docker-stack runbook

Naming: decade = traffic domain (`0x` pre-flight, `1x` chat, `2x` embeddings,
`3x` streaming + advanced, `4x` synthetic capacity with no inference);
ascending number = intensity within the decade; named files (e.g.
`gate-profile-b.js`) are non-local artifacts, never local runs.

`00-smoke` → `10-rps-flood` → `20-embeddings-burst` → `30-sse-smoke` →
`31-sse-hold-xk6` (custom binary, see file header). `gate-profile-b.js` is the
staging-scale artifact (sharded generators), not for local runs.

## Script catalog

Decade = traffic domain; number = intensity. All runs need the app healthy
(`/actuator/health` UP) and, unless noted, a seeded `LOAD_KEY`.

| Script | Purpose | Wall time | Load shape (VUs / rate) | Endpoints touched | Untouched | Key needed |
|---|---|---|---|---|---|---|
| `00-smoke.js` | Wiring proof: actuator + auth-deny shapes | ~1m | 10 VUs | `GET /actuator/health`, `GET /actuator/prometheus`, `POST /v1/chat/completions` (401/4xx negatives only) | Models, embeddings, SSE, MCP | None |
| `01-gate-smoke.js` | 60s gate tripwire: auth + rate Lua + budget Lua, no upstream | ~1m | 100 rps, 20 pre / 50 max VUs | `POST /v1/chat/completions` (unknown model → local 404) | Upstream, embeddings, SSE | `LOAD_KEY` |
| `02-gate-burst.js` | 3m measurement burst for carrier A/B + bottleneck order | ~3m | 500 rps, 60 pre / 150 max VUs | Same as `01` | Same as `01` | `LOAD_KEY` |
| `10-rps-flood.js` | Cache-hit-able chat flood + rate-integrity dual mode | ~2m | Prime 10 rps + flood 50 rps (env override) | `POST /v1/chat/completions` | Embeddings, SSE, models | `LOAD_KEY` (falls back to 120-RPM dev key → asserts 429 shape instead) |
| `20-embeddings-burst.js` | Embeddings rate | ~1–2m | 20 rps (env override) | `POST /v1/embeddings` | Chat, SSE, models | `LOAD_KEY` + upstream embedding key or local Ollama |
| `30-sse-smoke.js` | SSE acceptance (start + close, no TTFT) | ~1–2m | 3 VUs (env override) | `POST /v1/chat/completions` (`stream:true`) | Embeddings, models | `LOAD_KEY` |
| `31-sse-hold-xk6.js` | True TTFT + inter-event gaps (xk6-sse, auto-provisioned) | ~6.5m | Ramp 0→3 VUs 1m, hold 5m + 30s stop | `POST /v1/chat/completions` (`stream:true`) | Embeddings, models | `LOAD_KEY` |
| `32-overload-ramp.js` | Gateway ceiling hunt on the cache-hit path | ~4.5m | 5 steps 1k/5k/10k/25k/50k rps, 30–60s each | `POST /v1/chat/completions` (byte-fixed body → L2 HIT, no Ollama) | Embeddings, SSE, models | `LOAD_KEY` unlimited (`RPMLIMIT=0`) |
| `33-knees.js` | Concurrency knees (fixed-VU steps bend pools/queues) | ~5.5m | 500/1000/2000/4000 VUs, 60–90s each | `POST /v1/chat/completions` (byte-fixed body → L2 HIT) | Embeddings, SSE, models | `LOAD_KEY` unlimited |
| `35-mixed-60s.js` | Mixed-traffic proof: HIT + MISS + full error matrix | 75s | 4+4+2+2 rps chat/embed + 1 rps each err-401/400/404/429 | Chat + embeddings + error paths | SSE | `LOAD_KEY` + `DEV_KEY` (120-RPM, for the 429 phase) |
| `40-breakpoint.js` | Gateway ceiling WITHOUT completions (models-list ramp) | ~12m | 20 steps 100→2000 rps, 30s each; aborts past sustained 1% transport failures | `GET /v1/models` only | Chat, embeddings, SSE, upstream, Ollama | `LOAD_KEY` unlimited (default key 429s above 1000 rps) |
| `gate-profile-b.js` | Staging/distributed artifact (sharded generators, 80k rps + 25k SSE) — NOT a local run | N/A | 4000 pre VUs across generator hosts | Small-JSON + SSE mix | N/A (staging) | `GATEWAY_URL` + `API_KEY` |

## Prerequisites

```powershell
winget install k6 --source winget
docker compose --profile monitoring up -d --wait --wait-timeout 120
# wait for app health (compose start_period is 20s; poll to be sure):
do { Start-Sleep 5; $h = (curl -fsS http://localhost:9091/actuator/health 2>$null) } until ($h)
$env:BASE_URL = "http://localhost:8080"
# default compose bootstrap key (valid 32-char suffix):
$env:API_KEY = "gw-localdevmasterkey0123456789abcde"
$env:LOAD_KEY = "gw-0123456789abcdef0123456789abcdef"
```

Provider-key-dependent scenarios (`10` measured, `20` full) need a provider
key (OPENAI_API_KEY from `.env`) for 200s at scale, or point at local Ollama (`MODEL=local-llama`,
`EMBEDDINGS_MODEL=local-embed`); 502/504 are
rate-integrity shapes when keys are absent. The `local-llama` alias points at
the installed `ollama` chat model (`qwen2.5:0.5b`); embeddings alias
`local-embed` uses `ollama` with `nomic-embed-text:latest`. The adapter
defaults to array-form input; single-string form is selected per provider via
the `embeddingSingleAsString` flag (Ollama uses single-string).

## Local Ollama (active local-stack path)

With Ollama serving the installed models (`ollama serve`; `ollama pull qwen2.5:0.5b`
for chat, `ollama pull nomic-embed-text:latest` for embeddings):

```powershell
$env:MODEL = "local-llama"
$env:EMBEDDINGS_MODEL = "local-embed"
k6 run loadtest/k6/10-rps-flood.js
k6 run loadtest/k6/20-embeddings-burst.js
k6 run loadtest/k6/30-sse-smoke.js
```

The gateway forwards `model` verbatim; the adapter passes array `input` by
default (Jina-requires-arrays compatible); `qwen2.5:0.5b` chat + `nomic-embed-text`
embeddings work directly. SSRF allowlist (`GATEWAY_DEV_ALLOW_PRIVATE_HOSTS`)
handles `localhost` for the container's internal reach.

Runs: same `k6` commands above (`00-smoke` first for wiring proof; the
`10-rps-flood` model value stays `fast` for provider-independent rate-integrity,
while `local-llama` alias points at the installed `qwen2.5:0.5b`).

## Runs (NOT executed by automation — run by hand for now)

```powershell
k6 run loadtest/k6/00-smoke.js              # wiring proof (actuator, auth-deny, mcp, no keys needed)
k6 run loadtest/k6/10-rps-flood.js          # rate-integrity flood; needs OPENAI_API_KEY or 502
k6 run loadtest/k6/20-embeddings-burst.js   # embeddings rate; EMBEDDINGS_MODEL=local-embed for local Ollama, else provider key
k6 run loadtest/k6/30-sse-smoke.js           # SSE wiring (stream start, close, no TTFT)
.\k6-sse run loadtest/k6/31-sse-hold-xk6.js   # true TTFT + inter-event gaps (custom binary)
```

## Isolation between scenarios

```powershell
docker compose exec -T redis redis-cli FLUSHDB
docker compose exec -T postgres psql -U cacherelay -d cacherelay -c "TRUNCATE usage_ledger RESTART IDENTITY;"
```

Count-asserting runs need `docker compose down -v` first (fresh named volumes).
k6 series land next to SUT metrics when run with
`-o experimental-prometheus-rw` + `K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write`.

## Chaos trims (separate shell, mid-hold)

- `docker stop cacherelay-redis` → expect fast fail-closed 503s, no hangs; start, expect recovery without restart.
- `docker stop cacherelay-postgres` → expect dead-letter growth (`logs/ledger-deadletter.log`), no thread-block; start,
  expect replay.
- Slow-client: covered by `30-sse-smoke` stalls + backpressure aborts in app logs.

## Verified run log (2026-09-09, local Ollama stack)

All four scenarios green against the rebuilt `cacherelay:latest` image:

| Scenario              | Result                                    | Notes                                                                                                                                                       |
|-----------------------|-------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `00-smoke`            | 53,720/53,720 checks                      | harness↔stack wiring                                                                                                                                        |
| `10-rps-flood`        | 100% checks, 0 dropped, p95 8.9ms         | 50 rps, `local-llama`                                                                                                                                       |
| `20-embeddings-burst` | 100% checks, 0 dropped, p95 33.8ms        | 20 rps, `local-embed`                                                                                                                                       |
| `30-sse-smoke`        | 100% checks, 0 failed                     | 3 VUs, `local-llama`                                                                                                                                        |
| `31-sse-hold-xk6`     | 48,344 streams, TTFT p95 9ms, gap p95 0ms | 3 VUs, `local-llama`, `.\k6-sse.exe` (no manual build — stock k6 v1.7.0+ auto-provisions `k6/x/sse`; local `go install` fallback documented in file header) |

Chaos trims: Redis stop → chat 503 (fail-closed), embed 400 (validation),
recovery clean; Postgres stop → both 400, ledger dead-letter journal replayed
19,336 writes lost during the outage on restart.

## Memory-proof runs (2026-09-10, SoftMaxHeapSize=768m, Xmx1152m, Direct 64m, limit 2G)

`30-sse-smoke`: 114,816 checks, 0 failed. Flood @50rps: 21,600 reqs, 0 failed,
0 dropped (RSS 1008MiB under load). Burst: p95 32.21ms (baseline 33.8ms — no
regression, sawtooth A/B skipped). Spike @150rps: 63,599 reqs, 0 failed,
0 dropped, p95 5.34ms (cache-warm), no restarts, RSS 1.047GiB. OOM-line: full
flood green at Xmx192m (6x below operating point) — kill not reached, margin
proven; cold-start 00-smoke showed 60% k6 timeouts with clean app logs
(latency death precedes OOM death — warm up before measuring).

Overload hunt (`32-overload-ramp.js`, cache-hit path, unlimited key): 176,867
reqs dispatched (~635/s achieved), 85.5% served, 14.5% hit the 30s client
timeout; 5.1M iterations dropped at the GENERATOR (vus_max 10,000 — single-box
k6 saturates first). App never OOM'd, never restarted, UP after, peak RSS
1.616GiB/2G. Weak-spot order: (1) k6 generator VU exhaustion, (2) gateway
queueing latency (not memory). 10K+ meaningful rps needs distributed
generation (gate-profile-b) + server thread/queue tuning.

## Local rate ceilings

The shared dev box serves `qwen2.5:0.5b` + `nomic-embed-text:latest` from Ollama at 100% GPU (iGPU).
Saturating the scenario targets (500 rps chat, 100 rps embeddings, 50 SSE VUs)
yields 60s timeouts and tens of thousands of dropped iterations — capacity, not
defect. Each scenario now defaults to a sustainable local rate and accepts an
env override for provider-keyed runs:

- `10-rps-flood.js`: `PRIME_RATE` (default 10), `FLOOD_RATE` (default 50)
- `20-embeddings-burst.js`: `BURST_RATE` (default 20)
- `30-sse-smoke.js`: `SSE_VUS` (default 3)

## Gotchas

- **Never `FLUSHDB` between scenarios.** It wipes the seeded bootstrap keys, and
  `BootstrapKeySeeder` latches `seeded=true` so it never re-seeds — every
  request then 401s. Restart the app to reset the latch and re-seed.
- `31-sse-hold-xk6.js` needs the xk6-sse binary (build via `grafana/xk6`).
  k6 v1.7.0 resolves `k6/x/sse` automatically — verify whether the custom
  build is still required before running it.

## Threshold policy

Smoke/prerelease runs assert integrity (`dropped_iterations == 0`,
`failed < 5%`, `checks > 95%`) with generous latency bounds for shared dev
hardware. Tight SLO thresholds (p95/p99) run nightly on quiet iron, never in PR.
