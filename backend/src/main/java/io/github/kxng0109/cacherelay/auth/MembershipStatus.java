package io.github.kxng0109.cacherelay.auth;

/**
 * Membership lifecycle states. Removal from the IdP side flips ACTIVE to
 * INACTIVE on next login; rows are never deleted so history survives.
 */
public enum MembershipStatus {
	/** Currently a team member. */
	ACTIVE,
	/** Removed IdP-side or superseded; kept for history. */
	INACTIVE
}
