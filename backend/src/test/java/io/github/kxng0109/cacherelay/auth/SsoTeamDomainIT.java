package io.github.kxng0109.cacherelay.auth;

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
 * Proves the SSO team domain end to end against the shared Postgres: the full
 * Flyway chain (V1–V20) migrates once per JVM, orgs/teams/memberships
 * round-trip, bindings stay unique per org, and membership finders resolve
 * both directions.
 */
@DisplayName("SSO team domain against real PostgreSQL")
class SsoTeamDomainIT extends SharedContainersBase {

	private SsoOrgRepository orgs;

	private SsoTeamRepository teams;

	private SsoMembershipRepository memberships;

	private EntityManager entityManager;

	@BeforeEach
	void migrate() {
		PGSimpleDataSource dataSource = SharedContainersBase.newDataSource();

		LocalContainerEntityManagerFactoryBean factoryBean = new LocalContainerEntityManagerFactoryBean();
		factoryBean.setDataSource(dataSource);
		factoryBean.setPackagesToScan(SsoOrg.class.getPackageName());
		factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
		factoryBean.afterPropertiesSet();
		entityManager = factoryBean.getObject().createEntityManager();
		JpaRepositoryFactory factory = new JpaRepositoryFactory(entityManager);
		orgs = factory.getRepository(SsoOrgRepository.class);
		teams = factory.getRepository(SsoTeamRepository.class);
		memberships = factory.getRepository(SsoMembershipRepository.class);
		clean();
	}

	private void clean() {
		transact(() -> {
			new SimpleJpaRepository<>(SsoMembership.class, entityManager).deleteAll();
			new SimpleJpaRepository<>(SsoTeam.class, entityManager).deleteAll();
			new SimpleJpaRepository<>(SsoOrg.class, entityManager).deleteAll();
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
	@DisplayName("orgs resolve by slug and teams bind one group per org")
	void orgAndTeamRoundTrip() {
		SsoOrg org = new SsoOrg("acme", "Acme");
		SsoTeam team = new SsoTeam(org.getId(), "https://login.example.com/tid", "group-1", "Eng");
		transact(() -> {
			orgs.save(org);
			teams.save(team);
		});

		Optional<SsoOrg> foundOrg = orgs.findBySlug("acme");
		Optional<SsoTeam> foundTeam = teams.findByOrgIdAndIdpIssuerAndIdpGroupId(
				org.getId(), "https://login.example.com/tid", "group-1");

		assertThat(foundOrg).isPresent();
		assertThat(foundTeam).isPresent();
		assertThat(foundTeam.get().getName()).isEqualTo("Eng");
		assertThat(teams.findByOrgId(org.getId())).hasSize(1);
	}

	@Test
	@DisplayName("duplicate bindings violate the unique constraint")
	void duplicateBindingRejected() {
		SsoOrg org = new SsoOrg("acme", "Acme");
		transact(() -> {
			orgs.save(org);
			teams.save(new SsoTeam(org.getId(), "iss", "group-1", "Eng"));
		});

		assertThatThrownBy(() -> transact(() ->
				teams.save(new SsoTeam(org.getId(), "iss", "group-1", "Eng again"))))
				.isInstanceOf(RuntimeException.class);
	}

	@Test
	@DisplayName("memberships resolve by user and by team with lifecycle moves")
	void membershipLifecycle() {
		UUID userId = UUID.randomUUID();
		SsoOrg org = new SsoOrg("acme", "Acme");
		SsoTeam team = new SsoTeam(org.getId(), "iss", "group-1", "Eng");
		transact(() -> {
			orgs.save(org);
			teams.save(team);
			memberships.save(new SsoMembership(userId, team.getId(), TeamRole.MEMBER,
					MembershipStatus.ACTIVE));
		});

		assertThat(memberships.findByUserId(userId)).hasSize(1);
		assertThat(memberships.findByTeamIdAndStatus(team.getId(), MembershipStatus.ACTIVE)).hasSize(1);
		assertThat(memberships.findByUserIdAndTeamId(userId, team.getId())).isPresent();

		transact(() -> {
			SsoMembership stored = memberships.findByUserIdAndTeamId(userId, team.getId()).orElseThrow();
			stored.move(TeamRole.LEAD, MembershipStatus.INACTIVE);
			memberships.save(stored);
		});

		List<SsoMembership> active = memberships.findByTeamIdAndStatus(team.getId(), MembershipStatus.ACTIVE);
		assertThat(active).isEmpty();
		SsoMembership moved = memberships.findByUserIdAndTeamId(userId, team.getId()).orElseThrow();
		assertThat(moved.getRole()).isEqualTo(TeamRole.LEAD);
		assertThat(moved.getStatus()).isEqualTo(MembershipStatus.INACTIVE);
	}
}
