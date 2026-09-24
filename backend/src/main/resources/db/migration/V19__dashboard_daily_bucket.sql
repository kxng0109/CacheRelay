-- Lazy on-demand dashboard buckets for Phase 1: one detail row per settled day
-- per owner, provider, and model triple, written on first view and reused
-- afterwards. No background jobs touch this table; only dashboard reads do.
-- Totals and every breakdown reconstruct exactly from detail rows in memory,
-- so averages recompute from duration sums and never average averages. Ledger
-- rows are append-only and idempotent on request_id, so settled-day buckets
-- are immutable once written.
CREATE TABLE dashboard_daily_bucket
(
    id                  UUID         PRIMARY KEY,
    scope_type          VARCHAR(16)  NOT NULL,
    scope_key           VARCHAR(128) NOT NULL,
    bucket_day          DATE         NOT NULL,
    owner               VARCHAR(64)  NOT NULL DEFAULT '',
    provider            VARCHAR(64)  NOT NULL DEFAULT '',
    model               VARCHAR(128) NOT NULL DEFAULT '',
    requests            BIGINT       NOT NULL,
    prompt_tokens       BIGINT       NOT NULL,
    completion_tokens   BIGINT       NOT NULL,
    total_tokens        BIGINT       NOT NULL,
    cost_micros         BIGINT       NOT NULL,
    billed_micros       BIGINT       NOT NULL,
    effective_micros    BIGINT       NOT NULL,
    duration_sum_ms     BIGINT       NOT NULL,
    cache_read_tokens   BIGINT       NOT NULL,
    cache_write_tokens  BIGINT       NOT NULL,
    uncached_tokens     BIGINT       NOT NULL,
    reasoning_tokens    BIGINT       NOT NULL,
    watermark           TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_dashboard_bucket_grain UNIQUE (scope_type, scope_key, bucket_day, owner, provider, model)
);

CREATE INDEX ix_dashboard_bucket_scope_day ON dashboard_daily_bucket (scope_type, scope_key, bucket_day);
