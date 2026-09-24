package io.github.kxng0109.cacherelay.auth;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Entity contracts for the SSO team domain: accessors, renames, moves, and
 * composite-key equality.
 */
@DisplayName("SSO team entities")
class SsoEntitiesTest {

	@Test
	@DisplayName("orgs expose identity and names")
	void orgAccessors() {
		SsoOrg org = new SsoOrg("acme", "Acme");

		assertThat(org.getId()).isNotNull();
		assertThat(org.getSlug()).isEqualTo("acme");
		assertThat(org.getDisplayName()).isEqualTo("Acme");
		assertThat(org.getCreatedAt()).isNotNull();
		assertThat(org.getUpdatedAt()).isNotNull();
	}

	@Test
	@DisplayName("teams expose bindings and rename in place")
	void teamAccessorsAndRename() {
		UUID orgId = UUID.randomUUID();
		SsoTeam team = new SsoTeam(orgId, "iss", "group-1", "Eng");

		assertThat(team.getId()).isNotNull();
		assertThat(team.getOrgId()).isEqualTo(orgId);
		assertThat(team.getIdpIssuer()).isEqualTo("iss");
		assertThat(team.getIdpGroupId()).isEqualTo("group-1");
		assertThat(team.getName()).isEqualTo("Eng");
		assertThat(team.getCreatedAt()).isNotNull();
		assertThat(team.getUpdatedAt()).isNotNull();

		team.rename("Engineering");

		assertThat(team.getId()).isNotNull();
		assertThat(team.getName()).isEqualTo("Engineering");
		assertThat(team.getUpdatedAt()).isNotNull();
	}

	@Test
	@DisplayName("memberships expose keys and move state")
	void membershipAccessorsAndMove() {
		UUID userId = UUID.randomUUID();
		UUID teamId = UUID.randomUUID();
		SsoMembership membership = new SsoMembership(userId, teamId, TeamRole.MEMBER,
				MembershipStatus.ACTIVE);

		assertThat(membership.getUserId()).isEqualTo(userId);
		assertThat(membership.getTeamId()).isEqualTo(teamId);
		assertThat(membership.getRole()).isEqualTo(TeamRole.MEMBER);
		assertThat(membership.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
		assertThat(membership.getCreatedAt()).isNotNull();
		assertThat(membership.getUpdatedAt()).isNotNull();

		membership.move(TeamRole.LEAD, MembershipStatus.INACTIVE);

		assertThat(membership.getRole()).isEqualTo(TeamRole.LEAD);
		assertThat(membership.getStatus()).isEqualTo(MembershipStatus.INACTIVE);
		assertThat(membership.getUpdatedAt()).isNotNull();
	}

	@Test
	@DisplayName("watermarks advance time and keep status on null verdicts")
	void watermarkMarks() {
		UUID userId = UUID.randomUUID();
		Instant first = Instant.parse("2026-09-23T12:00:00Z");
		java.time.Instant second = first.plusSeconds(900L);
		SsoRevalidation watermark = new SsoRevalidation(userId, first, null);

		assertThat(watermark.getUserId()).isEqualTo(userId);
		assertThat(watermark.getLastVerifiedAt()).isEqualTo(first);
		assertThat(watermark.getLastStatus()).isNull();
		assertThat(watermark.getUpdatedAt()).isNotNull();

		watermark.mark(second, null);

		assertThat(watermark.getLastVerifiedAt()).isEqualTo(second);
		assertThat(watermark.getLastStatus()).isNull();

		watermark.mark(second.plusSeconds(900L), RevalidationStatus.INACTIVE);

		assertThat(watermark.getLastStatus()).isEqualTo(RevalidationStatus.INACTIVE);
	}

	@Test
	@DisplayName("membership ids compare by value")
	void membershipIdEquality() {
		UUID userId = UUID.randomUUID();
		UUID teamId = UUID.randomUUID();

		SsoMembershipId first = new SsoMembershipId(userId, teamId);
		SsoMembershipId same = new SsoMembershipId(userId, teamId);
		SsoMembershipId otherTeam = new SsoMembershipId(userId, UUID.randomUUID());
		SsoMembershipId otherUser = new SsoMembershipId(UUID.randomUUID(), teamId);

		assertThat(first).isEqualTo(same);
		assertThat(first.hashCode()).isEqualTo(same.hashCode());
		assertThat(first).isNotEqualTo(otherTeam);
		assertThat(first).isNotEqualTo(otherUser);
		assertThat(first).isNotEqualTo(null);
		assertThat(first).isNotEqualTo("not-an-id");
		assertThat(first).isEqualTo(first);
	}
}
