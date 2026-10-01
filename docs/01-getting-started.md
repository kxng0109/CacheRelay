---
sidebar_position: 1
---

# Getting started

Run CacheRelay locally with free, open-source images — no paid service or subscription is required. Provider API keys are optional: the gateway boots without them, and a locally installed [Ollama](https://ollama.com) serves free models and embeddings.

## 1. Generate local secrets

```powershell
# Windows
scripts\init-env.cmd
```

```bash
# macOS / Linux
./scripts/init-env.sh
```

This fills every hard-required secret with locally generated random values and refuses to overwrite an existing `.env` unless forced. `.env` is gitignored — never commit it.

## 2. Start what you need

```bash
# Dependencies only (Redis 8 + PostgreSQL 16 for IDE development)
docker compose --profile deps up -d

# Gateway with backing databases plus Prometheus & Grafana
docker compose --profile monitoring up -d

# Full containerized stack
docker compose --profile all up -d --build
```

| Service    | Address                                                                       |
| ---------- | ----------------------------------------------------------------------------- |
| Gateway    | `http://localhost:8080`                                                       |
| Health     | `http://localhost:9091/actuator/health` (management port, host loopback only) |
| Grafana    | `http://localhost:3000` (51 panels, 12 rows)                                  |
| Prometheus | `http://localhost:9090` (20 pre-loaded alert rules)                           |

Provider, embedding-model, budget, and pricing-source changes require a container recreate (`docker compose up -d --force-recreate cacherelay`, ~15 s boot). Env-var changes never apply without a recreate.

## 3. Send the first request

Create a key (see [Keys and budgets](./05-keys-budgets.md)), then:

```bash
curl -N http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer gw-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Hello"}]}'
```

```bash
curl http://localhost:8080/v1/embeddings \
  -H "Authorization: Bearer gw-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" \
  -H "Content-Type: application/json" \
  -d '{"model":"text-embedding-3-small","input":["First text","Second text"]}'
```

```bash
curl http://localhost:8080/v1/models \
  -H "Authorization: Bearer gw-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
```

The models listing is key-authenticated but unmetered metadata — no budget or rate-limit charge.

## Next

- [How a request flows](./02-request-flow.md) — the filter pipeline and status codes.
- [Failover and circuits](./03-failover-circuits.md) — provider chains and breakers.
