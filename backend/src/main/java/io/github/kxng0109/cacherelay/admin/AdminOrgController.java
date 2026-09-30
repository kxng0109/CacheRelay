package io.github.kxng0109.cacherelay.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.kxng0109.cacherelay.admin.dto.CreateOrgRequest;
import io.github.kxng0109.cacherelay.admin.dto.CreateTeamRequest;
import io.github.kxng0109.cacherelay.admin.dto.OrgResponse;
import io.github.kxng0109.cacherelay.admin.dto.RenameRequest;
import io.github.kxng0109.cacherelay.admin.dto.TeamResponse;
import io.github.kxng0109.cacherelay.auth.SsoOrg;
import io.github.kxng0109.cacherelay.auth.SsoOrgRepository;
import io.github.kxng0109.cacherelay.auth.SsoTeam;
import io.github.kxng0109.cacherelay.auth.TeamManagementService;
import io.github.kxng0109.cacherelay.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST controller for locally managed orgs under {@code /v1/admin/orgs}.
 * Orgs are explicit admin-created tenant boundaries; IdP-registered org slugs
 * resolve to the same rows, so local and SSO management never fork the domain.
 */
@RestController
@RequestMapping("/v1/admin/orgs")
@RequiredArgsConstructor
@Tag(name = "Admin - Orgs", description = "Locally managed organizations")
public class AdminOrgController {

	private final SsoOrgRepository orgs;

	private final TeamManagementService teams;

	/**
	 * Creates an org.
	 *
	 * @param body desired slug and display name
	 * @return the persisted org
	 */
	@Operation(
			summary = "Create org",
			description = "Creates a locally managed org; slugs normalize to lowercase alphanumerics and dashes.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "201", description = "Org created"),
			@ApiResponse(responseCode = "400", description = "Malformed slug or name"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect"),
			@ApiResponse(responseCode = "409", description = "Org slug already exists")
	})
	@PostMapping
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<OrgResponse> createOrg(@Valid @RequestBody CreateOrgRequest body) {
		SsoOrg org = teams.createOrg(body.slug(), body.displayName());
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(new OrgResponse(org.getId(), org.getSlug(), org.getDisplayName()));
	}

	/**
	 * Lists every org for operator pickers and administration.
	 *
	 * @return orgs in no guaranteed order
	 */
	@Operation(
			summary = "List orgs",
			description = "Returns every org.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Org list"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect")
	})
	@GetMapping
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<List<OrgResponse>> listOrgs() {
		List<OrgResponse> response = new ArrayList<>();
		for (SsoOrg org : orgs.findAll()) {
			response.add(new OrgResponse(org.getId(), org.getSlug(), org.getDisplayName()));
		}
		return ResponseEntity.ok(response);
	}

	/**
	 * Renames an org's display name. Slugs are immutable once issued.
	 *
	 * @param id   org id
	 * @param body desired display name
	 * @return the updated org
	 */
	@Operation(
			summary = "Rename org",
			description = "Renames an org's display name; the slug never moves.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Org renamed"),
			@ApiResponse(responseCode = "400", description = "Malformed org id or name"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect"),
			@ApiResponse(responseCode = "404", description = "Org not found")
	})
	@PatchMapping("/{id}")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<OrgResponse> renameOrg(
			@Parameter(description = "Org UUID")
			@PathVariable("id") String id,
			@Valid @RequestBody RenameRequest body) {
		SsoOrg org = teams.renameOrg(requireId(id), body.displayName());
		return ResponseEntity.ok(new OrgResponse(org.getId(), org.getSlug(), org.getDisplayName()));
	}

	/**
	 * Deletes an org once it holds no teams.
	 *
	 * @param id org id
	 * @return HTTP 204 on success
	 */
	@Operation(
			summary = "Delete org",
			description = "Deletes an org; refused while teams remain.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "204", description = "Org deleted", content = @Content),
			@ApiResponse(responseCode = "400", description = "Malformed org id"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect"),
			@ApiResponse(responseCode = "404", description = "Org not found"),
			@ApiResponse(responseCode = "409", description = "Org still holds teams")
	})
	@DeleteMapping("/{id}")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<Void> deleteOrg(
			@Parameter(description = "Org UUID")
			@PathVariable("id") String id) {
		teams.deleteOrg(requireId(id));
		return ResponseEntity.noContent().build();
	}

	/**
	 * Creates a locally managed team inside an org.
	 *
	 * @param id   owning org id
	 * @param body desired display name
	 * @return the persisted team with zero active members
	 */
	@Operation(
			summary = "Create team",
			description = "Creates a locally managed team; fresh teams hold no memberships.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "201", description = "Team created"),
			@ApiResponse(responseCode = "400", description = "Malformed org id or name"),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect"),
			@ApiResponse(responseCode = "404", description = "Org not found"),
			@ApiResponse(responseCode = "409", description = "Team already exists")
	})
	@PostMapping("/{id}/teams")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<TeamResponse> createTeam(
			@Parameter(description = "Owning org UUID")
			@PathVariable("id") String id,
			@Valid @RequestBody CreateTeamRequest body) {
		SsoTeam team = teams.createTeam(requireId(id), body.name());
		String slug = orgs.findById(team.getOrgId())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"org not found"))
				.getSlug();
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(new TeamResponse(team.getId(), slug, team.getName(),
						team.getIdpGroupId(), 0L));
	}

	private static UUID requireId(String id) {
		try {
			return UUID.fromString(id);
		} catch (IllegalArgumentException malformed) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "malformed org id");
		}
	}
}
