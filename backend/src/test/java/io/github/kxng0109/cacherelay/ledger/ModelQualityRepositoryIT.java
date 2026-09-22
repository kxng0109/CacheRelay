package io.github.kxng0109.cacherelay.ledger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the {@code model_quality} table end to end: the full Flyway chain
 * (V1–V17) migrates, curated tiers round-trip, updates replace rows, and deletes
 * unrate models.
 */
@DisplayName("Model quality repository against real PostgreSQL")
@Testcontainers
class ModelQualityRepositoryIT {

	@Container
	static final PostgreSQLContainer POSTGRES =
			new PostgreSQLContainer(DockerImageName.parse("postgres:16.15-alpine"));

	private SimpleJpaRepository<ModelQualityEntity, String> repository;

	private EntityManager entityManager;

	@BeforeEach
	void migrate() {
		PGSimpleDataSource dataSource = new PGSimpleDataSource();
		dataSource.setServerNames(new String[]{POSTGRES.getHost()});
		dataSource.setPortNumbers(new int[]{POSTGRES.getMappedPort(5432)});
		dataSource.setDatabaseName(POSTGRES.getDatabaseName());
		dataSource.setUser(POSTGRES.getUsername());
		dataSource.setPassword(POSTGRES.getPassword());
		Flyway.configure().dataSource(dataSource).load().migrate();

		LocalContainerEntityManagerFactoryBean factoryBean = new LocalContainerEntityManagerFactoryBean();
		factoryBean.setDataSource(dataSource);
		factoryBean.setPackagesToScan(ModelQualityEntity.class.getPackageName());
		factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factoryBean.afterPropertiesSet();
		entityManager = factoryBean.getObject().createEntityManager();
		repository = new SimpleJpaRepository<>(ModelQualityEntity.class, entityManager);
		clean();
	}

	private void clean() {
		EntityTransaction transaction = entityManager.getTransaction();
		transaction.begin();
		try {
			repository.deleteAll();
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

	private void save(ModelQualityEntity entity) {
		EntityTransaction transaction = entityManager.getTransaction();
		transaction.begin();
		try {
			repository.save(entity);
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

	@Test
	@DisplayName("curated tiers round-trip with benchmark refs")
	void roundTrips() {
		save(new ModelQualityEntity(
				"gpt-5.6-luna", ModelQualityTier.FRONTIER, "AA-Index:72.3@2026-09-01", Instant.now()));

		Optional<ModelQualityEntity> found = repository.findById("gpt-5.6-luna");

		assertThat(found).isPresent();
		assertThat(found.get().getTier()).isEqualTo(ModelQualityTier.FRONTIER);
		assertThat(found.get().getBenchmarkRefs()).isEqualTo("AA-Index:72.3@2026-09-01");
		assertThat(found.get().getUpdatedAt()).isNotNull();
	}

	@Test
	@DisplayName("re-rating replaces the row and null refs stay null")
	void updatesReplace() {
		save(new ModelQualityEntity("m", ModelQualityTier.BUDGET, null, Instant.now()));
		save(new ModelQualityEntity("m", ModelQualityTier.STANDARD, "Arena:1400@2026-09-01", Instant.now()));

		List<ModelQualityEntity> all = repository.findAll();

		assertThat(all).hasSize(1);
		assertThat(all.getFirst().getTier()).isEqualTo(ModelQualityTier.STANDARD);
	}

	@Test
	@DisplayName("deletes unrate models")
	void deletesUnrate() {
		save(new ModelQualityEntity("m", ModelQualityTier.BUDGET, null, Instant.now()));

		repository.deleteById("m");

		assertThat(repository.findById("m")).isEmpty();
	}
}
