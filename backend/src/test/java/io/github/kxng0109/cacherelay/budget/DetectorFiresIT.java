package io.github.kxng0109.cacherelay.budget;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import io.github.kxng0109.cacherelay.SharedContainersBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import tools.jackson.databind.ObjectMapper;

/**
 * FIN-B13 + FIN-B42 against real Redis and PostgreSQL: the detector parses
 * canonical config keys, reads the exact month counter, and fires a scoped
 * alert — no key-blind stubs.
 */
@DisplayName("FIN-B13 detector fires on canonical keys")
class DetectorFiresIT extends SharedContainersBase {

	private StringRedisTemplate redis;

	private AlertEventRepository outbox;

	private EntityManager entityManager;

	@BeforeEach
	void setUp() {
		LettuceConnectionFactory redisFactory = new LettuceConnectionFactory(
				SharedContainersBase.redisHost(), SharedContainersBase.redisPort());
		redisFactory.afterPropertiesSet();
		redis = new StringRedisTemplate(redisFactory);
		redis.afterPropertiesSet();
		redis.getConnectionFactory().getConnection().serverCommands().flushDb();

		PGSimpleDataSource dataSource = SharedContainersBase.newDataSource();
		new JdbcTemplate(dataSource).execute("TRUNCATE alert_events");
		LocalContainerEntityManagerFactoryBean factoryBean = new LocalContainerEntityManagerFactoryBean();
		factoryBean.setDataSource(dataSource);
		factoryBean.setPackagesToScan(AlertEvent.class.getPackageName());
		factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factoryBean.afterPropertiesSet();
		entityManager = factoryBean.getObject().createEntityManager();
		outbox = new JpaRepositoryFactory(entityManager).getRepository(AlertEventRepository.class);
	}

	@Test
	@DisplayName("spend at cap raises a scoped static alert on the exact counter key")
	void spendAtCapRaisesScopedAlert() {
		String hex = "ab".repeat(32);
		String month = YearMonth.now(ZoneOffset.UTC).toString();
		redis.opsForHash().putAll(
				BudgetEnforcer.cfgKey("KEY", hex),
				Map.of("minute_micros", "1000000", "month_micros", "100000"));
		String monthKey = BudgetEnforcer.monthKey("KEY", hex, month);
		redis.opsForValue().set(monthKey, "100000");

		BudgetDetector detector = new BudgetDetector(
				redis, outbox,
				new BudgetDetectionProperties(true, "", 100),
				null, new ObjectMapper());
		EntityTransaction transaction = entityManager.getTransaction();
		transaction.begin();
		try {
			detector.evaluateAll(Instant.now());
			transaction.commit();
		} catch (RuntimeException ex) {
			if (transaction.isActive()) {
				transaction.rollback();
			}
			throw ex;
		} finally {
			entityManager.clear();
		}

		assertThat(redis.opsForValue().get(monthKey))
				.as("exact month counter key addressed")
				.isEqualTo("100000");
		List<AlertEvent> alerts = outbox.findAll();
		assertThat(alerts).hasSize(1);
		assertThat(alerts.get(0).getScope()).isEqualTo("KEY:" + hex);
		assertThat(alerts.get(0).getDetector()).isEqualTo("burn_static");
		assertThat(alerts.get(0).getStatus()).isEqualTo("PENDING");
	}
}
