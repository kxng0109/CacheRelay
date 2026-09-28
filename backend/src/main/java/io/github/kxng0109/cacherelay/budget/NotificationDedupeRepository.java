package io.github.kxng0109.cacherelay.budget;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link NotificationDedupe}.
 */
public interface NotificationDedupeRepository extends JpaRepository<NotificationDedupe, UUID> {

	boolean existsByDedupeShaAndChannelAndTarget(String dedupeSha, String channel, String target);

	/**
	 * Releases a send claim (transient failure path): the next redelivery may
	 * claim and send again instead of suppressing the alert permanently.
	 *
	 * @param dedupeSha alert fingerprint
	 * @param channel   channel name
	 * @param target    destination
	 */
	void deleteByDedupeShaAndChannelAndTarget(String dedupeSha, String channel, String target);
}
