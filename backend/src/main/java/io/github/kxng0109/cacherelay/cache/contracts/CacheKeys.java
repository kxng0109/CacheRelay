package io.github.kxng0109.cacherelay.cache.contracts;

import org.jspecify.annotations.Nullable;

/**
 * Single owner of {@code cacherelay:cache:*} Redis key namespaces.
 *
 * <p>Exact-match keys, vector document keys, and purge glob patterns are built here so the write path and the
 * administrative purge path can never drift apart. A rename touches one constant; every caller follows.</p>
 */
public final class CacheKeys {

	/** Prefix for L1 exact key-value entries. */
	public static final String EXACT_PREFIX = "cacherelay:cache:exact:";

	/** Prefix for L2 vector document entries. */
	public static final String DOC_PREFIX = "cacherelay:cache:doc:";

	/** RediSearch index name for L2 vector similarity search. */
	public static final String INDEX_NAME = "cacherelay:cache:idx";

	private CacheKeys() {
	}

	/**
	 * Builds the Redis key for an L1 exact entry.
	 *
	 * @param ownerId tenant identifier, never {@code null}
	 * @param exactHash SHA-256 digest of the normalized request payload, never {@code null}
	 * @return Redis exact match key
	 */
	public static String exactKey(String ownerId, String exactHash) {
		return EXACT_PREFIX + ownerId + ":" + exactHash;
	}

	/**
	 * Builds the Redis key for an L2 vector document entry.
	 *
	 * @param ownerId tenant identifier, never {@code null}
	 * @param entryId unique entry identifier, never {@code null}
	 * @return Redis vector document key
	 */
	public static String docKey(String ownerId, String entryId) {
		return DOC_PREFIX + ownerId + ":" + entryId;
	}

	/**
	 * Builds the tenant-scoped purge glob for L1 exact entries.
	 *
	 * @param ownerId tenant identifier, may be {@code null} only when the caller already guards scope
	 * @return tenant purge pattern
	 */
	public static String exactTenantPattern(@Nullable String ownerId) {
		return EXACT_PREFIX + ownerId + ":*";
	}

	/**
	 * Builds the tenant-scoped purge glob for L2 vector document entries.
	 *
	 * @param ownerId tenant identifier, may be {@code null} only when the caller already guards scope
	 * @return tenant purge pattern
	 */
	public static String docTenantPattern(@Nullable String ownerId) {
		return DOC_PREFIX + ownerId + ":*";
	}
}
