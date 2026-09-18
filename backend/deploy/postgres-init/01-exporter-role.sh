#!/bin/sh
# Creates the least-privilege monitoring role for postgres-exporter.
#
# Runs once at first volume init via docker-entrypoint-initdb.d (mounted
# read-only by docker-compose.yml), so no existence check is needed. All
# secrets come from the container environment - nothing is baked into the
# image or committed to git. Values travel as psql variables at the top
# level (never inside dollar-quoted blocks, where psql performs no
# substitution), so special characters in passwords stay intact.
set -eu

: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${POSTGRES_DB:?POSTGRES_DB is required}"
: "${POSTGRES_EXPORTER_USER:?POSTGRES_EXPORTER_USER is required}"
: "${POSTGRES_EXPORTER_PASSWORD:?POSTGRES_EXPORTER_PASSWORD is required}"

psql -v ON_ERROR_STOP=1 \
	--username "$POSTGRES_USER" \
	--dbname "$POSTGRES_DB" \
	-v exp_user="$POSTGRES_EXPORTER_USER" \
	-v exp_pwd="$POSTGRES_EXPORTER_PASSWORD" \
	-v db="$POSTGRES_DB" <<'EOSQL'
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
CREATE ROLE :"exp_user" WITH LOGIN PASSWORD :'exp_pwd';
GRANT pg_monitor TO :"exp_user";
GRANT CONNECT ON DATABASE :"db" TO :"exp_user";
EOSQL
