package io.github.kxng0109.cacherelay.admin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.kxng0109.cacherelay.admin.dto.TeamResponse;
import io.github.kxng0109.cacherelay.auth.MembershipStatus;
import io.github.kxng0109.cacherelay.auth.SsoMembership;
import io.github.kxng0109.cacherelay.auth.SsoMembershipRepository;
import io.github.kxng0109.cacherelay.auth.SsoOrg;
import io.github.kxng0109.cacherelay.auth.SsoOrgRepository;
import io.github.kxng0109.cacherelay.auth.SsoTeam;
import io.github.kxng0109.cacherelay.auth.SsoTeamRepository;
import io.github.kxng0109.cacherelay.auth.TeamRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Admin team inventory: org-scoped lists with live member counts, fail-closed
 * on missing orgs and blank slugs.
 */
@DisplayName("AdminTeamController")
class AdminTeamControllerTest {

	private final SsoOrgRepository orgs = mock(SsoOrgRepository.class);
	private final SsoTeamRepository teams = mock(SsoTeamRepository.class);
	private final SsoMembershipRepository memberships = mock(SsoMembershipRepository.class);
	private final AdminTeamController controller = new AdminTeamController(orgs, teams, memberships);

	@Test
	@DisplayName("lists org teams with active-member counts")
	void listsOrgTeams() {
		SsoOrg org = new SsoOrg("acme", "Acme");
		SsoTeam eng = new SsoTeam(org.getId(), "iss", "group-1", "Eng");
		SsoTeam ops = new SsoTeam(org.getId(), "iss", "group-2", "Ops");
		when(orgs.findBySlug("acme")).thenReturn(Optional.of(org));
		when(teams.findByOrgId(org.getId())).thenReturn(List.of(eng, ops));
		when(memberships.findByTeamIdAndStatus(eng.getId(), MembershipStatus.ACTIVE))
				.thenReturn(List.of(new SsoMembership(UUID.randomUUID(), eng.getId(),
						TeamRole.MEMBER, MembershipStatus.ACTIVE)));
		when(memberships.findByTeamIdAndStatus(ops.getId(), MembershipStatus.ACTIVE))
				.thenReturn(List.of());

		ResponseEntity<List<TeamResponse>> response = controller.listTeams("acme");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody()).hasSize(2);
		assertThat(response.getBody().getFirst().activeMembers()).isEqualTo(1L);
		assertThat(response.getBody().getFirst().orgSlug()).isEqualTo("acme");
		assertThat(response.getBody().get(1).activeMembers()).isZero();
	}

	@Test
	@DisplayName("unknown orgs answer 404")
	void unknownOrgAnswers404() {
		when(orgs.findBySlug("nope")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> controller.listTeams("nope"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("blank slugs answer 400 without touching stores")
	void blankSlugAnswers400() {
		assertThatThrownBy(() -> controller.listTeams("  "))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThatThrownBy(() -> controller.listTeams(null))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		verifyNoInteractions(orgs, teams, memberships);
	}
}
