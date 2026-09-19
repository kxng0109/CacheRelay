#!/usr/bin/env sh
# Bootstraps a local .env for CacheRelay from .env.docker.example with generated secrets.
# Uses openssl when available, otherwise /dev/urandom - no network, no paid services.
# Provider API keys are intentionally left blank; the free local path uses Ollama (see README).
#
# Usage:  ./scripts/init-env.sh [template] [output]      (FORCE=1 to overwrite an existing .env)
set -eu
umask 077

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
src=${1:-"$repo_root/.env.docker.example"}
out=${2:-"$repo_root/.env"}

[ -f "$src" ] || { echo "Template not found: $src" >&2; exit 1; }
if [ -e "$out" ] && [ "${FORCE:-0}" != "1" ]; then
  echo "$out already exists. Re-run with FORCE=1 to overwrite it." >&2
  exit 1
fi

gen_hex() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex "$1"
  else
    od -An -N"$1" -tx1 /dev/urandom | tr -d ' \n'
  fi
}

tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
tr -d '\r' < "$src" > "$tmp"

for name in POSTGRES_PASSWORD POSTGRES_EXPORTER_PASSWORD REDIS_PASSWORD REDIS_CACHE_PASSWORD \
            GATEWAY_ADMIN_MASTERKEY GATEWAY_AUTH_JWT_SECRET GATEWAY_MCP_HITL_SECRET; do
  grep -q "^${name}=" "$tmp" || { echo "Template is missing required variable '$name' in $src" >&2; exit 1; }
  secret=$(gen_hex 32)
  sed "s|^${name}=.*$|${name}=${secret}|" "$tmp" > "$tmp.new" && mv "$tmp.new" "$tmp"
done
grafana=$(gen_hex 16)
sed "s|^GRAFANA_ADMIN_PASSWORD=.*$|GRAFANA_ADMIN_PASSWORD=${grafana}|" "$tmp" > "$tmp.new" && mv "$tmp.new" "$tmp"

cp "$tmp" "$out"
chmod 600 "$out" 2>/dev/null || true
echo "Created $out"
echo "Generated local secrets (values not shown). Provider API keys were left blank."
echo "Keep this file private - it is gitignored. Next: docker compose --profile deps up -d"
