package io.github.kxng0109.cacherelay.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import io.github.kxng0109.cacherelay.SharedContainersBase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the revalidation watermark domain against the shared Postgres:
 * missing watermarks surface for self-seeding, overdue claims order oldest
 * first, and fresh rows stay unclaimed.
 */
@DisplayName("SSO revalidation watermarks against real PostgreSQL")
class SsoRevalidationIT extends SharedContainersBase {

	private SsoRevalidationRepository watermarks;

	private SsoLinkRepository links;

	private UserAccountRepository accounts;

	private EntityManager entityManager;

	@BeforeEach
	void migrate() {
		LocalContainerEntityManagerFactoryBean factoryBean = new LocalContainerEntityManagerFactoryBean();
		factoryBean.setDataSource(SharedContainersBase.newDataSource());
		factoryBean.setPackagesToScan(SsoRevalidation.class.getPackageName());
		factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factoryBean.afterPropertiesSet();
		entityManager = factoryBean.getObject().createEntityManager();
		JpaRepositoryFactory factory = new JpaRepositoryFactory(entityManager);
		watermarks = factory.getRepository(SsoRevalidationRepository.class);
		links = factory.getRepository(SsoLinkRepository.class);
		accounts = factory.getRepository(UserAccountRepository.class);
		clean();
	}

	private UUID account(String username) {
		UserAccount[] holder = new UserAccount[1];
		transact(() -> holder[0] = accounts.save(new UserAccount(username, null, null, false)));
		return holder[0].getId();
	}

	private void clean() {
		transact(() -> {
			new SimpleJpaRepository<>(SsoRevalidation.class, entityManager).deleteAll();
			new SimpleJpaRepository<>(SsoLink.class, entityManager).deleteAll();
			new SimpleJpaRepository<>(UserAccount.class, entityManager).deleteAll();
		});
	}

	private void transact(Runnable work) {
		EntityTransaction transaction = entityManager.getTransaction();
		transaction.begin();
		try {
			work.run();
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
	@DisplayName("links without watermarks surface for self-seeding")
	void missingWatermarksSurface() {
		UUID seeded = account("seeded-user");
		UUID missing = account("missing-user");
		transact(() -> {
			links.save(new SsoLink(seeded, "iss", "sub-1", "azure"));
			links.save(new SsoLink(missing, "iss", "sub-2", "azure"));
			watermarks.save(new SsoRevalidation(seeded, Instant.now(),
					RevalidationStatus.ACTIVE));
		});

		List<UUID> candidates = watermarks.findUserIdsMissingWatermark(
				PageRequest.of(0, 10));

		assertThat(candidates).containsExactly(missing);
		assertThat(links.findByUserId(missing)).hasSize(1);
		assertThat(links.findByUserId(UUID.randomUUID())).isEmpty();
		assertThat(links.findBySubject("sub-2")).hasSize(1);
		assertThat(links.findBySubject("nobody")).isEmpty();
	}

	@Test
	@DisplayName("overdue claims order oldest first and skip fresh rows")
	void overdueClaimsOrdered() {
		Instant now = Instant.now();
		UUID old = UUID.randomUUID();
		UUID newer = UUID.randomUUID();
		UUID fresh = UUID.randomUUID();
		transact(() -> {
			watermarks.save(new SsoRevalidation(old, now.minusSeconds(3600),
					RevalidationStatus.ACTIVE));
			watermarks.save(new SsoRevalidation(newer, now.minusSeconds(60),
					RevalidationStatus.ACTIVE));
			watermarks.save(new SsoRevalidation(fresh, now, RevalidationStatus.ACTIVE));
		});

		List<SsoRevalidation>[] claimed = new List[1];
		transact(() -> claimed[0] = watermarks.findDueForRevalidation(
				now.minusSeconds(30), PageRequest.of(0, 10)));

		assertThat(claimed[0]).extracting(SsoRevalidation::getUserId)
				.containsExactly(old, newer);
	}
}
