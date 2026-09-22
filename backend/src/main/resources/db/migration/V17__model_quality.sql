-- Admin-curated model quality tiers, deliberately separate from model_pricing so the
-- daily LiteLLM price sync can never clobber curation. Absence of a row means
-- unrated: cost may tie-break only within a tier, never across tiers, and
-- unrated models are never substituted on quality grounds.
CREATE TABLE model_quality
(
    model_id       VARCHAR(128) PRIMARY KEY,
    quality_tier   VARCHAR(16)  NOT NULL,
    benchmark_refs VARCHAR(2000),
    updated_at     TIMESTAMPTZ  NOT NULL
);
