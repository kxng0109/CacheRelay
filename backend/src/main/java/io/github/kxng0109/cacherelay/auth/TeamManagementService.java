package io.github.kxng0109.cacherelay.auth;

import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Administrative writes for locally managed orgs, teams, and memberships.
 *
 * <p>IdP-mapped teams (non-blank issuer) are read-only here: the login sync owns
 * their membership lifecycle, so manual writes answer 409 instead of being
 * silently undone at the next login. Locally created teams carry a blank issuer
 * and a {@code local:} group id, which the sync ignores entirely. Concurrent
 * duplicate creates serialize on the database unique constraints and answer 409.</p>
 */
@Service
public class TeamManagementService {

	/**
	 * Group-id namespace for locally managed teams. An IdP group id can never
	 * equal one of these without sharing the team's blank issuer, and the
	 * {@code (org_id, idp_issuer, idp_group_id)} unique key keeps every binding
	 * distinct.
	 */
	static final String LOCAL_GROUP_PREFIX = "local:";

	private static final Pattern SLUG = Pattern.compile("^[a-z0-9-]{1,64}$");

	private final SsoOrgRepository orgs;

	private final SsoTeamRepository teams;

	private final SsoMembershipRepository memberships;

	private final UserAccountRepository users;

	private final InviteTokenRepository invites;

	/**
	 * Creates the service.
	 *
	 * @param orgs        org persistence
	 * @param teams       team persistence
	 * @param memberships membership persistence
	 * @param users       account persistence for placement validation
	 * @param invites     invite persistence for team-delete guards
	 */
	public TeamManagementService(SsoOrgRepository orgs, SsoTeamRepository teams,
			SsoMembershipRepository memberships, UserAccountRepository users,
			InviteTokenRepository invites) {
		this.orgs = orgs;
		this.teams = teams;
		this.memberships = memberships;
		this.users = users;
		this.invites = invites;
	}

	/**
	 * Creates an org with a normalized slug.
	 *
	 * @param slug        desired org key, trimmed and lowercased before validation
	 * @param displayName human name, trimmed, 1..128 chars
	 * @return the persisted org
	 * @throws ResponseStatusException 400 for malformed slug or name, 409 for duplicate slug
	 */
	@Transactional
	public SsoOrg createOrg(String slug, String displayName) {
		String clean = slug == null ? "" : slug.trim().toLowerCase();
		if (!SLUG.matcher(clean).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "malformed org slug");
		}
		String name = displayName == null ? "" : displayName.trim();
		if (name.isEmpty() || name.length() > 128) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "malformed org name");
		}
		if (orgs.findBySlug(clean).isPresent()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "org slug already exists");
		}
		try {
			return orgs.save(new SsoOrg(clean, name));
		} catch (DataIntegrityViolationException duplicate) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "org slug already exists");
		}
	}

	/**
	 * Renames an org's display name. Slugs are immutable once issued.
	 *
	 * @param orgId       owning org id
	 * @param displayName human name, trimmed, 1..128 chars
	 * @return the updated org
	 * @throws ResponseStatusException 404 for unknown orgs, 400 for malformed names
	 */
	@Transactional
	public SsoOrg renameOrg(UUID orgId, String displayName) {
		SsoOrg org = orgs.findById(orgId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"org not found"));
		String name = displayName == null ? "" : displayName.trim();
		if (name.isEmpty() || name.length() > 128) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "malformed org name");
		}
		org.rename(name);
		return orgs.save(org);
	}

	/**
	 * Deletes an org once it holds no teams.
	 *
	 * @param orgId owning org id
	 * @throws ResponseStatusException 404 for unknown orgs, 409 while teams remain
	 */
	@Transactional
	public void deleteOrg(UUID orgId) {
		SsoOrg org = orgs.findById(orgId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"org not found"));
		if (!teams.findByOrgId(org.getId()).isEmpty()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "org still holds teams");
		}
		orgs.delete(org);
	}

	/**
	 * Creates a locally managed team inside an org.
	 *
	 * @param orgId owning org id
	 * @param name  display name, trimmed, 1..128 chars
	 * @return the persisted team
	 * @throws ResponseStatusException 404 for unknown orgs, 400 for malformed names,
	 *                                  409 for duplicate team names
	 */
	@Transactional
	public SsoTeam createTeam(UUID orgId, String name) {
		SsoOrg org = orgs.findById(orgId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"org not found"));
		String clean = cleanName(name);
		String groupId = LOCAL_GROUP_PREFIX + slugify(clean);
		try {
			return teams.save(new SsoTeam(org.getId(), "", groupId, clean));
		} catch (DataIntegrityViolationException duplicate) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "team already exists");
		}
	}

	/**
	 * Renames a locally managed team, keeping its identity stable.
	 *
	 * @param teamId team id
	 * @param name   display name, trimmed, 1..128 chars
	 * @return the updated team
	 * @throws ResponseStatusException 404 for unknown teams, 400 for malformed names,
	 *                                  409 for IdP-managed teams
	 */
	@Transactional
	public SsoTeam renameTeam(UUID teamId, String name) {
		SsoTeam team = requireLocalTeam(teamId);
		String clean = cleanName(name);
		team.rename(clean);
		return teams.save(team);
	}

	/**
	 * Deletes a locally managed team once nothing references it.
	 *
	 * @param teamId team id
	 * @throws ResponseStatusException 404 for unknown teams, 409 for IdP-managed teams,
	 *                                  the unassigned holding team, teams with active
	 *                                  memberships, or teams backing pending invites
	 */
	@Transactional
	public void deleteTeam(UUID teamId) {
		SsoTeam team = requireLocalTeam(teamId);
		if (SsoTeam.UNASSIGNED_GROUP_ID.equals(team.getIdpGroupId())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"unassigned holding team cannot be deleted");
		}
		if (!memberships.findByTeamIdAndStatus(team.getId(), MembershipStatus.ACTIVE).isEmpty()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"team still holds active memberships");
		}
		if (!invites.findByTeamIdAndConsumedAtIsNull(team.getId()).isEmpty()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"team still backs pending invites");
		}
		teams.delete(team);
	}

	/**
	 * Assigns an account to a locally managed team, reactivating dormant rows.
	 *
	 * @param teamId team id
	 * @param userId account id
	 * @param role   team-scoped role
	 * @return the active membership
	 * @throws ResponseStatusException 404 for unknown teams or accounts, 409 for
	 *                                  IdP-managed teams or disabled accounts
	 */
	@Transactional
	public SsoMembership assignMember(UUID teamId, UUID userId, TeamRole role) {
		SsoTeam team = requireLocalTeam(teamId);
		UserAccount account = users.findById(userId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"account not found"));
		if (account.isDisabled()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"account is disabled");
		}
		return memberships.findByUserIdAndTeamId(userId, team.getId())
				.map(current -> {
					current.move(role, MembershipStatus.ACTIVE);
					return memberships.save(current);
				})
				.orElseGet(() -> memberships.save(new SsoMembership(userId, team.getId(), role,
						MembershipStatus.ACTIVE)));
	}

	/**
	 * Revokes an account's membership without deleting history.
	 *
	 * @param teamId team id
	 * @param userId account id
	 * @return the inactive membership
	 * @throws ResponseStatusException 404 for unknown teams or memberships, 409 for
	 *                                  IdP-managed teams
	 */
	@Transactional
	public SsoMembership revokeMember(UUID teamId, UUID userId) {
		SsoTeam team = requireLocalTeam(teamId);
		SsoMembership current = memberships.findByUserIdAndTeamId(userId, team.getId())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"membership not found"));
		current.move(current.getRole(), MembershipStatus.INACTIVE);
		return memberships.save(current);
	}

	private SsoTeam requireLocalTeam(UUID teamId) {
		SsoTeam team = teams.findById(teamId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"team not found"));
		if (!team.getIdpIssuer().isBlank()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"team is IdP-managed and read-only");
		}
		return team;
	}

	private static String cleanName(String name) {
		String clean = name == null ? "" : name.trim();
		if (clean.isEmpty() || clean.length() > 128) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "malformed team name");
		}
		return clean;
	}

	private static String slugify(String name) {
		String slug = name.toLowerCase().replaceAll("[^a-z0-9-]", "-");
		if (slug.isBlank() || slug.chars().allMatch(c -> c == '-')) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "malformed team name");
		}
		return slug;
	}

	/**
	 * Resolves a team for manual placement, failing closed on anything the admin
	 * must not target.
	 *
	 * @param teamId placed team id
	 * @return the placeable team
	 * @throws ResponseStatusException 404 for unknown teams, 409 for IdP-managed teams
	 */
	@Transactional(readOnly = true)
	public SsoTeam requirePlaceableTeam(UUID teamId) {
		SsoTeam team = teams.findById(teamId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
						"team not found"));
		if (!team.getIdpIssuer().isBlank()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"team is IdP-managed and read-only");
		}
		return team;
	}
}
