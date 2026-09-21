package io.github.kxng0109.cacherelay.model;

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
 * Proves the {@code model_alias} table end to end: the full Flyway chain
 * (V1–V16) migrates, definitions round-trip with timestamps, listings work,
 * and deletes remove rows.
 */
@DisplayName("Model alias repository against real PostgreSQL")
@Testcontainers
class ModelAliasRepositoryIT {

	@Container
	static final PostgreSQLContainer POSTGRES =
			new PostgreSQLContainer(DockerImageName.parse("postgres:16.15-alpine"));

	private SimpleJpaRepository<ModelAliasDefinition, String> repository;

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
		factoryBean.setPackagesToScan(ModelAliasDefinition.class.getPackageName());
		factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factoryBean.afterPropertiesSet();
		entityManager = factoryBean.getObject().createEntityManager();
		repository = new SimpleJpaRepository<>(ModelAliasDefinition.class, entityManager);
	}

	private void save(ModelAliasDefinition definition) {
		EntityTransaction transaction = entityManager.getTransaction();
		transaction.begin();
		try {
			repository.save(definition);
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

	private void delete(String name) {
		EntityTransaction transaction = entityManager.getTransaction();
		transaction.begin();
		try {
			repository.deleteById(name);
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
	@DisplayName("save and exact read round-trip")
	void saveAndExactRead() {
		String chain = "[{\"providerName\":\"openai\",\"modelOverride\":\"gpt-5.6-luna\"}]";
		save(new ModelAliasDefinition("fast-gpt", chain, "SEQUENTIAL"));

		Optional<ModelAliasDefinition> found = repository.findById("fast-gpt");

		assertThat(found).isPresent();
		assertThat(found.get().getChainJson()).isEqualTo(chain);
		assertThat(found.get().getStrategy()).isEqualTo("SEQUENTIAL");
		assertThat(found.get().getCreatedAt()).isNotNull();
		assertThat(found.get().getUpdatedAt()).isNotNull();
	}

	@Test
	@DisplayName("findAll lists every definition")
	void findAllListsDefinitions() {
		save(new ModelAliasDefinition("model-a", "[]", "SEQUENTIAL"));
		save(new ModelAliasDefinition("model-b", "[]", "RACE"));

		List<ModelAliasDefinition> all = repository.findAll();

		assertThat(all).extracting(ModelAliasDefinition::getName)
				.contains("model-a", "model-b");
	}

	@Test
	@DisplayName("delete removes the row")
	void deleteRemovesRow() {
		save(new ModelAliasDefinition("doomed", "[]", "SEQUENTIAL"));

		delete("doomed");

		assertThat(repository.findById("doomed")).isEmpty();
	}

	@Test
	@DisplayName("unknown name reads empty")
	void unknownNameReadsEmpty() {
		assertThat(repository.findById("ghost")).isEmpty();
	}
}
