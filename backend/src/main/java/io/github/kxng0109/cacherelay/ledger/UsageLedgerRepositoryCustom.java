package io.github.kxng0109.cacherelay.ledger;

import io.github.kxng0109.cacherelay.admin.dto.LedgerFilter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Custom aggregation and dynamic filtering queries for the usage ledger.
 */
public interface UsageLedgerRepositoryCustom {

	/**
	 * Computes global or tenant-scoped totals for requests matching the filter.
	 *
	 * @param filter active filter criteria
	 * @return aggregated totals
	 */
	UsageTotals getTotals(LedgerFilter filter);

	/**
	 * Computes usage totals aggregated by tenant or owner.
	 *
	 * @param filter active filter criteria
	 * @return usage records grouped by ownerId
	 */
	List<OwnerUsageRecord> getBreakdownByOwner(LedgerFilter filter);

	/**
	 * Computes usage totals aggregated by provider and model.
	 *
	 * @param filter active filter criteria
	 * @return usage records grouped by provider and model
	 */
	List<ModelUsageRecord> getBreakdownByModel(LedgerFilter filter);

	/**
	 * Computes usage totals aggregated by provider.
	 *
	 * @param filter active filter criteria
	 * @return usage records grouped by provider
	 */
	List<ProviderUsageRecord> getBreakdownByProvider(LedgerFilter filter);

	/**
	 * Computes exact detail grains grouped by owner, provider, and model.
	 *
	 * @param ownerIds owner identifiers to include, or {@code null} for all owners
	 * @param from     window start inclusive, or {@code null} for no lower bound
	 * @param to       window end inclusive, or {@code null} for no upper bound
	 * @return detail grains ordered by cost descending
	 */
	List<OwnerModelUsageRecord> getDetailRows(Set<String> ownerIds, Instant from, Instant to);

	/**
	 * Searches ledger entries with dynamic filters and pagination.
	 *
	 * @param filter   active filter criteria
	 * @param pageable pagination parameters
	 * @return paginated entries
	 */
	Page<UsageLedgerEntry> findEntries(LedgerFilter filter, Pageable pageable);
}
