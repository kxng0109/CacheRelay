package io.github.kxng0109.cacherelay.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link SsoTeam}.
 */
public interface SsoTeamRepository extends JpaRepository<SsoTeam, UUID> {

	/**
	 * Finds the team bound to one IdP group inside an org.
	 *
	 * @param orgId      owning org, never {@code null}
	 * @param idpIssuer  IdP issuer, never {@code null}
	 * @param idpGroupId stable IdP group id, never {@code null}
	 * @return the team when present
	 */
	Optional<SsoTeam> findByOrgIdAndIdpIssuerAndIdpGroupId(UUID orgId, String idpIssuer,
			String idpGroupId);

	/**
	 * Lists every team in an org.
	 *
	 * @param orgId owning org, never {@code null}
	 * @return org teams in no guaranteed order
	 */
	List<SsoTeam> findByOrgId(UUID orgId);
}
