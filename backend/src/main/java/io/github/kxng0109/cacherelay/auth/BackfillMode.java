package io.github.kxng0109.cacherelay.auth;

/**
 * First-login group backfill strategies. Token claims stay the primary
 * source; backfill runs only when claims cannot carry membership and blocks
 * the first login, failing closed on any error.
 */
public enum BackfillMode {
	/** No backfill; claims only. */
	NONE,
	/** Microsoft Graph transitive membership (Entra overage). */
	ENTRA_GRAPH,
	/** Okta Users API groups (claim fallback). */
	OKTA_API,
	/** Google Workspace Directory API (no token groups exist). */
	GOOGLE_DIRECTORY,
	/** GitHub REST org/team membership with the user's OAuth token. */
	GITHUB_API
}
