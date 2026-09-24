package io.github.kxng0109.cacherelay.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * One account's membership in one team with a team-scoped role.
 */
@Entity
@Table(name = "sso_membership")
@IdClass(SsoMembershipId.class)
@Getter
public class SsoMembership {

	@Id
	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Id
	@Column(name = "team_id", nullable = false, updatable = false)
	private UUID teamId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private TeamRole role;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private MembershipStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	/**
	 * No-argument constructor required by the JPA specification.
	 */
	protected SsoMembership() {
	}

	/**
	 * @param userId owning account, never {@code null}
	 * @param teamId team, never {@code null}
	 * @param role   team-scoped role, never {@code null}
	 * @param status lifecycle state, never {@code null}
	 */
	public SsoMembership(UUID userId, UUID teamId, TeamRole role, MembershipStatus status) {
		this.userId = userId;
		this.teamId = teamId;
		this.role = role;
		this.status = status;
	}

	/**
	 * Moves the membership to a new role and state.
	 *
	 * @param role   team-scoped role, never {@code null}
	 * @param status lifecycle state, never {@code null}
	 */
	public void move(TeamRole role, MembershipStatus status) {
		this.role = role;
		this.status = status;
		this.updatedAt = Instant.now();
	}
}
