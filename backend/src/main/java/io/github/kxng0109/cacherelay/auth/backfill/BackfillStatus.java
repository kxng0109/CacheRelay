package io.github.kxng0109.cacherelay.auth.backfill;

/**
 * One backfill attempt verdict. Token-complete logins skip fetching entirely
 * and must never deny; only attempted-but-failed fetches deny.
 */
public enum BackfillStatus {
	/** Claims suffice; no fetch attempted. */
	SKIPPED,
	/** Fetch succeeded; result carries groups and the disabled flag. */
	SUCCEEDED,
	/** Fetch attempted and failed; deny the login. */
	FAILED
}
