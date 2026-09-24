package io.github.kxng0109.cacherelay.auth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SsoProvisioningService")
class SsoProvisioningServiceTest {

	private SsoOrgRepository orgs;

	private SsoTeamRepository teams;

	private SsoMembershipRepository memberships;

	private AuthAuditService audit;

	private SsoOrg org;

	private SsoTeam unassigned;

	@BeforeEach
	void setUp() {
		orgs = mock(SsoOrgRepository.class);
		teams = mock(SsoTeamRepository.class);
		memberships = mock(SsoMembershipRepository.class);
		audit = mock(AuthAuditService.class);
		org = new SsoOrg("acme", "Acme");
		unassigned = new SsoTeam(org.getId(), "", SsoTeam.UNASSIGNED_GROUP_ID, "Unassigned");
		when(orgs.findBySlug("acme")).thenReturn(Optional.of(org));
		when(teams.findByOrgIdAndIdpIssuerAndIdpGroupId(org.getId(), "",
				SsoTeam.UNASSIGNED_GROUP_ID)).thenReturn(Optional.of(unassigned));
		when(memberships.findByUserId(any())).thenReturn(List.of());
		when(teams.findByOrgId(org.getId())).thenReturn(List.of(unassigned));
	}

	private SsoProvisioningService serviceWith(SsoClaimProperties.RegistrationTeams... mappings) {
		return new SsoProvisioningService(orgs, teams, memberships,
				new SsoClaimProperties(List.of(mappings)),
				new SsoBackfillProperties(List.of(
						new SsoBackfillProperties.RegistrationBackfill("azure",
								BackfillMode.ENTRA_GRAPH, "tenant-1"))),
				audit);
	}

	private SsoClaimProperties.RegistrationTeams mapping(String registrationId) {
		return new SsoClaimProperties.RegistrationTeams(registrationId, "acme", "groups",
				"roles", "tid", List.of("tid-1"), List.of("eng-*:MEMBER", "eng-leads:LEAD"));
	}

	private UserAccount user() {
		return new UserAccount("op", null, null, false);
	}

	private Map<String, Object> attributes(Object groups, Object roles) {
		return Map.of("groups", groups, "roles", roles);
	}

	private Map<String, Object> idToken(String tid) {
		return Map.of("tid", tid);
	}

	private void stubUnassignedPresent() {
		when(teams.findByOrgIdAndIdpIssuerAndIdpGroupId(org.getId(), "",
				SsoTeam.UNASSIGNED_GROUP_ID)).thenReturn(Optional.of(unassigned));
	}

	@Test
	@DisplayName("unmapped registrations keep legacy behavior without teams")
	void legacyRegistrationKeepsAccount() {
		SsoProvisioningService service = serviceWith();
		UserAccount account = user();

		Optional<UserAccount> resolved = service.provision(account, "azure",
				"https://login.example.com/tid-1", attributes(List.of("eng-a"), List.of()),
				idToken("tid-1"), null, null);

		assertThat(resolved).contains(account);
		verify(teams, never()).save(any());
		verify(memberships, never()).save(any());
		verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("wrong tenants fail closed with an audit record")
	void wrongTenantDenied() {
		SsoProvisioningService service = serviceWith(mapping("azure"));
		UserAccount account = user();

		Optional<UserAccount> resolved = service.provision(account, "azure",
				"https://login.example.com/tid-9", attributes(List.of("eng-a"), List.of()),
				idToken("tid-9"), "10.0.0.2", "req-9");

		assertThat(resolved).isEmpty();
		verify(audit).record(AuthAuditService.ACTION_SSO_TEAMS_SYNC,
				AuthAuditService.SEVERITY_WARN, "op", "/oauth2/callback",
				AuthAuditService.OUTCOME_FAILURE, "10.0.0.2", "req-9");
		verify(teams, never()).save(any());
	}

	@Test
	@DisplayName("empty allowlists skip the tenant check")
	void emptyAllowlistSkipsTenantCheck() {
		SsoClaimProperties.RegistrationTeams open = new SsoClaimProperties.RegistrationTeams(
				"azure", "acme", "groups", "roles", "tid", List.of(), List.of("eng-a"));
		SsoProvisioningService service = serviceWith(open);
		UserAccount account = user();
		when(teams.findByOrgIdAndIdpIssuerAndIdpGroupId(any(), any(), any()))
				.thenReturn(Optional.empty());
		stubUnassignedPresent();
		when(teams.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		Optional<UserAccount> resolved = service.provision(account, "azure", "iss",
				attributes(List.of("eng-a"), List.of()), Map.of(), null, null);

		assertThat(resolved).contains(account);
	}

	@Test
	@DisplayName("exact and prefix groups match while others stay out")
	void groupPatternsMatch() {
		SsoProvisioningService service = serviceWith(mapping("azure"));
		UserAccount account = user();
		when(teams.findByOrgIdAndIdpIssuerAndIdpGroupId(any(), any(), any()))
				.thenReturn(Optional.empty());
		stubUnassignedPresent();
		when(teams.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		Optional<UserAccount> resolved = service.provision(account, "azure",
				"https://login.example.com/tid-1", attributes(List.of("eng-a", "sales"), List.of()),
				idToken("tid-1"), null, null);

		assertThat(resolved).contains(account);
		verify(teams, times(1)).save(any(SsoTeam.class));
		verify(memberships, times(1)).save(any(SsoMembership.class));
	}

	@Test
	@DisplayName("lead suffix assigns team lead but never gateway admin")
	void leadSuffixAssignsLeadOnly() {
		SsoProvisioningService service = serviceWith(mapping("azure"));
		UserAccount account = user();
		SsoTeam leads = new SsoTeam(org.getId(), "https://login.example.com/tid-1",
				"eng-leads", "eng-leads");
		when(teams.findByOrgIdAndIdpIssuerAndIdpGroupId(org.getId(),
				"https://login.example.com/tid-1", "eng-leads")).thenReturn(Optional.of(leads));

		service.provision(account, "azure", "https://login.example.com/tid-1",
				attributes(List.of("eng-leads"), List.of("admin", "owner")), idToken("tid-1"),
				null, null);

		assertThat(account.isAdmin()).isFalse();
		verify(memberships).save(any(SsoMembership.class));
	}

	@Test
	@DisplayName("unknown role suffixes never match")
	void unknownSuffixNeverMatches() {
		SsoClaimProperties.RegistrationTeams weird = new SsoClaimProperties.RegistrationTeams(
				"azure", "acme", "groups", "roles", "", List.of(), List.of("eng-a:BOSS"));
		SsoProvisioningService service = serviceWith(weird);
		UserAccount account = user();

		service.provision(account, "azure", "iss", attributes(List.of("eng-a"), List.of()),
				Map.of(), null, null);

		verify(teams, never()).save(any());
	}

	@Test
	@DisplayName("removed groups deactivate while active ones stay")
	void removalDeactivates() {
		SsoProvisioningService service = serviceWith(mapping("azure"));
		UserAccount account = user();
		SsoTeam eng = new SsoTeam(org.getId(), "https://login.example.com/tid-1", "eng-a", "eng-a");
		SsoTeam gone = new SsoTeam(org.getId(), "https://login.example.com/tid-1", "eng-old", "eng-old");
		when(teams.findByOrgId(org.getId())).thenReturn(List.of(unassigned, eng, gone));
		when(teams.findByOrgIdAndIdpIssuerAndIdpGroupId(org.getId(),
				"https://login.example.com/tid-1", "eng-a")).thenReturn(Optional.of(eng));
		List<SsoMembership> stored = new ArrayList<>(List.of(
				new SsoMembership(account.getId(), eng.getId(), TeamRole.MEMBER, MembershipStatus.ACTIVE),
				new SsoMembership(account.getId(), gone.getId(), TeamRole.MEMBER, MembershipStatus.ACTIVE),
				new SsoMembership(account.getId(), unassigned.getId(), TeamRole.MEMBER, MembershipStatus.ACTIVE)));
		when(memberships.findByUserId(account.getId())).thenReturn(stored);

		service.provision(account, "azure", "https://login.example.com/tid-1",
				attributes(List.of("eng-a"), List.of()), idToken("tid-1"), null, null);

		assertThat(stored.get(1).getStatus()).isEqualTo(MembershipStatus.INACTIVE);
		assertThat(stored.get(2).getStatus()).isEqualTo(MembershipStatus.INACTIVE);
		assertThat(stored.get(0).getStatus()).isEqualTo(MembershipStatus.ACTIVE);
	}

	@Test
	@DisplayName("no mapped groups land in unassigned")
	void emptyClaimsLandUnassigned() {
		SsoProvisioningService service = serviceWith(mapping("azure"));
		UserAccount account = user();

		service.provision(account, "azure", "https://login.example.com/tid-1",
				attributes(List.of(), List.of()), idToken("tid-1"), null, null);

		verify(teams, never()).save(any());
		verify(memberships, times(1)).save(any(SsoMembership.class));
	}

	@Test
	@DisplayName("unchanged memberships skip writes")
	void unchangedMembershipsSkipWrites() {
		SsoProvisioningService service = serviceWith(mapping("azure"));
		UserAccount account = user();
		SsoTeam eng = new SsoTeam(org.getId(), "https://login.example.com/tid-1", "eng-a", "eng-a");
		when(teams.findByOrgId(org.getId())).thenReturn(List.of(unassigned, eng));
		when(teams.findByOrgIdAndIdpIssuerAndIdpGroupId(org.getId(),
				"https://login.example.com/tid-1", "eng-a")).thenReturn(Optional.of(eng));
		SsoMembership current = new SsoMembership(account.getId(), eng.getId(), TeamRole.MEMBER,
				MembershipStatus.ACTIVE);
		when(memberships.findByUserId(account.getId())).thenReturn(List.of(current));

		service.provision(account, "azure", "https://login.example.com/tid-1",
				attributes(List.of("eng-a"), List.of()), idToken("tid-1"), null, null);

		verify(memberships, never()).save(any());
	}

	@Test
	@DisplayName("role changes update the membership")
	void roleChangeUpdates() {
		SsoProvisioningService service = serviceWith(mapping("azure"));
		UserAccount account = user();
		SsoTeam leads = new SsoTeam(org.getId(), "https://login.example.com/tid-1",
				"eng-leads", "eng-leads");
		when(teams.findByOrgIdAndIdpIssuerAndIdpGroupId(org.getId(),
				"https://login.example.com/tid-1", "eng-leads")).thenReturn(Optional.of(leads));
		SsoMembership current = new SsoMembership(account.getId(), leads.getId(), TeamRole.MEMBER,
				MembershipStatus.ACTIVE);
		when(memberships.findByUserId(account.getId())).thenReturn(List.of(current));

		service.provision(account, "azure", "https://login.example.com/tid-1",
				attributes(List.of("eng-leads"), List.of()), idToken("tid-1"), null, null);

		assertThat(current.getRole()).isEqualTo(TeamRole.LEAD);
		verify(memberships, times(1)).save(current);
	}

	@Test
	@DisplayName("malformed claim shapes never provision")
	void malformedClaimsIgnored() {
		SsoProvisioningService service = serviceWith(mapping("azure"));
		UserAccount account = user();
		when(teams.findByOrgIdAndIdpIssuerAndIdpGroupId(any(), any(), any()))
				.thenReturn(Optional.empty());
		stubUnassignedPresent();
		when(teams.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		service.provision(account, "azure", "https://login.example.com/tid-1",
				Map.of("groups", 42, "roles", "eng-a"), idToken("tid-1"), null, null);

		verify(teams, times(1)).save(any(SsoTeam.class));
		verify(memberships, times(1)).save(any(SsoMembership.class));
	}

	@Test
	@DisplayName("string arrays and blank values extract safely")
	void arrayAndBlankClaimsExtract() {
		SsoProvisioningService service = serviceWith(mapping("azure"));
		UserAccount account = user();
		when(teams.findByOrgIdAndIdpIssuerAndIdpGroupId(any(), any(), any()))
				.thenReturn(Optional.empty());
		stubUnassignedPresent();
		when(teams.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		service.provision(account, "azure", "https://login.example.com/tid-1",
				attributes(new String[]{"eng-a", "  ", ""}, List.of()), idToken("tid-1"), null, null);

		verify(teams, times(1)).save(any(SsoTeam.class));
	}

	@Test
	@DisplayName("duplicate values create one team")
	void duplicateValuesDeduped() {
		SsoProvisioningService service = serviceWith(mapping("azure"));
		UserAccount account = user();
		when(teams.findByOrgIdAndIdpIssuerAndIdpGroupId(any(), any(), any()))
				.thenReturn(Optional.empty());
		stubUnassignedPresent();
		when(teams.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		service.provision(account, "azure", "https://login.example.com/tid-1",
				attributes(List.of("eng-a", "eng-a"), List.of("eng-a")), idToken("tid-1"), null, null);

		verify(teams, times(1)).save(any(SsoTeam.class));
		verify(memberships, times(1)).save(any(SsoMembership.class));
	}

	@Test
	@DisplayName("first sight creates the org and its unassigned team")
	void firstSightCreatesOrg() {
		when(orgs.findBySlug("brand-new")).thenReturn(Optional.empty());
		when(orgs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		when(teams.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		SsoClaimProperties.RegistrationTeams fresh = new SsoClaimProperties.RegistrationTeams(
				"okta", "brand-new", "groups", "roles", "", List.of(), List.of());
		SsoProvisioningService service = serviceWith(fresh);
		UserAccount account = user();

		Optional<UserAccount> resolved = service.provision(account, "okta", "iss",
				attributes(List.of(), List.of()), Map.of(), null, null);

		assertThat(resolved).contains(account);
		verify(orgs, times(1)).save(any(SsoOrg.class));
		verify(teams, times(1)).save(any(SsoTeam.class));
	}

	@Test
	@DisplayName("allowlist without a tenant claim fails closed")
	void allowlistWithoutClaimDenies() {
		SsoClaimProperties.RegistrationTeams strict = new SsoClaimProperties.RegistrationTeams(
				"azure", "acme", "groups", "roles", "", List.of("tid-1"), List.of("eng-a"));
		SsoProvisioningService service = serviceWith(strict);

		assertThat(service.provision(user(), "azure", "iss",
				attributes(List.of("eng-a"), List.of()), Map.of("tid", "tid-1"), null, null))
				.isEmpty();
	}

	@Test
	@DisplayName("non-string tenant values fail closed")
	void nonStringTenantDenies() {
		SsoProvisioningService service = serviceWith(mapping("azure"));

		assertThat(service.provision(user(), "azure", "iss",
				attributes(List.of("eng-a"), List.of()), Map.of("tid", 42), null, null))
				.isEmpty();
	}

	@Test
	@DisplayName("absent claim keys behave like empty claims")
	void absentClaimKeysLandUnassigned() {
		SsoProvisioningService service = serviceWith(mapping("azure"));
		UserAccount account = user();

		service.provision(account, "azure", "https://login.example.com/tid-1",
				Map.of("roles", List.of()), idToken("tid-1"), null, null);

		verify(teams, never()).save(any());
		verify(memberships, times(1)).save(any(SsoMembership.class));
	}

	@Test
	@DisplayName("non-string collection elements never provision")
	void mixedCollectionSkipsNonStrings() {
		SsoProvisioningService service = serviceWith(mapping("azure"));
		UserAccount account = user();

		service.provision(account, "azure", "https://login.example.com/tid-1",
				attributes(List.of(42, true), List.of()), idToken("tid-1"), null, null);

		verify(teams, never()).save(any());
		verify(memberships, times(1)).save(any(SsoMembership.class));
	}

	@Test
	@DisplayName("exact patterns without suffix default to member")
	void exactPatternDefaultsMember() {
		SsoClaimProperties.RegistrationTeams exact = new SsoClaimProperties.RegistrationTeams(
				"azure", "acme", "groups", "roles", "", List.of(), List.of("eng-ops"));
		SsoProvisioningService service = serviceWith(exact);
		UserAccount account = user();
		when(teams.findByOrgIdAndIdpIssuerAndIdpGroupId(any(), any(), any()))
				.thenReturn(Optional.empty());
		stubUnassignedPresent();
		when(teams.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		SsoMembership created = new SsoMembership(account.getId(), UUID.randomUUID(),
				TeamRole.MEMBER, MembershipStatus.ACTIVE);
		when(memberships.save(any(SsoMembership.class))).thenReturn(created);

		service.provision(account, "azure", "iss", attributes(List.of("eng-ops"), List.of()),
				Map.of(), null, null);

		verify(teams, times(1)).save(any(SsoTeam.class));
	}

	@Test
	@DisplayName("longest prefix wins and ties go to the earlier pattern")
	void precedenceRules() {
		SsoClaimProperties.RegistrationTeams ranked = new SsoClaimProperties.RegistrationTeams(
				"azure", "acme", "groups", "roles", "", List.of(),
				List.of("eng-*:MEMBER", "eng-a*:LEAD", "eng-a:LEAD", "eng-a:MEMBER"));
		SsoProvisioningService service = serviceWith(ranked);
		UserAccount account = user();
		when(teams.findByOrgIdAndIdpIssuerAndIdpGroupId(any(), any(), any()))
				.thenReturn(Optional.empty());
		stubUnassignedPresent();
		when(teams.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		List<SsoMembership> saved = new ArrayList<>();
		when(memberships.save(any(SsoMembership.class))).thenAnswer(invocation -> {
			SsoMembership row = invocation.getArgument(0);
			saved.add(row);
			return row;
		});

		service.provision(account, "azure", "iss", attributes(List.of("eng-a"), List.of()),
				Map.of(), null, null);

		assertThat(saved).hasSize(1);
		assertThat(saved.getFirst().getRole()).isEqualTo(TeamRole.LEAD);
	}

	@Test
	@DisplayName("inactive matched memberships reactivate")
	void reactivation() {
		SsoProvisioningService service = serviceWith(mapping("azure"));
		UserAccount account = user();
		SsoTeam eng = new SsoTeam(org.getId(), "https://login.example.com/tid-1", "eng-a", "eng-a");
		when(teams.findByOrgId(org.getId())).thenReturn(List.of(unassigned, eng));
		when(teams.findByOrgIdAndIdpIssuerAndIdpGroupId(org.getId(),
				"https://login.example.com/tid-1", "eng-a")).thenReturn(Optional.of(eng));
		SsoMembership dormant = new SsoMembership(account.getId(), eng.getId(), TeamRole.MEMBER,
				MembershipStatus.INACTIVE);
		SsoMembership stale = new SsoMembership(account.getId(), UUID.randomUUID(),
				TeamRole.MEMBER, MembershipStatus.INACTIVE);
		when(memberships.findByUserId(account.getId())).thenReturn(List.of(dormant, stale));

		service.provision(account, "azure", "https://login.example.com/tid-1",
				attributes(List.of("eng-a"), List.of()), idToken("tid-1"), null, null);

		assertThat(dormant.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
		verify(memberships, times(1)).save(dormant);
	}

	@Test
	@DisplayName("memberships with unknown teams are left alone")
	void unknownTeamSkipped() {
		SsoProvisioningService service = serviceWith(mapping("azure"));
		UserAccount account = user();
		SsoTeam eng = new SsoTeam(org.getId(), "https://login.example.com/tid-1", "eng-a", "eng-a");
		when(teams.findByOrgId(org.getId())).thenReturn(List.of(unassigned, eng));
		when(teams.findByOrgIdAndIdpIssuerAndIdpGroupId(org.getId(),
				"https://login.example.com/tid-1", "eng-a")).thenReturn(Optional.of(eng));
		SsoMembership orphan = new SsoMembership(account.getId(), UUID.randomUUID(),
				TeamRole.MEMBER, MembershipStatus.ACTIVE);
		when(memberships.findByUserId(account.getId())).thenReturn(List.of(orphan));

		service.provision(account, "azure", "https://login.example.com/tid-1",
				attributes(List.of("eng-a"), List.of()), idToken("tid-1"), null, null);

		assertThat(orphan.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
	}

	@Test
	@DisplayName("inactive unassigned holdings reactivate when nothing matches")
	void unassignedReactivation() {
		SsoProvisioningService service = serviceWith(mapping("azure"));
		UserAccount account = user();
		SsoMembership holding = new SsoMembership(account.getId(), unassigned.getId(),
				TeamRole.MEMBER, MembershipStatus.INACTIVE);
		when(memberships.findByUserId(account.getId())).thenReturn(List.of(holding));

		service.provision(account, "azure", "https://login.example.com/tid-1",
				attributes(List.of(), List.of()), idToken("tid-1"), null, null);

		assertThat(holding.getStatus()).isEqualTo(MembershipStatus.ACTIVE);
		verify(memberships, times(1)).save(holding);
	}

	@Test
	@DisplayName("active unassigned holdings stay untouched without matches")
	void unassignedActiveNoop() {
		SsoProvisioningService service = serviceWith(mapping("azure"));
		UserAccount account = user();
		SsoMembership holding = new SsoMembership(account.getId(), unassigned.getId(),
				TeamRole.MEMBER, MembershipStatus.ACTIVE);
		when(memberships.findByUserId(account.getId())).thenReturn(List.of(holding));

		service.provision(account, "azure", "https://login.example.com/tid-1",
				attributes(List.of(), List.of()), idToken("tid-1"), null, null);

		verify(memberships, never()).save(any());
	}

	@Test
	@DisplayName("inactive unassigned holdings stay parked while mapped teams win")
	void unassignedInactiveStaysWhenMapped() {
		SsoProvisioningService service = serviceWith(mapping("azure"));
		UserAccount account = user();
		SsoTeam eng = new SsoTeam(org.getId(), "https://login.example.com/tid-1", "eng-a", "eng-a");
		when(teams.findByOrgIdAndIdpIssuerAndIdpGroupId(org.getId(),
				"https://login.example.com/tid-1", "eng-a")).thenReturn(Optional.of(eng));
		SsoMembership holding = new SsoMembership(account.getId(), unassigned.getId(),
				TeamRole.MEMBER, MembershipStatus.INACTIVE);
		when(memberships.findByUserId(account.getId())).thenReturn(List.of(holding));

		service.provision(account, "azure", "https://login.example.com/tid-1",
				attributes(List.of("eng-a"), List.of()), idToken("tid-1"), null, null);

		assertThat(holding.getStatus()).isEqualTo(MembershipStatus.INACTIVE);
		verify(memberships, times(1)).save(any(SsoMembership.class));
	}

	@Test
	@DisplayName("blank tenant values fail closed")
	void blankTenantDenies() {
		SsoProvisioningService service = serviceWith(mapping("azure"));

		assertThat(service.provision(user(), "azure", "iss",
				attributes(List.of("eng-a"), List.of()), Map.of("tid", "  "), null, null))
				.isEmpty();
	}

	@Test
	@DisplayName("null array elements never provision")
	void nullArrayElementsIgnored() {
		SsoProvisioningService service = serviceWith(mapping("azure"));
		UserAccount account = user();
		when(teams.findByOrgIdAndIdpIssuerAndIdpGroupId(any(), any(), any()))
				.thenReturn(Optional.empty());
		stubUnassignedPresent();
		when(teams.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		service.provision(account, "azure", "https://login.example.com/tid-1",
				attributes(new String[]{null, "eng-a"}, List.of()), idToken("tid-1"), null, null);

		verify(teams, times(1)).save(any(SsoTeam.class));
	}

	@Test
	@DisplayName("backfill need tracks entries, mappings, and existing memberships")
	void needsBackfillTracks() {
		SsoProvisioningService service = serviceWith(mapping("azure"));
		UserAccount account = user();

		assertThat(service.needsBackfill(account.getId(), "generic")).isFalse();
		assertThat(service.needsBackfill(account.getId(), "azure")).isTrue();

		when(memberships.findByUserId(account.getId())).thenReturn(List.of(
				new SsoMembership(account.getId(), UUID.randomUUID(), TeamRole.MEMBER,
						MembershipStatus.ACTIVE)));
		assertThat(service.needsBackfill(account.getId(), "azure")).isFalse();
	}

	@Test
	@DisplayName("backfill need fails without a claim mapping")
	void needsBackfillWithoutMapping() {
		SsoProvisioningService service = new SsoProvisioningService(orgs, teams, memberships,
				new SsoClaimProperties(List.of()),
				new SsoBackfillProperties(List.of(
						new SsoBackfillProperties.RegistrationBackfill("azure",
								BackfillMode.ENTRA_GRAPH, "tenant-1"))),
				audit);
		UserAccount account = user();

		assertThat(service.needsBackfill(account.getId(), "azure")).isFalse();
	}

	@Test
	@DisplayName("backfilled groups merge with claims and enrich names")
	void backfillMergesAndEnriches() {
		SsoProvisioningService service = serviceWith(mapping("azure"));
		UserAccount account = user();
		SsoTeam eng = new SsoTeam(org.getId(), "https://login.example.com/tid-1", "eng-a",
				"eng-a");
		when(teams.findByOrgIdAndIdpIssuerAndIdpGroupId(org.getId(),
				"https://login.example.com/tid-1", "eng-a")).thenReturn(Optional.of(eng));

		Optional<UserAccount> resolved = service.provisionWithBackfill(account, "azure",
				"https://login.example.com/tid-1", Map.of(), Map.of("tid", "tid-1"),
				Map.of("eng-a", "Engineering"), null, null);

		assertThat(resolved).contains(account);
		assertThat(eng.getName()).isEqualTo("Engineering");
		verify(teams).save(eng);
	}

	@Test
	@DisplayName("enrichment skips blank, same, and missing teams")
	void enrichmentSkips() {
		SsoProvisioningService service = serviceWith(mapping("azure"));
		UserAccount account = user();
		SsoTeam eng = new SsoTeam(org.getId(), "https://login.example.com/tid-1", "eng-a",
				"Engineering");
		when(teams.findByOrgIdAndIdpIssuerAndIdpGroupId(org.getId(),
				"https://login.example.com/tid-1", "eng-a")).thenReturn(Optional.of(eng));
		when(teams.findByOrgIdAndIdpIssuerAndIdpGroupId(org.getId(),
				"https://login.example.com/tid-1", "ghost")).thenReturn(Optional.empty());
		when(teams.findByOrgIdAndIdpIssuerAndIdpGroupId(org.getId(),
				"https://login.example.com/tid-1", "unmatched")).thenReturn(Optional.empty());

		service.provisionWithBackfill(account, "azure", "https://login.example.com/tid-1",
				Map.of(), Map.of("tid", "tid-1"),
				Map.of("eng-a", "Engineering", "blank", "  ", "ghost", "Ghost",
						"unmatched", "Unmatched"),
				null, null);

		verify(teams, never()).save(any());
	}

	@Test
	@DisplayName("backfill without a claim mapping keeps legacy behavior")
	void backfillWithoutMappingLegacy() {
		SsoProvisioningService service = new SsoProvisioningService(orgs, teams, memberships,
				new SsoClaimProperties(List.of()),
				new SsoBackfillProperties(List.of(
						new SsoBackfillProperties.RegistrationBackfill("azure",
								BackfillMode.ENTRA_GRAPH, "tenant-1"))),
				audit);
		UserAccount account = user();

		Optional<UserAccount> resolved = service.provisionWithBackfill(account, "azure", "iss",
				Map.of(), Map.of(), Map.of("eng-a", "Engineering"), null, null);

		assertThat(resolved).contains(account);
		verify(teams, never()).save(any());
		verify(memberships, never()).save(any());
	}

	@Test
	@DisplayName("backfill denials propagate through tenant checks")
	void backfillTenantDenyPropagates() {
		SsoProvisioningService service = serviceWith(mapping("azure"));
		UserAccount account = user();

		Optional<UserAccount> resolved = service.provisionWithBackfill(account, "azure",
				"https://login.example.com/tid-1", Map.of(), Map.of("tid", "wrong-tid"),
				Map.of("eng-a", "Engineering"), null, null);

		assertThat(resolved).isEmpty();
		verify(teams, never()).save(any());
	}

	@Test
	@DisplayName("empty fetched groups skip enrichment")
	void emptyFetchedSkipsEnrichment() {
		SsoProvisioningService service = serviceWith(mapping("azure"));
		UserAccount account = user();
		when(teams.findByOrgIdAndIdpIssuerAndIdpGroupId(any(), any(), any()))
				.thenReturn(Optional.empty());
		stubUnassignedPresent();
		when(teams.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		Optional<UserAccount> resolved = service.provisionWithBackfill(account, "azure",
				"https://login.example.com/tid-1", Map.of("groups", List.of("eng-a")),
				Map.of("tid", "tid-1"), Map.of(), null, null);

		assertThat(resolved).contains(account);
		verify(teams, times(1)).save(any(SsoTeam.class));
	}

	@Test
	@DisplayName("null fetched names skip enrichment")
	void nullFetchedNameSkipped() {
		SsoProvisioningService service = serviceWith(mapping("azure"));
		UserAccount account = user();
		when(teams.findByOrgIdAndIdpIssuerAndIdpGroupId(any(), any(), any()))
				.thenReturn(Optional.empty());
		stubUnassignedPresent();
		when(teams.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		Map<String, String> fetched = new HashMap<>();
		fetched.put("eng-a", null);

		Optional<UserAccount> resolved = service.provisionWithBackfill(account, "azure",
				"https://login.example.com/tid-1", Map.of(), Map.of("tid", "tid-1"), fetched,
				null, null);

		assertThat(resolved).contains(account);
		verify(teams, times(1)).save(any(SsoTeam.class));
	}

	@Test
	@DisplayName("successful syncs audit once")
	void successfulSyncAudits() {
		SsoProvisioningService service = serviceWith(mapping("azure"));
		UserAccount account = user();
		when(teams.findByOrgIdAndIdpIssuerAndIdpGroupId(any(), any(), any()))
				.thenReturn(Optional.empty());
		stubUnassignedPresent();
		when(teams.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		service.provision(account, "azure", "https://login.example.com/tid-1",
				attributes(List.of("eng-a"), List.of()), idToken("tid-1"), "10.0.0.3", "req-3");

		verify(audit).record(AuthAuditService.ACTION_SSO_TEAMS_SYNC,
				AuthAuditService.SEVERITY_INFO, "op", "/oauth2/callback",
				AuthAuditService.OUTCOME_SUCCESS, "10.0.0.3", "req-3");
	}
}
