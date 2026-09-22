-- Routing decision log for cost-router phase 1 (observation only): one row per
-- sampled chat request recording which chain was walked, which legs were
-- tried, who won, and the price rates known at the time. No prompts, no
-- completions, no keys, no PII: identifiers and rates only.
CREATE TABLE routing_decision_log
(
    id               UUID            PRIMARY KEY,
    occurred_at      TIMESTAMPTZ     NOT NULL,
    alias_name       VARCHAR(128)    NOT NULL,
    model_name       VARCHAR(128)    NOT NULL,
    min_quality_tier VARCHAR(16),
    tradeoff_mode    VARCHAR(16)     NOT NULL,
    chain_json       TEXT            NOT NULL,
    tried_json       TEXT            NOT NULL,
    winner           VARCHAR(128),
    input_rate       NUMERIC(24, 12),
    output_rate      NUMERIC(24, 12)
);

CREATE INDEX ix_routing_decision_log_occurred_at ON routing_decision_log (occurred_at);
