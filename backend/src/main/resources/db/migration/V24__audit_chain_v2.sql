-- FIN-B34: versioned audit digest covering the money-bearing payload.
--
-- V13's row_hash binds only (prev, actor, action, level, subject_id): limits
-- and timestamps are tamperable without breaking the chain. chain_v2 binds
-- the same predecessor link plus before_json, after_json, and created_at, so
-- any mutation of a money-bearing field is detectable. The v1 columns stay
-- untouched (existing verifiers keep working).
--
-- created_at renders in UTC explicitly: timestamptz text output follows the
-- server TimeZone setting, which would make digests environment-dependent.
ALTER TABLE budget_audit ADD COLUMN chain_v2 VARCHAR(64);

DO $$
DECLARE
	prev_v2 VARCHAR(64);
	link VARCHAR(64);
	rec  RECORD;
BEGIN
	FOR rec IN SELECT id, prev_hash, actor, action, level, subject_id,
	                  COALESCE(before_json, '') AS before_json,
	                  COALESCE(after_json, '') AS after_json,
	                  to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US') AS created_utc
	           FROM budget_audit ORDER BY seq
	LOOP
		link := encode(sha256(convert_to(
			rec.prev_hash || rec.actor || rec.action || rec.level || rec.subject_id
			|| rec.before_json || rec.after_json || rec.created_utc, 'UTF8')), 'hex');
		UPDATE budget_audit SET chain_v2 = link WHERE id = rec.id;
	END LOOP;
END;
$$;

ALTER TABLE budget_audit ALTER COLUMN chain_v2 SET NOT NULL;

-- Replaces the V13/V22 trigger body: same seq-ordered tip (FIN-B35), plus
-- the v2 digest over the full canonical serialization.
CREATE OR REPLACE FUNCTION chain_budget_audit_row() RETURNS trigger AS $$
DECLARE
	prev VARCHAR(64);
	link VARCHAR(64);
	link_v2 VARCHAR(64);
BEGIN
	SELECT row_hash INTO prev FROM budget_audit ORDER BY seq DESC LIMIT 1;
	IF NOT FOUND THEN
		prev := 'GENESIS';
	END IF;
	link := encode(sha256(convert_to(
		prev || NEW.actor || NEW.action || NEW.level || NEW.subject_id, 'UTF8')), 'hex');
	link_v2 := encode(sha256(convert_to(
		prev || NEW.actor || NEW.action || NEW.level || NEW.subject_id
		|| COALESCE(NEW.before_json, '') || COALESCE(NEW.after_json, '')
		|| to_char(NEW.created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US'), 'UTF8')), 'hex');
	NEW.prev_hash := prev;
	NEW.row_hash := link;
	NEW.chain_v2 := link_v2;
	RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Verification query (documented here and exercised by AuditHashChainIT):
-- recomputes every v2 link from stored values; any returned id proves
-- tampering with a money-bearing field.
--   SELECT id FROM budget_audit WHERE chain_v2 != encode(sha256(convert_to(
--     prev_hash || actor || action || level || subject_id
--     || COALESCE(before_json, '') || COALESCE(after_json, '')
--     || to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US'), 'UTF8')), 'hex')
--   ORDER BY seq;
