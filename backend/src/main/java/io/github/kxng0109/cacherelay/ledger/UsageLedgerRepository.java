package io.github.kxng0109.cacherelay.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

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