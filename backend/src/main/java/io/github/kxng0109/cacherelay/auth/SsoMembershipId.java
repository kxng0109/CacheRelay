package io.github.kxng0109.cacherelay.auth;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite key for {@link SsoMembership}.
 */
public class SsoMembershipId implements Serializable {

	private UUID userId;

	private UUID teamId;

	/**
	 * No-argument constructor required by the JPA specification.
	 */
	public SsoMembershipId() {
	}

	/**
	 * @param userId owning account
	 * @param teamId team
	 */
	public SsoMembershipId(UUID userId, UUID teamId) {
		this.userId = userId;
		this.teamId = teamId;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof SsoMembershipId that)) {
			return false;
		}
		return Objects.equals(userId, that.userId) && Objects.equals(teamId, that.teamId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(userId, teamId);
	}
}
