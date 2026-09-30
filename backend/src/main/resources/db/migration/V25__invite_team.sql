-- Local team placement for invites: an optional target team recorded at invite
-- creation and honored atomically at redemption.
--
-- The column is nullable (link-only and admin invites carry no placement) and
-- references sso_team with the default RESTRICT behavior: deleting a team that
-- still backs pending invites must be refused in application code (409), never
-- silently unplaced by the database. No backfill: existing rows stay NULL.
ALTER TABLE auth_invite ADD COLUMN team_id UUID REFERENCES sso_team (id);
