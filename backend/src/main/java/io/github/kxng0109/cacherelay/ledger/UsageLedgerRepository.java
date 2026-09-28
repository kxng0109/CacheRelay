package io.github.kxng0109.cacherelay.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists {@link UsageLedgerEntry} rows.
 */
public interface UsageLedgerRepository extends JpaRepository<UsageLedgerEntry, UUID>, UsageLedgerRepositoryCustom {

	/**
	 * @param requestId the correlation id of the proxied request
	 * @return the entry recorded for that request, if any
	 */
	Optional<UsageLedgerEntry> findByRequestId(UUID requestId);

	/**
	 * Counts rows created in a window (FIN-B10 bucket freshness check).
	 *
	 * @param from window start inclusive
	 * @param to   window end inclusive
	 * @return row count
	 */
	long countByCreatedAtBetween(Instant from, Instant to);

	/**
	 * Counts one owner set's rows created in a window (FIN-B10 bucket freshness check).
	 *
	 * @param ownerIds owner ids to scope to
	 * @param from     window start inclusive
	 * @param to       window end inclusive
	 * @return row count
	 */
	long countByOwnerIdInAndCreatedAtBetween(Collection<String> ownerIds, Instant from, Instant to);

	/**
	 * Retention purge for rows older than the cutoff (FIN-B39): without it the
	 * ledger grows without bound, and the dedupe horizon is undefined. The
	 * cutoff must always exceed the 90-day maximum query window — purged rows
	 * are ancient history no legal detail query can address, and dashboard
	 * grains survive as the record (see {@code DashboardService.reconcileDay}).
	 *
	 * @param cutoff exclusive upper bound on {@code createdAt}
	 * @return deleted row count
	 */
	@Modifying
	@Query("DELETE FROM UsageLedgerEntry e WHERE e.createdAt < :cutoff")
	int purgeBefore(@Param("cutoff") Instant cutoff);
	/**
	 * @param requestId the correlation id of the proxied request
	 * @return {@code true} when an entry was already recorded for it
	 */
	boolean existsByRequestId(UUID requestId);

	/**
	 * Batch existence check for micro-batch deduplication (PERF-03): one SELECT per
	 * flush instead of one per row.
	 *
	 * @param requestIds correlation ids of the candidate batch
	 * @return the subset already recorded
	 */
	List<UsageLedgerEntry> findByRequestIdIn(Collection<UUID> requestIds);
}