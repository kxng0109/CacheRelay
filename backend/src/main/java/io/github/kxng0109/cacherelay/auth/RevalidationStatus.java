package io.github.kxng0109.cacherelay.auth;

/**
 * Last-known verification states for revalidation watermarks.
 */
public enum RevalidationStatus {
	/** IdP reported the account enabled at last check. */
	ACTIVE,
	/** IdP reported the account disabled or deleted; access revoked. */
	INACTIVE
}
