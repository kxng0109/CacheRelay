-- FIN-B35: chain tip follows database insertion order, never pod clocks.
--
-- The V13 trigger picked the tip with ORDER BY created_at DESC, but that
-- column carries the inserting pod's wall clock (BudgetAuditRecord defaults to
-- Instant.now()). A pod running ahead permanently pins the tip: every later
-- insert links the same predecessor and dies on uq_budget_audit_prev, wedging
-- admin writes until the clock catches up.
--
-- The seq identity column is assigned by the database at insert in commit
-- order, so ORDER BY seq DESC always names the true latest row regardless of
-- clocks. Concurrent inserts may still read the same tip and collide loudly
-- on the unique predecessor (admin retries) — that serialization property is
-- unchanged; only the false, clock-driven collisions are gone. Existing rows
-- keep the links V13 computed; verification walks (prev_hash, row_hash)
-- links, so backfill order never affects it.
ALTER TABLE budget_audit ADD COLUMN seq BIGINT GENERATED ALWAYS AS IDENTITY;

CREATE OR REPLACE FUNCTION chain_budget_audit_row() RETURNS trigger AS $$
DECLARE
	prev VARCHAR(64);
	link VARCHAR(64);
BEGIN
	SELECT row_hash INTO prev FROM budget_audit ORDER BY seq DESC LIMIT 1;
	IF NOT FOUND THEN
		prev := 'GENESIS';
	END IF;
	link := encode(sha256(convert_to(
		prev || NEW.actor || NEW.action || NEW.level || NEW.subject_id, 'UTF8')), 'hex');
	NEW.prev_hash := prev;
	NEW.row_hash := link;
	RETURN NEW;
END;
$$ LANGUAGE plpgsql;
