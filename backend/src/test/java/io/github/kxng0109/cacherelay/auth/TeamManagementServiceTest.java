package io.github.kxng0109.cacherelay.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Local team management: org lifecycle, unmapped-team lifecycle, membership
 * assignment with reactivation, and fail-closed guards on every path the login
 * sync must never silently undo.
 */
@DisplayName("TeamManagementService")
class TeamManagementServiceTest {

	private final SsoOrgRepository orgs = mock(SsoOrgRepository.class);
	private final SsoTeamRepository teams = mock(SsoTeamRepository.class);
	private final SsoMembershipRepository memberships = mock(SsoMembershipRepository.class);
	private final UserAccountRepository users = mock(UserAccountRepository.class);
	private final InviteTokenRepository invites = mock(InviteTokenRepository.class);
	private final TeamManagementService service =
			new TeamManagementService(orgs, teams, memberships, users, invites);

	@Test
	@DisplayName("creates orgs with normalized slugs")
	void createsOrgNormalized() {
		when(orgs.findBySlug("acme")).thenReturn(Optional.empty());
		when(orgs.save(any(SsoOrg.class))).thenAnswer(inv -> inv.getArgument(0));

		SsoOrg org = service.createOrg("  Acme ", "Acme Corp");

		assertThat(org.getSlug()).isEqualTo("acme");
		assertThat(org.getDisplayName()).isEqualTo("Acme Corp");
		verify(orgs).save(any(SsoOrg.class));
	}

	@Test
	@DisplayName("rejects null slugs and malformed names")
	void rejectsNullSlugAndBadNames() {
		when(orgs.findBySlug("ok")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.createOrg(null, "Acme"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThatThrownBy(() -> service.createOrg("ok", null))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThatThrownBy(() -> service.createOrg("ok", "x".repeat(129)))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThatThrownBy(() -> service.renameOrg(UUID.randomUUID(), "  "))
				.isInstanceOf(ResponseStatusException.class);
		verify(orgs, never()).save(any(SsoOrg.class));
	}

	@Test
	@DisplayName("renames orgs and guards unknown orgs")
	void renamesOrgGuardsUnknown() {
		SsoOrg org = new SsoOrg("acme", "Acme");
		when(orgs.findById(org.getId())).thenReturn(Optional.of(org));
		when(orgs.save(any(SsoOrg.class))).thenAnswer(inv -> inv.getArgument(0));

		assertThat(service.renameOrg(org.getId(), "Acme Inc").getDisplayName())
				.isEqualTo("Acme Inc");
		assertThatThrownBy(() -> service.renameOrg(UUID.randomUUID(), "Acme Inc"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("rejects malformed slugs and duplicate slugs")
	void rejectsBadAndDuplicateSlugs() {
		when(orgs.findBySlug("acme")).thenReturn(Optional.of(new SsoOrg("acme", "Acme")));

		assertThatThrownBy(() -> service.createOrg("BAD SLUG!", "x"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThatThrownBy(() -> service.createOrg("acme", "Acme"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.CONFLICT);
		verify(orgs, never()).save(any(SsoOrg.class));
	}

	@Test
	@DisplayName("concurrent duplicate slugs collapse to 409 on the constraint")
	void duplicateSlugRaceAnswers409() {
		when(orgs.findBySlug("acme")).thenReturn(Optional.empty());
		when(orgs.save(any(SsoOrg.class)))
				.thenThrow(new DataIntegrityViolationException("uq_sso_org_slug"));

		assertThatThrownBy(() -> service.createOrg("acme", "Acme"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	@DisplayName("renames org display names without moving slugs")
	void renamesOrg() {
		SsoOrg org = new SsoOrg("acme", "Acme");
		when(orgs.findById(org.getId())).thenReturn(Optional.of(org));
		when(orgs.save(any(SsoOrg.class))).thenAnswer(inv -> inv.getArgument(0));

		SsoOrg renamed = service.renameOrg(org.getId(), "Acme Inc");

		assertThat(renamed.getId()).isEqualTo(org.getId());
		assertThat(renamed.getSlug()).isEqualTo("acme");
		assertThat(renamed.getDisplayName()).isEqualTo("Acme Inc");
	}

	@Test
	@DisplayName("deletes empty orgs and refuses orgs holding teams")
	void deletesOrgGuardsTeams() {
		SsoOrg org = new SsoOrg("acme", "Acme");
		SsoOrg full = new SsoOrg("full", "Full");
		when(orgs.findById(org.getId())).thenReturn(Optional.of(org));
		when(orgs.findById(full.getId())).thenReturn(Optional.of(full));
		when(teams.findByOrgId(org.getId())).thenReturn(List.of());
		when(teams.findByOrgId(full.getId()))
				.thenReturn(List.of(new SsoTeam(full.getId(), "", "local:x", "X")));

		service.deleteOrg(org.getId());
		verify(orgs).delete(org);

		assertThatThrownBy(() -> service.deleteOrg(full.getId()))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.CONFLICT);
		assertThatThrownBy(() -> service.deleteOrg(UUID.randomUUID()))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("creates locally namespaced teams")
	void createsLocalTeam() {
		SsoOrg org = new SsoOrg("acme", "Acme");
		when(orgs.findById(org.getId())).thenReturn(Optional.of(org));
		when(teams.save(any(SsoTeam.class))).thenAnswer(inv -> inv.getArgument(0));

		SsoTeam team = service.createTeam(org.getId(), "Eng");

		assertThat(team.getOrgId()).isEqualTo(org.getId());
		assertThat(team.getIdpIssuer()).isEmpty();
		assertThat(team.getIdpGroupId()).isEqualTo("local:eng");
		assertThat(team.getName()).isEqualTo("Eng");
	}

	@Test
	@DisplayName("rejects unknown orgs and malformed team names")
	void rejectsBadTeamInputs() {
		SsoOrg org = new SsoOrg("acme", "Acme");
		when(orgs.findById(org.getId())).thenReturn(Optional.of(org));

		assertThatThrownBy(() -> service.createTeam(UUID.randomUUID(), "Eng"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThatThrownBy(() -> service.createTeam(org.getId(), null))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThatThrownBy(() -> service.createTeam(org.getId(), "   "))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThatThrownBy(() -> service.createTeam(org.getId(), "!!!"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThatThrownBy(() -> service.createTeam(org.getId(), "x".repeat(129)))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("unknown team ids fail closed on every write")
	void unknownTeamFailsClosed() {
		UUID ghost = UUID.randomUUID();

		assertThatThrownBy(() -> service.renameTeam(ghost, "X"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThatThrownBy(() -> service.deleteTeam(ghost))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThatThrownBy(() -> service.assignMember(ghost, UUID.randomUUID(), TeamRole.MEMBER))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThatThrownBy(() -> service.revokeMember(ghost, UUID.randomUUID()))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("duplicate team names collapse to 409 on the constraint")
	void duplicateTeamRaceAnswers409() {
		SsoOrg org = new SsoOrg("acme", "Acme");
		when(orgs.findById(org.getId())).thenReturn(Optional.of(org));
		when(teams.save(any(SsoTeam.class)))
				.thenThrow(new DataIntegrityViolationException("uq_sso_team_binding"));

		assertThatThrownBy(() -> service.createTeam(org.getId(), "Eng"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	@DisplayName("renames local teams and refuses mapped teams")
	void renamesLocalRefusesMapped() {
		SsoTeam local = new SsoTeam(UUID.randomUUID(), "", "local:eng", "Eng");
		SsoTeam mapped = new SsoTeam(UUID.randomUUID(), "https://idp.example.com", "g1", "G1");
		when(teams.findById(local.getId())).thenReturn(Optional.of(local));
		when(teams.findById(mapped.getId())).thenReturn(Optional.of(mapped));
		when(teams.save(any(SsoTeam.class))).thenAnswer(inv -> inv.getArgument(0));

		assertThat(service.renameTeam(local.getId(), "Engineering").getName())
				.isEqualTo("Engineering");
		assertThatThrownBy(() -> service.renameTeam(mapped.getId(), "X"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	@DisplayName("deletes unreferenced local teams with layered guards")
	void deletesLocalTeamGuards() {
		SsoTeam free = new SsoTeam(UUID.randomUUID(), "", "local:free", "Free");
		SsoTeam mapped = new SsoTeam(UUID.randomUUID(), "https://idp.example.com", "g1", "G1");
		SsoTeam held = new SsoTeam(UUID.randomUUID(), "", "local:held", "Held");
		SsoTeam invited = new SsoTeam(UUID.randomUUID(), "", "local:inv", "Inv");
		SsoTeam lobby = new SsoTeam(UUID.randomUUID(), "", SsoTeam.UNASSIGNED_GROUP_ID,
				"Unassigned");
		for (SsoTeam team : List.of(free, mapped, held, invited, lobby)) {
			when(teams.findById(team.getId())).thenReturn(Optional.of(team));
		}
		when(memberships.findByTeamIdAndStatus(eq(free.getId()), eq(MembershipStatus.ACTIVE)))
				.thenReturn(List.of());
		when(memberships.findByTeamIdAndStatus(eq(held.getId()), eq(MembershipStatus.ACTIVE)))
				.thenReturn(List.of(new SsoMembership(UUID.randomUUID(), held.getId(),
						TeamRole.MEMBER, MembershipStatus.ACTIVE)));
		when(memberships.findByTeamIdAndStatus(eq(invited.getId()), eq(MembershipStatus.ACTIVE)))
				.thenReturn(List.of());
		when(invites.findByTeamIdAndConsumedAtIsNull(free.getId())).thenReturn(List.of());
		when(invites.findByTeamIdAndConsumedAtIsNull(invited.getId()))
				.thenReturn(List.of(mock(InviteToken.class)));

		service.deleteTeam(free.getId());
		verify(teams).delete(free);

		for (SsoTeam guarded : List.of(mapped, held, invited, lobby)) {
			assertThatThrownBy(() -> service.deleteTeam(guarded.getId()))
					.isInstanceOf(ResponseStatusException.class)
					.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
					.isEqualTo(HttpStatus.CONFLICT);
		}
		verify(teams, never()).delete(mapped);
	}

	@Test
	@DisplayName("assigns, idempotently reassigns, and reactivates memberships")
	void assignsAndReactivates() {
		SsoTeam team = new SsoTeam(UUID.randomUUID(), "", "local:eng", "Eng");
		UserAccount fresh = new UserAccount("op", "hash", null, false);
		UserAccount same = new UserAccount("same", "hash", null, false);
		UserAccount moved = new UserAccount("moved", "hash", null, false);
		UserAccount back = new UserAccount("back", "hash", null, false);
		when(teams.findById(team.getId())).thenReturn(Optional.of(team));
		when(users.findById(fresh.getId())).thenReturn(Optional.of(fresh));
		when(users.findById(same.getId())).thenReturn(Optional.of(same));
		when(users.findById(moved.getId())).thenReturn(Optional.of(moved));
		when(users.findById(back.getId())).thenReturn(Optional.of(back));
		when(memberships.findByUserIdAndTeamId(fresh.getId(), team.getId()))
				.thenReturn(Optional.empty());
		when(memberships.findByUserIdAndTeamId(same.getId(), team.getId()))
				.thenReturn(Optional.of(new SsoMembership(same.getId(), team.getId(),
						TeamRole.MEMBER, MembershipStatus.ACTIVE)));
		when(memberships.findByUserIdAndTeamId(moved.getId(), team.getId()))
				.thenReturn(Optional.of(new SsoMembership(moved.getId(), team.getId(),
						TeamRole.MEMBER, MembershipStatus.ACTIVE)));
		when(memberships.findByUserIdAndTeamId(back.getId(), team.getId()))
				.thenReturn(Optional.of(new SsoMembership(back.getId(), team.getId(),
						TeamRole.LEAD, MembershipStatus.INACTIVE)));
		when(memberships.save(any(SsoMembership.class))).thenAnswer(inv -> inv.getArgument(0));

		assertThat(service.assignMember(team.getId(), fresh.getId(), TeamRole.MEMBER).getStatus())
				.isEqualTo(MembershipStatus.ACTIVE);
		assertThat(service.assignMember(team.getId(), same.getId(), TeamRole.MEMBER).getStatus())
				.isEqualTo(MembershipStatus.ACTIVE);
		assertThat(service.assignMember(team.getId(), moved.getId(), TeamRole.LEAD).getRole())
				.isEqualTo(TeamRole.LEAD);
		SsoMembership revived = service.assignMember(team.getId(), back.getId(), TeamRole.MEMBER);
		assertThat(revived.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
		assertThat(revived.getRole()).isEqualTo(TeamRole.MEMBER);
	}

	@Test
	@DisplayName("refuses assignments to mapped teams, unknown rows, and disabled accounts")
	void refusesBadAssignments() {
		SsoTeam mapped = new SsoTeam(UUID.randomUUID(), "https://idp.example.com", "g1", "G1");
		SsoTeam local = new SsoTeam(UUID.randomUUID(), "", "local:eng", "Eng");
		UserAccount off = new UserAccount("off", "hash", null, false);
		off.disable();
		when(teams.findById(mapped.getId())).thenReturn(Optional.of(mapped));
		when(teams.findById(local.getId())).thenReturn(Optional.of(local));
		when(users.findById(off.getId())).thenReturn(Optional.of(off));

		assertThatThrownBy(() -> service.assignMember(mapped.getId(), UUID.randomUUID(),
				TeamRole.MEMBER))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.CONFLICT);
		assertThatThrownBy(() -> service.assignMember(local.getId(), UUID.randomUUID(),
				TeamRole.MEMBER))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThatThrownBy(() -> service.assignMember(local.getId(), off.getId(),
				TeamRole.MEMBER))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.CONFLICT);
		verify(memberships, never()).save(any(SsoMembership.class));
	}

	@Test
	@DisplayName("revokes to inactive without deleting history")
	void revokesMembership() {
		SsoTeam team = new SsoTeam(UUID.randomUUID(), "", "local:eng", "Eng");
		UUID userId = UUID.randomUUID();
		UUID quietId = UUID.randomUUID();
		when(teams.findById(team.getId())).thenReturn(Optional.of(team));
		when(memberships.findByUserIdAndTeamId(userId, team.getId()))
				.thenReturn(Optional.of(new SsoMembership(userId, team.getId(), TeamRole.LEAD,
						MembershipStatus.ACTIVE)));
		when(memberships.findByUserIdAndTeamId(quietId, team.getId()))
				.thenReturn(Optional.of(new SsoMembership(quietId, team.getId(), TeamRole.MEMBER,
						MembershipStatus.INACTIVE)));
		when(memberships.save(any(SsoMembership.class))).thenAnswer(inv -> inv.getArgument(0));

		SsoMembership revoked = service.revokeMember(team.getId(), userId);

		assertThat(revoked.getStatus()).isEqualTo(MembershipStatus.INACTIVE);
		assertThat(revoked.getRole()).isEqualTo(TeamRole.LEAD);
		assertThat(service.revokeMember(team.getId(), quietId).getStatus())
				.isEqualTo(MembershipStatus.INACTIVE);
		assertThatThrownBy(() -> service.revokeMember(team.getId(), UUID.randomUUID()))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@DisplayName("resolves placeable teams for invite flows")
	void resolvesPlaceable() {
		SsoTeam local = new SsoTeam(UUID.randomUUID(), "", "local:eng", "Eng");
		SsoTeam mapped = new SsoTeam(UUID.randomUUID(), "https://idp.example.com", "g1", "G1");
		when(teams.findById(local.getId())).thenReturn(Optional.of(local));
		when(teams.findById(mapped.getId())).thenReturn(Optional.of(mapped));

		assertThat(service.requirePlaceableTeam(local.getId()).getId()).isEqualTo(local.getId());
		assertThatThrownBy(() -> service.requirePlaceableTeam(mapped.getId()))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.CONFLICT);
		assertThatThrownBy(() -> service.requirePlaceableTeam(UUID.randomUUID()))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}
}
