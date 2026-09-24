package io.github.kxng0109.cacherelay.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

/**
 * Persistence for {@link SsoRevalidation} watermarks.
 */
public interface SsoRevalidationRepository extends JpaRepository<SsoRevalidation, UUID> {

	/**
	 * Finds linked accounts without a watermark row for self-seeding.
	 *
	 * @param pageable batch window, never {@code null}
	 * @return user ids lacking watermarks
	 */
	@Query("SELECT link.userId FROM SsoLink link WHERE link.userId NOT IN"
			+ " (SELECT watermark.userId FROM SsoRevalidation watermark)")
	List<UUID> findUserIdsMissingWatermark(Pageable pageable);

	/**
	 * Claims one overdue batch, oldest first, skipping rows locked by a
	 * sibling instance.
	 *
	 * @param cutoff upper bound for staleness, never {@code null}
	 * @param pageable batch window, never {@code null}
	 * @return claimed watermarks oldest first
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
	@Query("SELECT watermark FROM SsoRevalidation watermark"
			+ " WHERE watermark.lastVerifiedAt < :cutoff ORDER BY watermark.lastVerifiedAt ASC")
	List<SsoRevalidation> findDueForRevalidation(Instant cutoff, Pageable pageable);
}
