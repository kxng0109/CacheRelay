package io.github.kxng0109.cacherelay.me;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.kxng0109.cacherelay.auth.JwtService;
import io.github.kxng0109.cacherelay.auth.MembershipStatus;
import io.github.kxng0109.cacherelay.auth.SsoMembership;
import io.github.kxng0109.cacherelay.auth.SsoMembershipRepository;
import io.github.kxng0109.cacherelay.auth.SsoOrg;
import io.github.kxng0109.cacherelay.auth.SsoOrgRepository;
import io.github.kxng0109.cacherelay.auth.SsoTeam;
import io.github.kxng0109.cacherelay.auth.SsoTeamRepository;
import io.github.kxng0109.cacherelay.auth.TeamRole;
import io.github.kxng0109.cacherelay.auth.UserAccount;
import io.github.kxng0109.cacherelay.auth.UserAccountRepository;
import io.github.kxng0109.cacherelay.me.dto.TeamMembershipResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Team membership reads: session owners see their own ACTIVE memberships with
 * team and org identity, never other accounts' rows.
 */
@DisplayName("MeTeamController")
class MeTeamControllerTest {

	private final SsoMembershipRepository memberships = mock(SsoMembershipRepository.class);
	private final SsoTeamRepository teams = mock(SsoTeamRepository.class);
	private final SsoOrgRepository orgs = mock(SsoOrgRepository.class);
	private final JwtService sessions = mock(JwtService.class);
	private final UserAccountRepository users = mock(UserAccountRepository.class);
	private final MeTeamController controller =
			new MeTeamController(memberships, teams, orgs, sessions, users);
	private final UUID userId = UUID.randomUUID();

	private static final String SESSION = "session-jwt";

	private Jwt sessionFor(UUID user) {
		Instant now = Instant.now();
		return new Jwt("session", now, now.plusSeconds(600),
				Map.of("alg", "HS256"), Map.of("sub", user.toString()));
	}

	private void stubSession() {
		when(sessions.validate(SESSION)).thenReturn(sessionFor(userId));
		UserAccount account = new UserAccount("local", null, null, false);
		when(users.findById(userId)).thenReturn(Optional.of(account));
	}

	private String bearer() {
		return "Bearer " + SESSION;
	}

	@Test
	@DisplayName("lists the caller's active memberships with team identity")
	void listsOwnMemberships() {
		stubSession();
		UUID teamId = UUID.randomUUID();
		UUID orgId = UUID.randomUUID();
		SsoTeam team = new SsoTeam(orgId, "iss", "group-1", "Eng");
		setId(team, teamId);
		SsoOrg org = new SsoOrg("acme", "Acme");
		setId(org, orgId);
		when(memberships.findByUserId(userId)).thenReturn(List.of(
				new SsoMembership(userId, teamId, TeamRole.LEAD, MembershipStatus.ACTIVE),
				new SsoMembership(userId, UUID.randomUUID(), TeamRole.MEMBER,
						MembershipStatus.INACTIVE)));
		when(teams.findAllById(List.of(teamId))).thenReturn(List.of(team));
		when(orgs.findAllById(List.of(orgId))).thenReturn(List.of(org));

		ResponseEntity<List<TeamMembershipResponse>> response = controller.myTeams(bearer());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody()).hasSize(1);
		assertThat(response.getBody().getFirst().teamId()).isEqualTo(teamId);
		assertThat(response.getBody().getFirst().teamName()).isEqualTo("Eng");
		assertThat(response.getBody().getFirst().orgSlug()).isEqualTo("acme");
		assertThat(response.getBody().getFirst().role()).isEqualTo(TeamRole.LEAD);
	}

	@Test
	@DisplayName("no memberships reads as an empty list")
	void emptyMemberships() {
		stubSession();
		when(memberships.findByUserId(userId)).thenReturn(List.of());

		ResponseEntity<List<TeamMembershipResponse>> response = controller.myTeams(bearer());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody()).isEmpty();
		verify(teams, never()).findAllById(any());
	}
	@Test
	@DisplayName("memberships with missing teams or orgs are skipped")
	void danglingMembershipsSkipped() {
		stubSession();
		UUID teamA = UUID.randomUUID();
		UUID teamB = UUID.randomUUID();
		UUID orgId = UUID.randomUUID();
		SsoTeam team = new SsoTeam(orgId, "iss", "group-1", "Eng");
		setId(team, teamA);
		SsoOrg org = new SsoOrg("acme", "Acme");
		setId(org, orgId);
		when(memberships.findByUserId(userId)).thenReturn(List.of(
				new SsoMembership(userId, teamA, TeamRole.MEMBER, MembershipStatus.ACTIVE),
				new SsoMembership(userId, teamB, TeamRole.MEMBER, MembershipStatus.ACTIVE)));
		when(teams.findAllById(any())).thenReturn(List.of(team));
		when(orgs.findAllById(any())).thenReturn(List.of(org));

		ResponseEntity<List<TeamMembershipResponse>> response = controller.myTeams(bearer());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody()).hasSize(1);
		assertThat(response.getBody().getFirst().teamId()).isEqualTo(teamA);
	}

	@Test
	@DisplayName("memberships with missing orgs are skipped")
	void orgMissingSkipped() {
		stubSession();
		UUID teamA = UUID.randomUUID();
		UUID teamB = UUID.randomUUID();
		UUID orgId = UUID.randomUUID();
		SsoTeam first = new SsoTeam(orgId, "iss", "group-1", "Eng");
		setId(first, teamA);
		SsoTeam second = new SsoTeam(UUID.randomUUID(), "iss", "group-2", "Ops");
		setId(second, teamB);
		SsoOrg org = new SsoOrg("acme", "Acme");
		setId(org, orgId);
		when(memberships.findByUserId(userId)).thenReturn(List.of(
				new SsoMembership(userId, teamA, TeamRole.MEMBER, MembershipStatus.ACTIVE),
				new SsoMembership(userId, teamB, TeamRole.MEMBER, MembershipStatus.ACTIVE)));
		when(teams.findAllById(any())).thenReturn(List.of(first, second));
		when(orgs.findAllById(any())).thenReturn(List.of(org));

		ResponseEntity<List<TeamMembershipResponse>> response = controller.myTeams(bearer());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody()).hasSize(1);
		assertThat(response.getBody().getFirst().teamId()).isEqualTo(teamA);
	}
	@Test
	@DisplayName("teams sharing one org resolve its slug once")
	void sharedOrgSlugResolvedOnce() {
		stubSession();
		UUID teamA = UUID.randomUUID();
		UUID teamB = UUID.randomUUID();
		UUID orgId = UUID.randomUUID();
		SsoTeam first = new SsoTeam(orgId, "iss", "group-1", "Eng");
		setId(first, teamA);
		SsoTeam second = new SsoTeam(orgId, "iss", "group-2", "Ops");
		setId(second, teamB);
		SsoOrg org = new SsoOrg("acme", "Acme");
		setId(org, orgId);
		when(memberships.findByUserId(userId)).thenReturn(List.of(
				new SsoMembership(userId, teamA, TeamRole.MEMBER, MembershipStatus.ACTIVE),
				new SsoMembership(userId, teamB, TeamRole.LEAD, MembershipStatus.ACTIVE)));
		when(teams.findAllById(any())).thenReturn(List.of(first, second));
		when(orgs.findAllById(List.of(orgId))).thenReturn(List.of(org));

		ResponseEntity<List<TeamMembershipResponse>> response = controller.myTeams(bearer());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody()).hasSize(2);
		assertThat(response.getBody()).extracting(TeamMembershipResponse::orgSlug)
				.containsOnly("acme");
	}

	@Test
	@DisplayName("null authorization answers 401")
	@SuppressWarnings("DataFlowIssue")
	void nullAuthorizationAnswers401() {
		assertThatThrownBy(() -> controller.myTeams(null))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		verify(memberships, never()).findByUserId(any());
	}

	@Test
	@DisplayName("non-bearer authorization answers 401")
	void nonBearerAuthorizationAnswers401() {
		assertThatThrownBy(() -> controller.myTeams("Token " + SESSION))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		verify(memberships, never()).findByUserId(any());
	}

	@Test
	@DisplayName("disabled accounts answer 401")
	void disabledAccountAnswers401() {
		when(sessions.validate(SESSION)).thenReturn(sessionFor(userId));
		UserAccount disabled = mock(UserAccount.class);
		when(disabled.isDisabled()).thenReturn(true);
		when(users.findById(userId)).thenReturn(Optional.of(disabled));

		assertThatThrownBy(() -> controller.myTeams(bearer()))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		verify(memberships, never()).findByUserId(any());
	}

	@Test
	@DisplayName("null decoded sessions answer 401")
	void nullDecodedSessionAnswers401() {
		when(sessions.validate(SESSION)).thenReturn(null);

		assertThatThrownBy(() -> controller.myTeams(bearer()))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		verify(memberships, never()).findByUserId(any());
	}

	@Test
	@DisplayName("malformed subjects answer 401")
	void malformedSubjectAnswers401() {
		Instant now = Instant.now();
		Jwt malformed = new Jwt("session", now, now.plusSeconds(600),
				Map.of("alg", "HS256"), Map.of("sub", "not-a-uuid"));
		when(sessions.validate(SESSION)).thenReturn(malformed);

		assertThatThrownBy(() -> controller.myTeams(bearer()))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		verify(memberships, never()).findByUserId(any());
	}

	@Test
	@DisplayName("invalid sessions answer 401 without touching memberships")
	void invalidSessionAnswers401() {
		when(sessions.validate(SESSION)).thenThrow(new RuntimeException("bad signature"));

		assertThatThrownBy(() -> controller.myTeams(bearer()))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.UNAUTHORIZED);
		verify(memberships, never()).findByUserId(any());
	}

	private static void setId(Object entity, UUID id) {
		try {
			Field field = entity.getClass().getDeclaredField("id");
			field.setAccessible(true);
			field.set(entity, id);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("Test reflection failed", ex);
		}
	}
}
