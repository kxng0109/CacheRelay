package io.github.kxng0109.cacherelay.auth.backfill;

import java.util.Map;

/**
 * One backfill verdict: the IdP's group view for a single user plus whether
 * the IdP reports the account disabled.
 *
 * <p>Group entries map stable group ids to display names (falling back to the
 * id where the IdP omits names for cosmetic reasons). A {@code null}
 * displayName where the IdP signals a permission deficit is never
 * represented here — clients fail the whole fetch instead.</p>
 *
 * @param groups   group id to display name in encounter order, never {@code null}
 * @param disabled whether the IdP reports the account disabled or deleted
 */
public record BackfillResult(
		Map<String, String> groups,
		boolean disabled
) {

	/**
	 * Verdict for an IdP-disabled or deleted account: no groups, deny login.
	 *
	 * @return disabled verdict, never {@code null}
	 */
	public static BackfillResult forDisabledAccount() {
		return new BackfillResult(Map.of(), true);
	}
}
