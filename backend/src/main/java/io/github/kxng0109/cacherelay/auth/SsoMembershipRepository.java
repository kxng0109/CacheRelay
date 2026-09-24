package io.github.kxng0109.cacherelay.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link SsoMembership}.
 */
public interface SsoMembershipRepository extends JpaRepository<SsoMembership, SsoMembershipId> {

	/**
	 * Lists every membership of one account across teams.
	 *
	 * @param userId owning account, never {@code null}
	 * @return memberships in no guaranteed order
	 */
	List<SsoMembership> findByUserId(UUID userId);

	/**
	 * Lists memberships of one team in a lifecycle state.
	 *
	 * @param teamId team, never {@code null}
	 * @param status lifecycle state, never {@code null}
	 * @return matching memberships in no guaranteed order
	 */
	List<SsoMembership> findByTeamIdAndStatus(UUID teamId, MembershipStatus status);

	/**
	 * Finds one account's membership in one team.
	 *
	 * @param userId owning account, never {@code null}
	 * @param teamId team, never {@code null}
	 * @return the membership when present
	 */
	Optional<SsoMembership> findByUserIdAndTeamId(UUID userId, UUID teamId);
}
