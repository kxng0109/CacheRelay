package io.github.kxng0109.cacherelay.admin;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.kxng0109.cacherelay.admin.dto.CreateOrgRequest;
import io.github.kxng0109.cacherelay.admin.dto.CreateTeamRequest;
import io.github.kxng0109.cacherelay.admin.dto.RenameRequest;
import io.github.kxng0109.cacherelay.auth.SsoOrg;
import io.github.kxng0109.cacherelay.auth.SsoOrgRepository;
import io.github.kxng0109.cacherelay.auth.SsoTeam;
import io.github.kxng0109.cacherelay.auth.TeamManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Admin org reads and writes: creation with normalized slugs, listing, renames
 * that never move slugs, guarded deletes, and nested team creation.
 */
@DisplayName("AdminOrgController")
class AdminOrgControllerTest {

	private final SsoOrgRepository orgs = mock(SsoOrgRepository.class);
	private final TeamManagementService teams = mock(TeamManagementService.class);
	private final AdminOrgController controller = new AdminOrgController(orgs, teams);

	@Test
	@DisplayName("creates orgs with 201 and the persisted shape")
	void createsOrg() {
		SsoOrg org = new SsoOrg("acme", "Acme Corp");
		when(teams.createOrg(eq("acme"), eq("Acme Corp"))).thenReturn(org);

		var response = controller.createOrg(new CreateOrgRequest("acme", "Acme Corp"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody().slug()).isEqualTo("acme");
		assertThat(response.getBody().displayName()).isEqualTo("Acme Corp");
		verify(teams).createOrg(eq("acme"), eq("Acme Corp"));
	}

	@Test
	@DisplayName("lists every org")
	void listsOrgs() {
		SsoOrg org = new SsoOrg("acme", "Acme Corp");
		when(orgs.findAll()).thenReturn(List.of(org));

		var response = controller.listOrgs();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).hasSize(1);
		assertThat(response.getBody().get(0).slug()).isEqualTo("acme");
	}

	@Test
	@DisplayName("renames orgs and answers 400 for malformed ids")
	void renamesOrg() {
		SsoOrg org = new SsoOrg("acme", "Acme Inc");
		when(teams.renameOrg(eq(org.getId()), eq("Acme Inc"))).thenReturn(org);

		var response = controller.renameOrg(org.getId().toString(),
				new RenameRequest("Acme Inc"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().displayName()).isEqualTo("Acme Inc");
		assertThatThrownBy(() -> controller.renameOrg("not-a-uuid", new RenameRequest("X")))
				.hasMessageContaining("malformed org id");
	}

	@Test
	@DisplayName("deletes orgs with 204")
	void deletesOrg() {
		UUID id = UUID.randomUUID();

		var response = controller.deleteOrg(id.toString());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		verify(teams).deleteOrg(eq(id));
	}

	@Test
	@DisplayName("creates nested teams with zero members and resolved slugs")
	void createsNestedTeam() {
		SsoOrg org = new SsoOrg("acme", "Acme Corp");
		SsoTeam team = new SsoTeam(org.getId(), "", "local:eng", "Eng");
		when(teams.createTeam(eq(org.getId()), eq("Eng"))).thenReturn(team);
		when(orgs.findById(eq(org.getId()))).thenReturn(Optional.of(org));

		var response = controller.createTeam(org.getId().toString(),
				new CreateTeamRequest("Eng"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody().name()).isEqualTo("Eng");
		assertThat(response.getBody().orgSlug()).isEqualTo("acme");
		assertThat(response.getBody().activeMembers()).isZero();
	}

	@Test
	@DisplayName("nested team creation answers 404 when the org vanished")
	void nestedTeamMissingOrg() {
		SsoOrg org = new SsoOrg("acme", "Acme Corp");
		SsoTeam team = new SsoTeam(org.getId(), "", "local:eng", "Eng");
		when(teams.createTeam(eq(org.getId()), eq("Eng"))).thenReturn(team);
		when(orgs.findById(eq(org.getId()))).thenReturn(Optional.empty());

		assertThatThrownBy(
				() -> controller.createTeam(org.getId().toString(), new CreateTeamRequest("Eng")))
				.hasMessageContaining("org not found");
	}

	@Test
	@DisplayName("malformed ids fail with 400 before touching the service")
	void malformedIdsRejected() {
		assertThatThrownBy(() -> controller.deleteOrg("not-a-uuid"))
				.hasMessageContaining("malformed org id");
		assertThatThrownBy(
				() -> controller.createTeam("not-a-uuid", new CreateTeamRequest("Eng")))
				.hasMessageContaining("malformed org id");
		verify(teams, never()).deleteOrg(any(UUID.class));
	}
}
