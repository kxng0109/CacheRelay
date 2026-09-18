-- Human authentication: local accounts, SSO shadow accounts, rotating refresh
-- families, single-use invites, and severity-filterable audit events. All secret
-- material is stored hashed; plaintext is single-exposure at creation only.
CREATE TABLE auth_user (
    id UUID PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255),
    email_hash VARCHAR(128),
    admin BOOLEAN NOT NULL DEFAULT FALSE,
    disabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uq_auth_user_username ON auth_user(LOWER(username));
CREATE TABLE auth_sso_link (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES auth_user(id) ON DELETE CASCADE,
    issuer TEXT NOT NULL,
    subject TEXT NOT NULL,
    registration_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_auth_sso_link_subject UNIQUE (issuer, subject)
);
CREATE INDEX ix_auth_sso_link_user ON auth_sso_link(user_id);
CREATE TABLE auth_refresh_token (
    id UUID PRIMARY KEY,
    family_id UUID NOT NULL,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    user_id UUID NOT NULL REFERENCES auth_user(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    absolute_expires_at TIMESTAMPTZ NOT NULL,
    replaced_by UUID,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX ix_auth_refresh_family ON auth_refresh_token(family_id);
CREATE INDEX ix_auth_refresh_user ON auth_refresh_token(user_id);
CREATE TABLE auth_invite (
    id UUID PRIMARY KEY,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    email_hash VARCHAR(128),
    admin BOOLEAN NOT NULL DEFAULT FALSE,
    created_by UUID REFERENCES auth_user(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    consumed_by UUID REFERENCES auth_user(id) ON DELETE SET NULL
);
CREATE TABLE auth_audit (
    id UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor_hash VARCHAR(128) NOT NULL,
    action VARCHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    resource_path VARCHAR(512),
    outcome VARCHAR(16) NOT NULL,
    ip_hash VARCHAR(128),
    request_id VARCHAR(64)
);
CREATE INDEX ix_auth_audit_action ON auth_audit(action, occurred_at DESC);
CREATE INDEX ix_auth_audit_severity ON auth_audit(severity, occurred_at DESC);
CREATE INDEX ix_auth_audit_actor ON auth_audit(actor_hash, occurred_at DESC);
