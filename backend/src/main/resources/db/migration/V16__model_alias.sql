-- Admin-managed model aliases: routing plans created and mutated at runtime via
-- /v1/admin/models. Rows here overlay the file-bound aliases from application.yml,
-- which always win on name conflict and stay read-only through the admin API.
CREATE TABLE model_alias (
    name VARCHAR(64) PRIMARY KEY,
    chain_json TEXT NOT NULL,
    strategy VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
