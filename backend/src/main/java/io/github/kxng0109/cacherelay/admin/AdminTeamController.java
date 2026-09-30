package io.github.kxng0109.cacherelay.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.kxng0109.cacherelay.admin.dto.AssignMemberRequest;
import io.github.kxng0109.cacherelay.admin.dto.MemberResponse;
import io.github.kxng0109.cacherelay.admin.dto.RenameRequest;
import io.github.kxng0109.cacherelay.admin.dto.TeamResponse;
import io.github.kxng0109.cacherelay.auth.MembershipStatus;
import io.github.kxng0109.cacherelay.auth.SsoMembership;
import io.github.kxng0109.cacherelay.auth.SsoMembershipRepository;
import io.github.kxng0109.cacherelay.auth.SsoOrg;
import io.github.kxng0109.cacherelay.auth.SsoOrgRepository;
import io.github.kxng0109.cacherelay.auth.SsoTeam;
import io.github.kxng0109.cacherelay.auth.SsoTeamRepository;
import io.github.kxng0109.cacherelay.auth.TeamManagementService;
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
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

	private final TeamManagementService teamService;

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

	/**
	 * Renames a locally managed team, keeping its identity stable.
	 *
	 * @param id   team id
	 * @param body desired display name
	 * @return the updated team with its live active-member count
	 */
	@Operation(
			summary = "Rename team",
			description = "Renames a locally managed team; IdP-managed teams are read-only.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Team renamed"),
			@ApiResponse(responseCode = "400", description = "Malformed team id or name"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect"),
			@ApiResponse(responseCode = "404", description = "Team not found"),
			@ApiResponse(responseCode = "409", description = "Team is IdP-managed and read-only")
	})
	@PatchMapping("/{id}")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<TeamResponse> renameTeam(
			@Parameter(description = "Team UUID")
			@PathVariable("id") String id,
			@Valid @RequestBody RenameRequest body) {
		SsoTeam team = teamService.renameTeam(requireTeamId(id), body.displayName());
		return ResponseEntity.ok(viewOf(team));
	}

	/**
	 * Deletes a locally managed team once nothing references it.
	 *
	 * @param id team id
	 * @return HTTP 204 on success
	 */
	@Operation(
			summary = "Delete team",
			description = "Deletes a locally managed team; refused while active memberships or pending invites reference it.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "204", description = "Team deleted", content = @Content),
			@ApiResponse(responseCode = "400", description = "Malformed team id"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect"),
			@ApiResponse(responseCode = "404", description = "Team not found"),
			@ApiResponse(responseCode = "409", description = "Team is protected or still referenced")
	})
	@DeleteMapping("/{id}")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<Void> deleteTeam(
			@Parameter(description = "Team UUID")
			@PathVariable("id") String id) {
		teamService.deleteTeam(requireTeamId(id));
		return ResponseEntity.noContent().build();
	}

	/**
	 * Assigns an account to a locally managed team, reactivating dormant rows.
	 *
	 * @param id      team id
	 * @param userId  account id
	 * @param body    team-scoped role
	 * @return the active membership
	 */
	@Operation(
			summary = "Assign team member",
			description = "Assigns an account to a locally managed team; idempotent on repeat calls.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Membership active"),
			@ApiResponse(responseCode = "400", description = "Malformed ids or role"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect"),
			@ApiResponse(responseCode = "404", description = "Team or account not found"),
			@ApiResponse(responseCode = "409", description = "IdP-managed team or disabled account")
	})
	@PutMapping("/{id}/members/{userId}")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<MemberResponse> assignMember(
			@Parameter(description = "Team UUID")
			@PathVariable("id") String id,
			@Parameter(description = "Account UUID")
			@PathVariable("userId") String userId,
			@Valid @RequestBody AssignMemberRequest body) {
		SsoMembership membership = teamService.assignMember(
				requireTeamId(id), requireUserId(userId), body.role());
		return ResponseEntity.ok(new MemberResponse(membership.getUserId(), membership.getTeamId(),
				membership.getRole(), membership.getStatus()));
	}

	/**
	 * Revokes an account's membership without deleting history.
	 *
	 * @param id     team id
	 * @param userId account id
	 * @return the inactive membership
	 */
	@Operation(
			summary = "Revoke team member",
			description = "Flips a membership to inactive; history is preserved for SSO reactivation.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Membership revoked"),
			@ApiResponse(responseCode = "400", description = "Malformed ids"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect"),
			@ApiResponse(responseCode = "404", description = "Team or membership not found"),
			@ApiResponse(responseCode = "409", description = "Team is IdP-managed and read-only")
	})
	@DeleteMapping("/{id}/members/{userId}")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<MemberResponse> revokeMember(
			@Parameter(description = "Team UUID")
			@PathVariable("id") String id,
			@Parameter(description = "Account UUID")
			@PathVariable("userId") String userId) {
		SsoMembership membership = teamService.revokeMember(
				requireTeamId(id), requireUserId(userId));
		return ResponseEntity.ok(new MemberResponse(membership.getUserId(), membership.getTeamId(),
				membership.getRole(), membership.getStatus()));
	}

	private TeamResponse viewOf(SsoTeam team) {
		String slug = orgs.findById(team.getOrgId())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"org not found"))
				.getSlug();
		long active = memberships.findByTeamIdAndStatus(team.getId(), MembershipStatus.ACTIVE)
				.size();
		return new TeamResponse(team.getId(), slug, team.getName(), team.getIdpGroupId(),
				active);
	}

	private static UUID requireTeamId(String id) {
		try {
			return UUID.fromString(id);
		} catch (IllegalArgumentException malformed) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "malformed team id");
		}
	}

	private static UUID requireUserId(String id) {
		try {
			return UUID.fromString(id);
		} catch (IllegalArgumentException malformed) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "malformed account id");
		}
	}
}
