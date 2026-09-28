package io.github.kxng0109.cacherelay.notify;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.github.kxng0109.cacherelay.SharedContainersBase;
import io.github.kxng0109.cacherelay.budget.NotificationBounceRepository;
import io.github.kxng0109.cacherelay.budget.NotificationDedupeRepository;
import io.github.kxng0109.cacherelay.budget.NotificationLogEntry;
import io.github.kxng0109.cacherelay.budget.NotificationLogRepository;
import io.github.kxng0109.cacherelay.budget.NotificationPreference;
import io.github.kxng0109.cacherelay.budget.NotificationPreferenceRepository;
import jakarta.persistence.EntityManager;import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FIN-B14 + FIN-B15 against real PostgreSQL: fanout claims and audit rows
 * commit in their own transactions, and transient sends release the claim so
 * redelivery retries instead of suppressing permanently.
 */
@DisplayName("FIN-B14/B15 fanout persistence against real PostgreSQL")
class NotificationFanoutIT extends SharedContainersBase {

	private NotificationPreferenceRepository preferences;

	private NotificationBounceRepository bounces;

	private NotificationDedupeRepository dedupes;

	private NotificationLogRepository logs;

	private NotificationFanout fanout;

	private RecordingSender sender;

	private static class RecordingSender implements ChannelSender {

		final List<NotificationPayload> received = new ArrayList<>();

		ChannelResult result = ChannelResult.SENT;

		@Override
		public String channel() {
			return "teams";
		}

		@Override
		public ChannelResult send(NotificationPreference preference, NotificationPayload payload) {
			received.add(payload);
			return result;
		}
	}

	@BeforeEach
	void setUp() {
		PGSimpleDataSource dataSource = SharedContainersBase.newDataSource();
		LocalContainerEntityManagerFactoryBean factoryBean = new LocalContainerEntityManagerFactoryBean();
		factoryBean.setDataSource(dataSource);
		factoryBean.setPackagesToScan(NotificationPreference.class.getPackageName());
		factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factoryBean.afterPropertiesSet();
		// Container-managed shared EM: repository calls join the
		// JpaTransactionManager transactions below, exactly like production.
		EntityManager sharedEm =
				SharedEntityManagerCreator.createSharedEntityManager(factoryBean.getObject());
		JpaRepositoryFactory factory = new JpaRepositoryFactory(sharedEm);
		preferences = factory.getRepository(NotificationPreferenceRepository.class);
		bounces = factory.getRepository(NotificationBounceRepository.class);
		dedupes = factory.getRepository(NotificationDedupeRepository.class);
		logs = factory.getRepository(NotificationLogRepository.class);
		sender = new RecordingSender();
		fanout = new NotificationFanout(preferences, bounces, dedupes, logs, List.of(sender));
		JpaTransactionManager transactionManager = new JpaTransactionManager(
				factoryBean.getObject());
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.executeWithoutResult(status -> preferences.save(new NotificationPreference(
				"KEY:hex", "teams", "https://example.com/hook", null, "warning")));
		fanout.setClaimTemplate(template);
	}

	private static AlertDeliveredEvent event() {
		return new AlertDeliveredEvent("sha-fin-b15", "KEY:hex", "burn_static", "warning",
				Instant.parse("2026-09-12T14:33:00Z"), "50.00", "2026-09");
	}

	@Test
	@DisplayName("sent delivery commits claim and audit rows")
	void sentDeliveryCommitsRows() {
		long seeded = new JdbcTemplate(SharedContainersBase.newDataSource())
				.queryForObject("SELECT COUNT(*) FROM notification_preferences", Long.class);
		assertThat(seeded).as("seed committed").isEqualTo(1L);
		assertThat(preferences.findByScope("KEY:hex")).hasSize(1);

		fanout.onDelivered(event());

		assertThat(sender.received).hasSize(1);
		JdbcTemplate jdbc = new JdbcTemplate(SharedContainersBase.newDataSource());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_log", Long.class))
				.as("audit row committed")
				.isEqualTo(1L);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_dedupe", Long.class))
				.as("claim row committed")
				.isEqualTo(1L);
		List<NotificationLogEntry> audits = logs.findAll();
		assertThat(audits).hasSize(1);
		assertThat(audits.get(0).getStatus()).isEqualTo("SENT");
	}

	@Test
	@DisplayName("transient delivery releases the claim and retries on redelivery")
	void transientDeliveryRetries() {
		sender.result = ChannelResult.TRANSIENT;

		fanout.onDelivered(event());
		fanout.onDelivered(event());

		assertThat(sender.received).hasSize(2);
		assertThat(dedupes.findAll()).isEmpty();
		List<NotificationLogEntry> audits = logs.findAll();
		assertThat(audits).hasSize(2);
		assertThat(audits).extracting(NotificationLogEntry::getStatus)
				.containsOnly("FAILED");
	}
}
