package io.github.kxng0109.cacherelay.me;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.kxng0109.cacherelay.auth.JwtService;
import io.github.kxng0109.cacherelay.auth.MembershipStatus;
import io.github.kxng0109.cacherelay.auth.SsoMembership;
import io.github.kxng0109.cacherelay.auth.SsoMembershipRepository;
import io.github.kxng0109.cacherelay.auth.SsoOrg;
import io.github.kxng0109.cacherelay.auth.SsoOrgRepository;
import io.github.kxng0109.cacherelay.auth.SsoTeam;
import io.github.kxng0109.cacherelay.auth.SsoTeamRepository;
import io.github.kxng0109.cacherelay.auth.UserAccountRepository;
import io.github.kxng0109.cacherelay.config.OpenApiConfig;
import io.github.kxng0109.cacherelay.me.dto.TeamMembershipResponse;
import io.github.kxng0109.cacherelay.security.filter.KeyAuthFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Team membership reads under {@code /v1/me/teams} for session-authenticated
 * accounts. Callers see only their own ACTIVE memberships with team and org
 * identity for scoping dashboards and requests.
 */
@RestController
@RequestMapping("/v1/me/teams")
@RequiredArgsConstructor
@Tag(name = "Self-service teams", description = "Session-authenticated team membership reads")
public class MeTeamController {

	private final SsoMembershipRepository memberships;

	private final SsoTeamRepository teams;

	private final SsoOrgRepository orgs;

	private final JwtService sessions;

	private final UserAccountRepository users;

	/**
	 * Lists the caller's active team memberships.
	 *
	 * @param authorization session bearer token
	 * @return HTTP 200 OK with owned memberships, possibly empty
	 */
	@Operation(
			summary = "List my teams",
			description = "Lists active team memberships of the session account.",
			security = @SecurityRequirement(name = OpenApiConfig.SCHEME_BEARER_AUTH)
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Owned memberships",
					content = @Content(mediaType = "application/json",
							array = @ArraySchema(schema = @Schema(implementation = TeamMembershipResponse.class)))),
			@ApiResponse(responseCode = "401", description = "Invalid session")
	})
	@GetMapping
	public ResponseEntity<List<TeamMembershipResponse>> myTeams(
			@Parameter(description = "Session bearer token", hidden = true)
			@RequestHeader("Authorization") String authorization) {
		UUID userId = requireSelf(authorization);
		List<SsoMembership> actives = new ArrayList<>();
		for (SsoMembership membership : memberships.findByUserId(userId)) {
			if (membership.getStatus() == MembershipStatus.ACTIVE) {
				actives.add(membership);
			}
		}
		if (actives.isEmpty()) {
			return ResponseEntity.ok(List.of());
		}
		List<UUID> teamIds = new ArrayList<>();
		for (SsoMembership membership : actives) {
			teamIds.add(membership.getTeamId());
		}
		Map<UUID, SsoTeam> teamById = new HashMap<>();
		for (SsoTeam team : teams.findAllById(teamIds)) {
			teamById.put(team.getId(), team);
		}
		List<UUID> orgIds = new ArrayList<>();
		for (SsoTeam team : teamById.values()) {
			if (!orgIds.contains(team.getOrgId())) {
				orgIds.add(team.getOrgId());
			}
		}
		Map<UUID, String> slugByOrg = new HashMap<>();
		for (SsoOrg org : orgs.findAllById(orgIds)) {
			slugByOrg.put(org.getId(), org.getSlug());
		}
		List<TeamMembershipResponse> response = new ArrayList<>();
		for (SsoMembership membership : actives) {
			SsoTeam team = teamById.get(membership.getTeamId());
			String slug = team == null ? null : slugByOrg.get(team.getOrgId());
			if (team == null || slug == null) {
				continue;
			}
			response.add(new TeamMembershipResponse(team.getId(), team.getName(), slug,
					membership.getRole(), membership.getStatus()));
		}
		return ResponseEntity.ok(response);
	}

	private UUID requireSelf(String authorization) {
		String token = authorization == null ? "" : authorization.trim();
		if (token.startsWith(KeyAuthFilter.AUTH_SCHEME)) {
			token = token.substring(KeyAuthFilter.AUTH_SCHEME.length()).trim();
		}
		final Jwt decoded;
		try {
			decoded = sessions.validate(token);
		} catch (RuntimeException invalid) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid session");
		}
		if (decoded == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid session");
		}
		final UUID userId;
		try {
			userId = UUID.fromString(decoded.getSubject());
		} catch (RuntimeException malformed) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid session");
		}
		boolean active = users.findById(userId).map(account -> !account.isDisabled()).orElse(false);
		if (!active) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid session");
		}
		return userId;
	}
}
