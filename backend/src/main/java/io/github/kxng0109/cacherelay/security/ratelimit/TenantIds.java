package io.github.kxng0109.cacherelay.security.ratelimit;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Server-side tenant identity guard (ADM-B01, ADM-B17).
 *
 * <p>The tenant string is the cache, budget, and ledger namespace: whoever names a key's tenant
 * reads that tenant's entries. It must therefore be server-authoritative, never free-form.
 * Valid tenants are lowercase, bounded, delimiter-safe, and outside the reserved set; anything
 * else is rejected at the key-creation boundary before it can become a namespace.</p>
 */
public final class TenantIds {

	/**
	 * Tenant names that collide with internally synthesized namespaces and can never be minted.
	 */
	public static final Set<String> RESERVED = Set.of("global", "unknown");

	private static final Pattern VALID_TENANT =
			Pattern.compile("[a-z0-9](?:[a-z0-9-_]{0,62}[a-z0-9])?");

	private TenantIds() {
	}

	/**
	 * Checks a tenant identifier without throwing.
	 *
	 * @param tenant candidate tenant string, possibly {@code null}
	 * @return {@code true} when the tenant is mintable
	 */
	public static boolean isValidTenant(String tenant) {
		if (tenant == null || tenant.isEmpty() || tenant.length() > 64) {
			return false;
		}
		if (!VALID_TENANT.matcher(tenant).matches()) {
			return false;
		}
		if (tenant.contains("__")) {
			return false;
		}
		return !RESERVED.contains(tenant.toLowerCase(Locale.ROOT));
	}

	/**
	 * Rejects an unmintable tenant identifier.
	 *
	 * @param tenant candidate tenant string
	 * @return the tenant, unchanged, when valid
	 * @throws IllegalArgumentException when the tenant is malformed or reserved
	 */
	public static String requireValidTenant(String tenant) {
		if (!isValidTenant(tenant)) {
			throw new IllegalArgumentException(
					"invalid tenant id (lowercase alphanumerics, '-', '_', 1-64 chars, no '__', never "
							+ RESERVED + "): " + tenant);
		}
		return tenant;
	}
}
