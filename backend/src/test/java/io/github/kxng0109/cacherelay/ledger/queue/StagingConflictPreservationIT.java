package io.github.kxng0109.cacherelay.ledger.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import io.github.kxng0109.cacherelay.SharedContainersBase;
import io.github.kxng0109.cacherelay.ledger.LedgerStagingEntry;
import io.github.kxng0109.cacherelay.ledger.LedgerStagingRepository;
import io.github.kxng0109.cacherelay.ledger.SpillwayJournalManager;
import io.github.kxng0109.cacherelay.ledger.TokenUsageEvent;
import io.github.kxng0109.cacherelay.ledger.UsageLedgerRepository;
import io.github.kxng0109.cacherelay.ledger.*;

/**
 * FIN-B02 against real PostgreSQL: a single duplicate staging row must never
 * discard its batch-mates.
 *
 * <p>Shape: the direct ledger write fails (outage), one batch row already sits
 * in {@code usage_ledger_staging} (staged by another instance), and two fresh
 * rows share the failed batch. Every fresh request must end up staged or
 * journaled — never silently dropped by a blanket
 * {@code DataIntegrityViolationException} catch.
 */
@DisplayName("FIN-B02 staging-conflict preservation against real PostgreSQL")
class StagingConflictPreservationIT extends SharedContainersBase {

	private EntityManager entityManager;

	private LedgerStagingRepository staging;

	private UsageLedgerRepository ledgerRepository;

	private SpillwayJournalManager spillwayJournal;

	private MicroBatchLedgerWriter writer;

	private DisruptorUsageLedgerQueue queue;

	@BeforeEach
	void setUp() {
		PGSimpleDataSource dataSource = SharedContainersBase.newDataSource();
		new JdbcTemplate(dataSource).execute("TRUNCATE usage_ledger_staging");

		LocalContainerEntityManagerFactoryBean factoryBean = new LocalContainerEntityManagerFactoryBean();
		factoryBean.setDataSource(dataSource);
		factoryBean.setPackagesToScan(LedgerStagingEntry.class.getPackageName());
		factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factoryBean.afterPropertiesSet();
		entityManager = factoryBean.getObject().createEntityManager();
		SimpleJpaRepository<LedgerStagingEntry, UUID> jpa =
				new SimpleJpaRepository<>(LedgerStagingEntry.class, entityManager);
		staging = transactionalProxy(jpa);

		ledgerRepository = mock(UsageLedgerRepository.class);
		spillwayJournal = mock(SpillwayJournalManager.class);
		queue = new DisruptorUsageLedgerQueue(1024, spillwayJournal);
		writer = new MicroBatchLedgerWriter(
				queue,
				ledgerRepository,
				spillwayJournal,
				new SimpleMeterRegistry(),
				100,
				50,
				30_000L,
				60_000L,
				5
		);
		writer.setStagingRepository(staging);
	}

	private final HibernateJpaDialect jpaDialect = new HibernateJpaDialect();

	/**
	 * Wraps plain JPA calls in one resource-local transaction per repository
	 * call — exactly the boundary production gets from Spring Data repository
	 * proxies (each {@code saveAll}/{@code flush} runs in its own transaction,
	 * so a duplicate rolls back only its own call, never the caller). JPA
	 * exceptions are translated through the same Hibernate dialect Spring uses
	 * in production, so the writer observes Spring {@code DataAccessException}
	 * types (not raw {@code PersistenceException}).
	 *
	 * @param jpa backing repository sharing this test's entity manager
	 * @return staging repository with production-equivalent transactionality
	 */
	private LedgerStagingRepository transactionalProxy(SimpleJpaRepository<LedgerStagingEntry, UUID> jpa) {
		return (LedgerStagingRepository) Proxy.newProxyInstance(
				getClass().getClassLoader(),
				new Class<?>[] {LedgerStagingRepository.class},
				(proxy, method, args) -> {
					if (method.getDeclaringClass() == Object.class) {
						return method.invoke(this, args);
					}
					EntityTransaction transaction = entityManager.getTransaction();
					transaction.begin();
					try {
						Object result = method.invoke(jpa, args);
						transaction.commit();
						return result;
					} catch (RuntimeException ex) {
						rollbackQuietly(transaction);
						throw translate(ex);
					} catch (ReflectiveOperationException ex) {
						rollbackQuietly(transaction);
						Throwable cause = ex.getCause();
						RuntimeException failure = cause instanceof RuntimeException runtime
								? runtime
								: new IllegalStateException("Staging call failed", ex);
						throw translate(failure);
					} finally {
						entityManager.clear();
					}
				}
		);
	}

	/**
	 * Replicates production exception translation (Spring's
	 * {@code PersistenceExceptionTranslationPostProcessor}): raw JPA failures
	 * become Spring {@code DataAccessException} types.
	 *
	 * @param failure raw failure from the JPA call
	 * @return translated failure, never {@code null}
	 */
	private RuntimeException translate(RuntimeException failure) {
		RuntimeException translated = jpaDialect.translateExceptionIfPossible(failure);
		return translated == null ? failure : translated;
	}

	private void rollbackQuietly(EntityTransaction transaction) {
		try {
			if (transaction.isActive()) {
				transaction.rollback();
			}
		} catch (RuntimeException ignored) {
		}
	}

	private static TokenUsageEvent event(UUID requestId, String tenant) {
		return new TokenUsageEvent(
				requestId, tenant, "openai", "gpt-5.6-sol",
				100, 50, 150, 120, 1000, Instant.now()
		);
	}

	@Test
	@DisplayName("duplicate staging row preserves fresh batch-mates")
	void duplicateStagingRowPreservesBatchMates() {
		UUID duplicateId = UUID.randomUUID();
		UUID freshId1 = UUID.randomUUID();
		UUID freshId2 = UUID.randomUUID();
		staging.save(LedgerStagingEntry.pendingFrom(event(duplicateId, "tenant-dup")));

		doThrow(new RuntimeException("database outage")).when(ledgerRepository).saveAll(anyList());
		assertThat(queue.offer(event(duplicateId, "tenant-dup"))).isTrue();
		assertThat(queue.offer(event(freshId1, "tenant-1"))).isTrue();
		assertThat(queue.offer(event(freshId2, "tenant-2"))).isTrue();

		assertThat(writer.flushCycle()).isEqualTo(0);

		List<UUID> stagedIds = staging.findAll().stream()
				.map(LedgerStagingEntry::getRequestId)
				.toList();
		assertThat(stagedIds).contains(duplicateId, freshId1, freshId2);
		verify(spillwayJournal, never()).appendBatch(anyList(), anyString());
	}
}
