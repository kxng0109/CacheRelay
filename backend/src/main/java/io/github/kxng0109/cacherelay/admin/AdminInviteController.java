package io.github.kxng0109.cacherelay.admin;

import java.util.UUID;

import io.github.kxng0109.cacherelay.auth.InviteService;
import io.github.kxng0109.cacherelay.auth.dto.InviteRequest;
import io.github.kxng0109.cacherelay.auth.dto.InviteResponse;
import io.github.kxng0109.cacherelay.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issues single-use invitations under {@code /v1/admin/invites}.
 *
 * <p>Reachable with the master key (including the zero-user bootstrap) or an admin
 * session. Every response carries a copyable redemption link; the link is additionally
 * emailed when an address was supplied and the mail channel is configured.
 */
@RestController
@RequestMapping("/v1/admin/invites")
@RequiredArgsConstructor
@Tag(name = "Admin - Invites", description = "Single-use invitations with copyable links and conditional email delivery")
public class AdminInviteController {

	private final InviteService invites;

	/**
	 * Creates an invite.
	 *
	 * @param body    invited address (optional) and privilege
	 * @param request current request (inviter attribution and fallback link base)
	 * @return copyable link plus whether it was emailed
	 */
	@Operation(
			summary = "Create invite",
			description = "Creates a single-use invite; returns a copyable redemption link and emails it when possible",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "201", description = "Invite created"),
			@ApiResponse(responseCode = "404", description = "Hidden: caller is not an admin")
	})
	@PostMapping
	public ResponseEntity<InviteResponse> create(@Valid @RequestBody InviteRequest body,
			HttpServletRequest request) {
		Object inviter = request.getAttribute(AdminAuthFilter.ATTRIBUTE_ADMIN_ID);
		UUID createdBy = inviter instanceof UUID uuid ? uuid : null;
		String baseUrl = request.getScheme() + "://" + request.getServerName()
				+ (isDefaultPort(request) ? "" : ":" + request.getServerPort());
		InviteService.CreatedInvite created = invites.create(createdBy, body.email(), body.admin(),
				baseUrl, request.getHeader("X-Request-ID"));
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(new InviteResponse(created.link(), created.emailed()));
	}

	private static boolean isDefaultPort(HttpServletRequest request) {
		return ("http".equals(request.getScheme()) && request.getServerPort() == 80)
				|| ("https".equals(request.getScheme()) && request.getServerPort() == 443);
	}
}
