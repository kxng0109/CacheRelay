package io.github.kxng0109.cacherelay.admin.dto;

/**
 * How far a provider's integration has been validated.
 */
public enum ProviderValidationStatus {

	/**
	 * Docs-derived contract fixtures pass; no live contact has been observed.
	 */
	CONTRACT_CHECKED,

	/**
	 * A live request reached the provider's auth layer and drew a well-formed
	 * provider error (for example HTTP 401 on a dummy key). Proves routing, base
	 * URL, and error-envelope mapping; proves nothing about inference, quota, or
	 * billing.
	 */
	AUTH_REACHABLE,

	/**
	 * Real inference succeeded end to end with a valid credential.
	 */
	LIVE_VERIFIED,

	/**
	 * No validation evidence is recorded for this name.
	 */
	UNVERIFIED
}
