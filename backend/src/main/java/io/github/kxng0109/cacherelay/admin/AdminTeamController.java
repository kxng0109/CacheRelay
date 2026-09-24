package io.github.kxng0109.cacherelay.admin;

import java.util.ArrayList;
import java.util.List;

import io.github.kxng0109.cacherelay.admin.dto.TeamResponse;
import io.github.kxng0109.cacherelay.auth.MembershipStatus;
import io.github.kxng0109.cacherelay.auth.SsoMembershipRepository;
import io.github.kxng0109.cacherelay.auth.SsoOrg;
import io.github.kxng0109.cacherelay.auth.SsoOrgRepository;
import io.github.kxng0109.cacherelay.auth.SsoTeam;
import io.github.kxng0109.cacherelay.auth.SsoTeamRepository;
import io.github.kxng0109.cacherelay.config.OpenApiConfig;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Team reads under {@code /v1/admin/teams}: every team in one org with live
 * active-member counts for admin consoles and team pickers.
 */
@RestController
@RequestMapping("/v1/admin/teams")
@RequiredArgsConstructor
@Tag(name = "Admin - Teams", description = "SSO-provisioned team inventory")
public class AdminTeamController {

	private final SsoOrgRepository orgs;

	private final SsoTeamRepository teams;

	private final SsoMembershipRepository memberships;

	/**
	 * Lists every team in one org.
	 *
	 * @param orgSlug owning org slug, required
	 * @return HTTP 200 OK with teams and active-member counts
	 */
	@Operation(
			summary = "List org teams",
			description = "Lists every SSO-provisioned team in one org with live active-member counts.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Org teams",
					content = @Content(mediaType = "application/json",
							array = @ArraySchema(schema = @Schema(implementation = TeamResponse.class)))),
			@ApiResponse(responseCode = "400", description = "Missing org slug"),
			@ApiResponse(responseCode = "401", description = "Unauthorized"),
			@ApiResponse(responseCode = "404", description = "Unknown org")
	})
	@GetMapping
	public ResponseEntity<List<TeamResponse>> listTeams(
			@Parameter(description = "Owning org slug", example = "acme")
			@RequestParam(value = "org", required = false) String orgSlug) {
		if (orgSlug == null || orgSlug.isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parameter 'org' is required");
		}
		SsoOrg org = orgs.findBySlug(orgSlug.trim())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "org not found"));
		List<TeamResponse> response = new ArrayList<>();
		for (SsoTeam team : teams.findByOrgId(org.getId())) {
			long active = memberships.findByTeamIdAndStatus(team.getId(), MembershipStatus.ACTIVE).size();
			response.add(new TeamResponse(team.getId(), org.getSlug(), team.getName(),
					team.getIdpGroupId(), active));
		}
		return ResponseEntity.ok(response);
	}
}
