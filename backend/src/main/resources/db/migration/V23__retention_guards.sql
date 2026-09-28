-- FS-B15 retention and DB guards.
--
-- 1. alert_events_archive drift: V14 added value_text/month to alert_events
--    but not to the archive created by V12, so INSERT INTO archive SELECT *
--    fails with "more expressions than target columns". The janitor now
--    names columns explicitly; this aligns the archive table too.
ALTER TABLE alert_events_archive ADD COLUMN IF NOT EXISTS value_text VARCHAR(32);
ALTER TABLE alert_events_archive ADD COLUMN IF NOT EXISTS month VARCHAR(7);

-- 2. FIN-B37: the database enforces what the app assumes. Money and counters
--    can never go negative; alert attempts can never go negative; alert
--    status admits the SKIPPED terminal state (log-only Alertmanager mode).
ALTER TABLE usage_ledger ADD CONSTRAINT chk_usage_ledger_nonnegative CHECK (
    prompt_tokens >= 0 AND completion_tokens >= 0 AND total_tokens >= 0
    AND cost_usd_micros >= 0 AND duration_ms >= 0);
ALTER TABLE budget_limits ADD CONSTRAINT chk_budget_limits_nonnegative CHECK (
    minute_micros >= 0 AND month_micros >= 0);
ALTER TABLE alert_events DROP CONSTRAINT IF EXISTS alert_events_status_check;
ALTER TABLE alert_events ADD CONSTRAINT alert_events_status_check CHECK (
    status IN ('PENDING', 'SENT', 'FAILED', 'RESOLVED', 'SKIPPED'));
ALTER TABLE alert_events ADD CONSTRAINT chk_alert_events_attempts CHECK (attempts >= 0);

-- 3. FIN-B38: idx_usage_ledger_created_at (V1, ASC) is subsumed by
--    idx_usage_ledger_created_at_desc (V3): a btree serves both scan
--    directions, so the V1 index only costs writes.
DROP INDEX IF EXISTS idx_usage_ledger_created_at;
