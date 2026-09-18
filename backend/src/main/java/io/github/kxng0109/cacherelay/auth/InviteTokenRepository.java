package io.github.kxng0109.cacherelay.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Persistence for {@link InviteToken}. Consumption is atomic: exactly one redeemer wins.
 */
public interface InviteTokenRepository extends JpaRepository<InviteToken, UUID> {

	/**
	 * Finds an invite by its hash.
	 *
	 * @param tokenHash SHA-256 hex of the presented token
	 * @return the invite, or empty
	 */
	Optional<InviteToken> findByTokenHash(String tokenHash);

	/**
	 * Consumes an invite, but only when unconsumed. Returns {@code 1} when this caller won.
	 *
	 * @param id         invite to consume
	 * @param consumedBy redeeming account
	 * @param now        consumption timestamp
	 * @return affected rows (0 or 1)
	 */
	@Modifying
	@Query("UPDATE InviteToken i SET i.consumedAt = :now, i.consumedBy = :consumedBy "
			+ "WHERE i.id = :id AND i.consumedAt IS NULL")
	int consume(UUID id, UUID consumedBy, Instant now);

	/**
	 * Deletes expired, never-consumed invites (janitor).
	 *
	 * @param now cutoff timestamp
	 * @return deleted rows
	 */
	@Modifying
	@Query("DELETE FROM InviteToken i WHERE i.expiresAt < :now AND i.consumedAt IS NULL")
	int deleteExpiredUnconsumed(Instant now);
}
