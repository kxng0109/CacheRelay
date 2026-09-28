package io.github.kxng0109.cacherelay.a2a.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.jspecify.annotations.Nullable;

import java.time.Duration;

/**
 * Task-ownership index for A2A {@code tasks/get} and {@code tasks/cancel} (A2A-B02).
 *
 * <p>Upstream agents authenticate the gateway with one shared credential, so task ids are
 * bearer-equivalent under that credential: whoever guesses a task id could read or cancel another
 * tenant's task. The index records the owning tenant when a task is created through this gateway
 * (from {@code message/send} results and {@code message/stream} first frames) and the proxy
 * denies {@code tasks/get} / {@code tasks/cancel} for unknown or foreign-owned ids with an
 * indistinguishable 404 — never forwarding them upstream.</p>
 *
 * <p>Entries expire after the configured TTL and the map is size-bounded, so the index itself is
 * not an unbounded growth vector. A task id absent from the index (expired, evicted, or created
 * outside this gateway) is denied fail-closed.</p>
 */
public final class A2aTaskOwnershipIndex {

	private final Cache<String, String> owners;

	/**
	 * @param maximumSize maximum tracked tasks (evicts least-recently-used beyond it)
	 * @param ttl         how long a task binding is remembered
	 */
	public A2aTaskOwnershipIndex(long maximumSize, Duration ttl) {
		this.owners = Caffeine.newBuilder()
				.maximumSize(Math.max(1, maximumSize))
				.expireAfterWrite(ttl)
				.build();
	}

	/**
	 * Records the tenant that created a task.
	 *
	 * @param agentName registered agent name
	 * @param taskId    upstream task id, ignored when blank
	 * @param ownerId   owning tenant, ignored when {@code null}
	 */
	public void record(String agentName, @Nullable String taskId, @Nullable String ownerId) {
		if (taskId == null || taskId.isBlank() || ownerId == null) {
			return;
		}
		owners.put(key(agentName, taskId), ownerId);
	}

	/**
	 * Returns the owning tenant of a task.
	 *
	 * @param agentName registered agent name
	 * @param taskId    upstream task id
	 * @return owner tenant, or {@code null} when unknown
	 */
	public @Nullable String ownerOf(String agentName, String taskId) {
		return owners.getIfPresent(key(agentName, taskId));
	}

	private static String key(String agentName, String taskId) {
		return agentName + "\u0000" + taskId;
	}
}
