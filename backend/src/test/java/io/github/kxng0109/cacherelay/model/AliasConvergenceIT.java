package io.github.kxng0109.cacherelay.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import io.github.kxng0109.cacherelay.SharedContainersBase;
import io.github.kxng0109.cacherelay.contracts.GatewayProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import jakarta.persistence.EntityManager;
import tools.jackson.databind.ObjectMapper;

/**
 * ADM-B05 fleet convergence against real PostgreSQL: pod A inserts an alias
 * row and notifies; pod B's listener rebuilds without a restart.
 */
@DisplayName("ADM-B05 alias convergence across two pods")
class AliasConvergenceIT extends SharedContainersBase {

	private ModelAliasInvalidationListener listener;

	private GatewayProperties podBAliases;

	@BeforeEach
	void setUp() {
		PGSimpleDataSource dataSource = SharedContainersBase.newDataSource();
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("TRUNCATE model_alias");

		LocalContainerEntityManagerFactoryBean factoryBean = new LocalContainerEntityManagerFactoryBean();
		factoryBean.setDataSource(dataSource);
		factoryBean.setPackagesToScan(ModelAliasDefinition.class.getPackageName());
		factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factoryBean.afterPropertiesSet();
		EntityManager entityManager = factoryBean.getObject().createEntityManager();
		ModelAliasRepository repository =
				new JpaRepositoryFactory(entityManager).getRepository(ModelAliasRepository.class);

		podBAliases = new GatewayProperties();
		ModelAliasRegistry podB = new ModelAliasRegistry(repository, podBAliases, new ObjectMapper());
		listener = new ModelAliasInvalidationListener(dataSource, podB);
		listener.start();
	}

	@AfterEach
	void tearDown() {
		if (listener != null) {
			listener.stop();
		}
	}

	@Test
	@DisplayName("pod B serves pod A's alias within the convergence bound")
	void podBServesPodAAlias() {
		PGSimpleDataSource dataSource = SharedContainersBase.newDataSource();
		JdbcTemplate podA = new JdbcTemplate(dataSource);
		Instant now = Instant.now();
		Timestamp stamped = Timestamp.from(now);
		podA.update(
				"INSERT INTO model_alias (name, chain_json, strategy, created_at, updated_at)"
						+ " VALUES (?, ?, ?, ?, ?)",
				"pod-a-model",
				"[{\"providerName\":\"openai-main\",\"modelOverride\":null}]",
				"SEQUENTIAL",
				stamped,
				stamped);
		podA.execute("SELECT pg_notify('model_alias_changed', 'pod-a-model')");

		// The listener has no readiness latch: a notify sent before its
		// LISTEN is active is lost (PG notifies active listeners only), so
		// retry the notify across poll windows instead of trusting one shot.
		boolean converged = false;
		for (int attempt = 0; attempt < 3 && !converged; attempt++) {
			if (attempt > 0) {
				podA.execute("SELECT pg_notify('model_alias_changed', 'pod-a-model')");
			}
			converged = pollForAlias("pod-a-model", Duration.ofSeconds(10));
		}

		assertThat(converged)
				.as("pod B converges on pod A's alias")
				.isTrue();
		assertThat(podBAliases.getAliases()).containsKey("pod-a-model");
	}

	private boolean pollForAlias(String name, Duration bound) {
		long deadline = System.nanoTime() + bound.toNanos();
		while (System.nanoTime() < deadline) {
			if (podBAliases.getAliases().containsKey(name)) {
				return true;
			}
			try {
				Thread.sleep(100L);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return false;
	}
}
