package io.github.kxng0109.cacherelay.auth;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.kxng0109.cacherelay.auth.backfill.BackfillRequest;

/**
 * IdP-driven team provisioning on SSO login: resolves the account's org,
 * matches presented IdP groups and roles against configured patterns,
 * materializes teams, and flips memberships to mirror the IdP.
 *
 * <p>Registrations without a claim mapping keep legacy behavior (shadow
 * account, no teams) so existing SSO keeps working until the operator
 * configures teams. Tenant allowlists fail closed. Team lead is the maximum
 * IdP-derivable privilege: gateway admin stays locally assigned and is never
 * touched here, no matter what the claims assert. Accounts with no mapped
 * team land in the org's unassigned team under least privilege. When several
 * patterns match one group, exact beats prefix, longer prefixes beat shorter
 * ones, and earlier patterns win remaining ties.</p>
 */
@Service
public class SsoProvisioningService {

	private static final String CALLBACK_PATH = "/oauth2/callback";

	private final SsoOrgRepository orgs;

	private final SsoTeamRepository teams;

	private final SsoMembershipRepository memberships;

	private final SsoClaimProperties claimProperties;

	private final SsoBackfillProperties backfillProperties;

	private final AuthAuditService audit;

	/**
	 * Creates the service.
	 *
	 * @param orgs               org persistence, never {@code null}
	 * @param teams              team persistence, never {@code null}
	 * @param memberships        membership persistence, never {@code null}
	 * @param claimProperties    per-registration claim mappings, never {@code null}
	 * @param backfillProperties per-registration backfill entries, never {@code null}
	 * @param audit              audit log, never {@code null}
	 */
	public SsoProvisioningService(SsoOrgRepository orgs, SsoTeamRepository teams,
			SsoMembershipRepository memberships, SsoClaimProperties claimProperties,
			SsoBackfillProperties backfillProperties, AuthAuditService audit) {
		this.orgs = orgs;
		this.teams = teams;
		this.memberships = memberships;
		this.claimProperties = claimProperties;
		this.backfillProperties = backfillProperties;
		this.audit = audit;
	}

	/**
	 * Decides whether a login needs a blocking backfill: a backfill entry
	 * exists, a claim mapping can match its groups, and the account holds no
	 * memberships yet (first login, or a previously denied one).
	 *
	 * @param userId         local account id, never {@code null}
	 * @param registrationId Spring registration id, never {@code null}
	 * @return {@code true} when the caller must backfill before provisioning
	 */
	public boolean needsBackfill(UUID userId, String registrationId) {
		if (backfillProperties.forRegistration(registrationId).isEmpty()) {
			return false;
		}
		if (claimProperties.forRegistration(registrationId).isEmpty()) {
			return false;
		}
		return memberships.findByUserId(userId).isEmpty();
	}

	/**
	 * Provisions with backfill groups merged into the token claims, then
	 * enriches matched team names from IdP display names.
	 *
	 * @param user             resolved local account, never {@code null}
	 * @param registrationId   Spring registration id, never {@code null}
	 * @param issuer           IdP issuer bound to created teams, never {@code null}
	 * @param attributes       principal attributes, never {@code null}
	 * @param idTokenClaims    ID token claims, never {@code null}
	 * @param fetchedGroups    backfilled id to display name, never {@code null}
	 * @param ip               remote address, or {@code null}
	 * @param requestId        correlation id, or {@code null}
	 * @return the account, or empty when checks deny the login
	 */
	@Transactional
	public Optional<UserAccount> provisionWithBackfill(UserAccount user, String registrationId,
			String issuer, Map<String, Object> attributes, Map<String, Object> idTokenClaims,
			Map<String, String> fetchedGroups, String ip, String requestId) {
		Optional<SsoClaimProperties.RegistrationTeams> mapping =
				claimProperties.forRegistration(registrationId);
		String groupsKey = mapping.map(SsoClaimProperties.RegistrationTeams::groupsClaim)
				.orElse("groups");
		Set<String> union = new LinkedHashSet<>(BackfillRequest.extractStrings(
				attributes.get(groupsKey)));
		union.addAll(fetchedGroups.keySet());
		Map<String, Object> merged = new LinkedHashMap<>(attributes);
		merged.put(groupsKey, new ArrayList<>(union));
		Optional<UserAccount> provisioned = provision(user, registrationId, issuer, merged,
				idTokenClaims, ip, requestId);
		if (provisioned.isEmpty()) {
			return provisioned;
		}
		enrichTeamNames(registrationId, issuer, fetchedGroups);
		return provisioned;
	}

	/**
	 * Provisions org, teams, and memberships for a freshly resolved account.
	 *
	 * @param user             resolved local account, never {@code null}
	 * @param registrationId   Spring registration id, never {@code null}
	 * @param issuer           IdP issuer bound to created teams, never {@code null}
	 * @param attributes       principal attributes (groups/roles claims), never {@code null}
	 * @param idTokenClaims    ID token claims (tenant claim), never {@code null}
	 * @param ip               remote address, or {@code null}
	 * @param requestId        correlation id, or {@code null}
	 * @return the account, or empty when the tenant check denies the login
	 */
	@Transactional
	public Optional<UserAccount> provision(UserAccount user, String registrationId, String issuer,
			Map<String, Object> attributes, Map<String, Object> idTokenClaims, String ip,
			String requestId) {
		Optional<SsoClaimProperties.RegistrationTeams> mapping =
				claimProperties.forRegistration(registrationId);
		if (mapping.isEmpty()) {
			return Optional.of(user);
		}
		SsoClaimProperties.RegistrationTeams rules = mapping.get();
		SsoOrg org = orgs.findBySlug(rules.orgSlug())
				.orElseGet(() -> orgs.save(new SsoOrg(rules.orgSlug(), rules.orgSlug())));
		SsoTeam unassigned = findOrCreateUnassigned(org);
		if (!tenantAllowed(rules, idTokenClaims)) {
			audit.record(AuthAuditService.ACTION_SSO_TEAMS_SYNC, AuthAuditService.SEVERITY_WARN,
					user.getUsername(), CALLBACK_PATH, AuthAuditService.OUTCOME_FAILURE, ip,
					requestId);
			return Optional.empty();
		}
		Set<String> candidates = new LinkedHashSet<>();
		candidates.addAll(extractStrings(attributes.get(rules.groupsClaim())));
		candidates.addAll(extractStrings(attributes.get(rules.rolesClaim())));
		Map<String, TeamRole> matched = matchAll(rules.groupPatterns(), candidates);
		Map<String, SsoTeam> resolved = new LinkedHashMap<>();
		for (Map.Entry<String, TeamRole> entry : matched.entrySet()) {
			resolved.put(entry.getKey(), findOrCreateTeam(org, issuer, entry.getKey()));
		}
		List<SsoMembership> existing = memberships.findByUserId(user.getId());
		Map<UUID, SsoMembership> byTeam = new LinkedHashMap<>();
		for (SsoMembership membership : existing) {
			byTeam.put(membership.getTeamId(), membership);
		}
		Set<UUID> activeNow = new HashSet<>();
		for (Map.Entry<String, SsoTeam> entry : resolved.entrySet()) {
			SsoTeam team = entry.getValue();
			SsoMembership current = byTeam.get(team.getId());
			TeamRole role = matched.get(entry.getKey());
			if (current == null) {
				memberships.save(new SsoMembership(user.getId(), team.getId(), role,
						MembershipStatus.ACTIVE));
			} else if (current.getStatus() != MembershipStatus.ACTIVE || current.getRole() != role) {
				current.move(role, MembershipStatus.ACTIVE);
				memberships.save(current);
			}
			activeNow.add(team.getId());
		}
		List<SsoTeam> orgTeams = teams.findByOrgId(org.getId());
		Map<UUID, SsoTeam> teamById = new LinkedHashMap<>();
		for (SsoTeam team : orgTeams) {
			teamById.put(team.getId(), team);
		}
		for (SsoMembership membership : existing) {
			if (membership.getStatus() == MembershipStatus.ACTIVE
					&& !activeNow.contains(membership.getTeamId())) {
				SsoTeam team = teamById.get(membership.getTeamId());
				if (team != null && isMapped(team)) {
					membership.move(membership.getRole(), MembershipStatus.INACTIVE);
					memberships.save(membership);
				}
			}
		}
		SsoMembership holding = byTeam.get(unassigned.getId());
		if (activeNow.isEmpty()) {
			if (holding == null) {
				memberships.save(new SsoMembership(user.getId(), unassigned.getId(),
						TeamRole.MEMBER, MembershipStatus.ACTIVE));
			} else if (holding.getStatus() != MembershipStatus.ACTIVE) {
				holding.move(holding.getRole(), MembershipStatus.ACTIVE);
				memberships.save(holding);
			}
		} else if (holding != null && holding.getStatus() == MembershipStatus.ACTIVE) {
			holding.move(holding.getRole(), MembershipStatus.INACTIVE);
			memberships.save(holding);
		}
		audit.record(AuthAuditService.ACTION_SSO_TEAMS_SYNC, AuthAuditService.SEVERITY_INFO,
				user.getUsername(), CALLBACK_PATH, AuthAuditService.OUTCOME_SUCCESS, ip, requestId);
		return Optional.of(user);
	}

	private SsoTeam findOrCreateUnassigned(SsoOrg org) {
		return teams.findByOrgIdAndIdpIssuerAndIdpGroupId(org.getId(), "",
				SsoTeam.UNASSIGNED_GROUP_ID).orElseGet(() -> teams.save(
				new SsoTeam(org.getId(), "", SsoTeam.UNASSIGNED_GROUP_ID, "Unassigned")));
	}

	private SsoTeam findOrCreateTeam(SsoOrg org, String issuer, String groupId) {
		return teams.findByOrgIdAndIdpIssuerAndIdpGroupId(org.getId(), issuer, groupId)
				.orElseGet(() -> teams.save(new SsoTeam(org.getId(), issuer, groupId, groupId)));
	}

	private boolean tenantAllowed(SsoClaimProperties.RegistrationTeams rules,
			Map<String, Object> idTokenClaims) {
		if (rules.allowedTenants().isEmpty()) {
			return true;
		}
		if (rules.tenantClaim().isBlank()) {
			return false;
		}
		Object raw = idTokenClaims.get(rules.tenantClaim());
		if (!(raw instanceof String tenant) || tenant.isBlank()) {
			return false;
		}
		return rules.allowedTenants().contains(tenant);
	}

	private Set<String> extractStrings(Object claim) {
		return BackfillRequest.extractStrings(claim);
	}

	private void enrichTeamNames(String registrationId, String issuer,
			Map<String, String> fetchedGroups) {
		if (fetchedGroups.isEmpty()) {
			return;
		}
		Optional<SsoClaimProperties.RegistrationTeams> mapping =
				claimProperties.forRegistration(registrationId);
		if (mapping.isEmpty()) {
			return;
		}
		SsoOrg org = orgs.findBySlug(mapping.get().orgSlug()).orElseThrow(
				() -> new IllegalStateException(
						"Provisioned org missing for slug '" + mapping.get().orgSlug() + "'"));
		for (Map.Entry<String, String> fetched : fetchedGroups.entrySet()) {
			if (fetched.getValue() == null || fetched.getValue().isBlank()) {
				continue;
			}
			Optional<SsoTeam> team = teams.findByOrgIdAndIdpIssuerAndIdpGroupId(
					org.getId(), issuer, fetched.getKey());
			if (team.isPresent() && !fetched.getValue().equals(team.get().getName())) {
				team.get().rename(fetched.getValue());
				teams.save(team.get());
			}
		}
	}

	private Map<String, TeamRole> matchAll(List<String> patterns, Set<String> candidates) {
		Map<String, TeamRole> matched = new LinkedHashMap<>();
		for (String candidate : candidates) {
			ScoredMatch best = null;
			for (String pattern : patterns) {
				ScoredMatch scored = score(pattern, candidate);
				if (scored != null && (best == null || scored.beats(best))) {
					best = scored;
				}
			}
			if (best != null) {
				matched.putIfAbsent(candidate, best.role());
			}
		}
		return matched;
	}

	private ScoredMatch score(String pattern, String candidate) {
		String body = pattern;
		TeamRole role = TeamRole.MEMBER;
		int separator = pattern.lastIndexOf(':');
		if (separator >= 0) {
			String suffix = pattern.substring(separator + 1);
			if ("LEAD".equalsIgnoreCase(suffix)) {
				role = TeamRole.LEAD;
			} else if ("MEMBER".equalsIgnoreCase(suffix)) {
				role = TeamRole.MEMBER;
			} else {
				return null;
			}
			body = pattern.substring(0, separator);
		}
		if (body.endsWith("*")) {
			String prefix = body.substring(0, body.length() - 1);
			return candidate.startsWith(prefix) ? new ScoredMatch(role, 1, prefix.length()) : null;
		}
		return candidate.equals(body) ? new ScoredMatch(role, 2, body.length()) : null;
	}

	/**
	 * Decides whether a team tracks an IdP group. The unassigned team is the
	 * only row with a blank issuer by construction, so one predicate suffices.
	 */
	private boolean isMapped(SsoTeam team) {
		return !team.getIdpIssuer().isBlank();
	}
	/**
	 * One pattern's verdict on one candidate: exact beats prefix and longer
	 * prefixes beat shorter ones. Remaining ties keep the earlier pattern
	 * because only strictly better verdicts supersede the incumbent.
	 *
	 * @param role   role the pattern assigns
	 * @param rank   {@code 2} for exact hits, {@code 1} for prefix hits
	 * @param length matched body length for prefix tie-breaking
	 */
	private record ScoredMatch(TeamRole role, int rank, int length) {

		/**
		 * Decides whether this verdict supersedes another.
		 *
		 * @param current best verdict so far, never {@code null}
		 * @return {@code true} when this verdict wins
		 */
		boolean beats(ScoredMatch current) {
			return rank != current.rank ? rank > current.rank : length > current.length;
		}
	}
}
