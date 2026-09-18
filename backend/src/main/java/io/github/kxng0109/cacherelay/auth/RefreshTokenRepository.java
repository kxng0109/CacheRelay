package io.github.kxng0109.cacherelay.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Persistence for {@link RefreshToken}. Rotation and revocation go through atomic
 * conditional updates so concurrent refreshes cannot both succeed.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

	/**
	 * Finds a live token row by its hash.
	 *
	 * @param tokenHash SHA-256 hex of the presented token
	 * @return the row, or empty
	 */
	Optional<RefreshToken> findByTokenHash(String tokenHash);

	/**
	 * Marks a presented row replaced, but only when it is still live (not replaced,
	 * not revoked). Returns {@code 1} when this caller won the rotation.
	 *
	 * @param id         row to replace
	 * @param replacedBy successor row id
	 * @return affected rows (0 or 1)
	 */
	@Modifying
	@Query("UPDATE RefreshToken t SET t.replacedBy = :replacedBy WHERE t.id = :id "
			+ "AND t.replacedBy IS NULL AND t.revokedAt IS NULL")
	int markReplaced(UUID id, UUID replacedBy);

	/**
	 * Revokes every row in a family (reuse detected). Returns the affected count.
	 *
	 * @param familyId family to revoke
	 * @param now      revocation timestamp
	 * @return affected rows
	 */
	@Modifying
	@Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.familyId = :familyId "
			+ "AND t.revokedAt IS NULL")
	int revokeFamily(UUID familyId, Instant now);

	/**
	 * Deletes rows fully expired past both idle and absolute horizons (janitor).
	 *
	 * @param idleCutoff     rows with {@code expiresAt} before this are idle-expired
	 * @param absoluteCutoff rows with {@code absoluteExpiresAt} before this hit the ceiling
	 * @return deleted rows
	 */
	@Modifying
	@Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :idleCutoff "
			+ "OR t.absoluteExpiresAt < :absoluteCutoff")
	int deleteExpired(Instant idleCutoff, Instant absoluteCutoff);
}
