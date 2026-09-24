-- SSO revalidation sweep (Phase 3a): distributed lock table plus per-user
-- verification watermarks. The sweep self-seeds missing watermark rows at the
-- epoch so first checks run immediately, then advances them per attempt
-- (success or transport failure alike) so IdP outages retry at cadence
-- instead of hot-looping. Revocation happens only on positive IdP-disabled
-- signals, never on transport errors, so outages cannot mass-revoke.
CREATE TABLE shedlock
(
    name        VARCHAR(64)  NOT NULL,
    lock_until  TIMESTAMP    NOT NULL,
    locked_at   TIMESTAMP    NOT NULL,
    locked_by   VARCHAR(255) NOT NULL,
    CONSTRAINT pk_shedlock PRIMARY KEY (name)
);

CREATE TABLE sso_reval_watermark
(
    user_id            UUID        PRIMARY KEY,
    last_verified_at   TIMESTAMPTZ NOT NULL,
    last_status        VARCHAR(16),
    updated_at         TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_sso_reval_watermark_due ON sso_reval_watermark (last_verified_at);
