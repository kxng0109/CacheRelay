package io.github.kxng0109.cacherelay.ledger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import io.github.kxng0109.cacherelay.SharedContainersBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the {@code dashboard_daily_bucket} table end to end: the full Flyway chain
 * (V1–V19) migrates, lazy buckets round-trip, range reads stay scope-isolated, and
 * the grain unique constraint rejects duplicates.
 */
@DisplayName("Dashboard bucket repository against real PostgreSQL")
class DashboardBucketRepositoryIT extends SharedContainersBase {

	private DashboardBucketRepository repository;

	private SimpleJpaRepository<DashboardBucket, UUID> raw;

	private EntityManager entityManager;

	@BeforeEach
	void migrate() {
		PGSimpleDataSource dataSource = SharedContainersBase.newDataSource();

		LocalContainerEntityManagerFactoryBean factoryBean = new LocalContainerEntityManagerFactoryBean();
		factoryBean.setDataSource(dataSource);
		factoryBean.setPackagesToScan(DashboardBucket.class.getPackageName());
		factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factoryBean.afterPropertiesSet();
		entityManager = factoryBean.getObject().createEntityManager();
		repository = new JpaRepositoryFactory(entityManager).getRepository(DashboardBucketRepository.class);
		raw = new SimpleJpaRepository<>(DashboardBucket.class, entityManager);
		clean();
	}

	private void clean() {
		EntityTransaction transaction = entityManager.getTransaction();
		transaction.begin();
		try {
			raw.deleteAll();
			transaction.commit();
		} catch (RuntimeException ex) {
			if (transaction.isActive()) {
				transaction.rollback();
			}
			throw ex;
		} finally {
			entityManager.clear();
		}
	}

	private void save(DashboardBucket entity) {
		EntityTransaction transaction = entityManager.getTransaction();
		transaction.begin();
		try {
			raw.save(entity);
			transaction.commit();
		} catch (RuntimeException ex) {
			if (transaction.isActive()) {
				transaction.rollback();
			}
			throw ex;
		} finally {
			entityManager.clear();
		}
	}

	private static DashboardBucket bucket(String scopeType, String scopeKey, LocalDate day,
			String owner, String provider, String model) {
		return new DashboardBucket(UUID.randomUUID(), scopeType, scopeKey, day, owner, provider, model,
				10L, 1000L, 500L, 1500L, 14_000L, 13_000L, 13_000L, 1_200L,
				200L, 50L, 750L, 0L, Instant.now(), Instant.now());
	}

	@Test
	@DisplayName("lazy buckets round-trip with partial sums and watermark")
	void roundTrips() {
		save(bucket("PERSONAL", "user-1", LocalDate.of(2026, 9, 20), "owner-1", "", ""));

		Optional<DashboardBucket> found = repository
				.findByScopeTypeAndScopeKeyAndBucketDayAndProviderAndModel(
						"PERSONAL", "user-1", LocalDate.of(2026, 9, 20), "", "");

		assertThat(found).isPresent();
		assertThat(found.get().getRequests()).isEqualTo(10L);
		assertThat(found.get().getBilledMicros()).isEqualTo(13_000L);
		assertThat(found.get().getWatermark()).isNotNull();
	}

	@Test
	@DisplayName("range reads stay isolated to the requested scope and day span")
	void rangeReadsIsolated() {
		save(bucket("PERSONAL", "user-1", LocalDate.of(2026, 9, 20), "owner-1", "", ""));
		save(bucket("PERSONAL", "user-1", LocalDate.of(2026, 9, 21), "owner-1", "openai", "gpt-4o"));
		save(bucket("PERSONAL", "user-2", LocalDate.of(2026, 9, 20), "owner-2", "", ""));
		save(bucket("ORG", "global", LocalDate.of(2026, 9, 20), "owner-1", "", ""));

		List<DashboardBucket> rows = repository.findByScopeTypeAndScopeKeyAndBucketDayBetween(
				"PERSONAL", "user-1", LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 21));

		assertThat(rows).hasSize(2);
		assertThat(rows).allMatch(row -> row.getScopeKey().equals("user-1"));
	}

	@Test
	@DisplayName("duplicate grain rows violate the unique constraint")
	void duplicateGrainRejected() {
		save(bucket("PERSONAL", "user-1", LocalDate.of(2026, 9, 20), "owner-1", "", ""));

		assertThatThrownBy(() -> save(bucket("PERSONAL", "user-1",
				LocalDate.of(2026, 9, 20), "owner-1", "", "")))
				.isInstanceOf(RuntimeException.class);
	}
}
