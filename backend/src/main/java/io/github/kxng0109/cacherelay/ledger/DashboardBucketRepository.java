package io.github.kxng0109.cacherelay.ledger;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link DashboardBucket} lazy daily grains.
 */
public interface DashboardBucketRepository extends JpaRepository<DashboardBucket, UUID> {

	/**
	 * Reads every bucket grain for one scope inside a day span, totals and breakdowns.
	 *
	 * @param scopeType scope grain, never {@code null}
	 * @param scopeKey  scope identity, never {@code null}
	 * @param from      first day inclusive, never {@code null}
	 * @param to        last day inclusive, never {@code null}
	 * @return matching buckets in no guaranteed order
	 */
	List<DashboardBucket> findByScopeTypeAndScopeKeyAndBucketDayBetween(
			String scopeType, String scopeKey, LocalDate from, LocalDate to);

	/**
	 * Reads one bucket grain.
	 *
	 * @param scopeType scope grain, never {@code null}
	 * @param scopeKey  scope identity, never {@code null}
	 * @param day       bucket day, never {@code null}
	 * @param provider  provider grain ({@code ""} for totals), never {@code null}
	 * @param model     model grain ({@code ""} for totals), never {@code null}
	 * @return the grain row when present
	 */
	Optional<DashboardBucket> findByScopeTypeAndScopeKeyAndBucketDayAndProviderAndModel(
			String scopeType, String scopeKey, LocalDate day, String provider, String model);
}
