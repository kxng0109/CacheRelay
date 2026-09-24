-- SSO org/team/membership domain for Phase 2a (token-claim provisioning).
-- Teams materialize lazily on first login when an IdP group matches a configured
-- pattern; memberships flip ACTIVE/INACTIVE on every login from the presented
-- claims. The per-org unassigned team (idp_group_id '__unassigned__') holds
-- accounts with no mapped team under least privilege. Identity stays the
-- (issuer, subject) pair in auth_sso_link; admin privilege is always local
-- and never derives from IdP claims.
CREATE TABLE sso_org
(
    id           UUID         PRIMARY KEY,
    slug         VARCHAR(64)  NOT NULL UNIQUE,
    display_name VARCHAR(128) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL
);

CREATE TABLE sso_team
(
    id            UUID         PRIMARY KEY,
    org_id        UUID         NOT NULL REFERENCES sso_org (id),
    idp_issuer    VARCHAR(256) NOT NULL,
    idp_group_id  VARCHAR(256) NOT NULL,
    name          VARCHAR(128) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_sso_team_binding UNIQUE (org_id, idp_issuer, idp_group_id)
);

CREATE INDEX ix_sso_team_org ON sso_team (org_id);

CREATE TABLE sso_membership
(
    user_id    UUID        NOT NULL,
    team_id    UUID        NOT NULL REFERENCES sso_team (id),
    role       VARCHAR(16) NOT NULL,
    status     VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_sso_membership PRIMARY KEY (user_id, team_id)
);

CREATE INDEX ix_sso_membership_team_status ON sso_membership (team_id, status);
CREATE INDEX ix_sso_membership_user ON sso_membership (user_id);
