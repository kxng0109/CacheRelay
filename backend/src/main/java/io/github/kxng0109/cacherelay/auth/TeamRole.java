package io.github.kxng0109.cacherelay.auth;

/**
 * Team-scoped roles. Team lead is the maximum IdP-derivable privilege:
 * gateway admin stays locally assigned and never derives from claims.
 */
public enum TeamRole {
	/** Regular team member. */
	MEMBER,
	/** Team lead: manages team-scoped resources, never gateway admin. */
	LEAD
}
