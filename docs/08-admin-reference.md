---
sidebar_position: 8
---

# Admin and account reference

Master-key endpoints accept `Authorization: Bearer <GATEWAY_ADMIN_MASTERKEY>` or `X-Admin-Key`. The app fails fast at startup when the master key is missing, blank, short, or a published default. Admin denials answer stealth-404 and every mutation is audit-logged.

## Humans, teams, sessions

- `POST /v1/auth/login` → short-lived access JWT (memory only) + `httpOnly` refresh cookie. `POST /v1/auth/refresh` rotates (CSRF header required; replay revokes the family). `POST /v1/auth/logout`, `GET /v1/auth/me`.
- `POST /v1/auth/redeem` consumes single-use invites (first redemption bootstraps the initial admin). `POST /v1/admin/invites` creates them, with optional `teamId` placement. Point `gateway.auth.invite-base-url` at the public frontend origin.
- SSO: Google, GitHub, Entra ID, Azure B2C, Okta, generic OIDC (Authorization Code + PKCE, shadow accounts by `(sub, iss)`), per-registration team mappings, first-login backfill, revalidation sweeps, and per-IdP webhook invalidation.
- Local teams (no SSO needed): `GET`/`POST /v1/admin/orgs`, `PATCH`/`DELETE /v1/admin/orgs/{id}` (non-empty deletes `409`); per-org team CRUD (IdP-mapped teams read-only, referenced deletes `409`); idempotent member assign/revoke (`MEMBER`/`LEAD`); `GET /v1/admin/teams?org=`, `GET /v1/me/teams`.
- `PUT|DELETE /v1/admin/users/{id}` (deletion terminally revokes keys first), `GET /v1/admin/users`, `GET /v1/admin/ledger/user/{userId}/summary`, `GET /v1/me/usage` (trailing 7 days default, 90-day cap).

## Ledger, models, providers

- `GET /v1/admin/ledger/summary` (by tenant/model/provider + filters), `GET /v1/admin/ledger/entries` (paginated, allowlisted sort), `GET /v1/admin/ledger/entries/{requestId}`, `GET /v1/admin/budgets/holds/{requestId}` (held vs settled micros).
- `GET /v1/admin/models` (`source`: `file` read-only vs `database` editable), `POST|PUT|DELETE /v1/admin/models[/{name}]`, `GET /v1/admin/model-catalog` (pricing snapshot picker with quality tiers and embedding widths), `GET|PUT|DELETE /v1/admin/model-quality/{modelId}`.
- `GET /v1/admin/providers` (dialect, base URL, `keyConfigured` boolean only, live circuit, validation status).

## Observability and API docs

- Health and Prometheus live on the dedicated management port (`:9091`, host loopback only); the app port serves no actuator route. Grafana ships a 51-panel operations dashboard.
- Interactive reference is served by the gateway itself: `/swagger-ui.html` and `/v3/api-docs` (JSON + YAML), partitioned into Public, Admin, and Observability groups. On the public site the reference is browsable-only — Try-It-Out targets `localhost` and cannot work for visitors.
