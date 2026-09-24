package io.github.kxng0109.cacherelay.auth.backfill;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.kxng0109.cacherelay.auth.AuthAuditService;
import io.github.kxng0109.cacherelay.auth.BackfillMode;
import io.github.kxng0109.cacherelay.auth.RefreshService;
import io.github.kxng0109.cacherelay.auth.SsoBackfillProperties;
import io.github.kxng0109.cacherelay.auth.SsoClaimProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SsoBackfillOrchestrator")
class SsoBackfillOrchestratorTest {

	private AuthAuditService audit;

	private EntraGraphBackfillClient entra;

	private OktaBackfillClient okta;

	private GoogleBackfillClient google;

	private GitHubBackfillClient github;

	@BeforeEach
	void setUp() {
		audit = mock(AuthAuditService.class);
		entra = mock(EntraGraphBackfillClient.class);
		okta = mock(OktaBackfillClient.class);
		google = mock(GoogleBackfillClient.class);
		github = mock(GitHubBackfillClient.class);
	}

	private SsoClaimProperties claims() {
		return new SsoClaimProperties(List.of(
				new SsoClaimProperties.RegistrationTeams("azure", "acme", "groups", "roles",
						"tid", List.of(), List.of()),
				new SsoClaimProperties.RegistrationTeams("okta", "acme", "groups", "roles",
						"", List.of(), List.of()),
				new SsoClaimProperties.RegistrationTeams("google", "acme", "groups", "roles",
						"", List.of(), List.of()),
				new SsoClaimProperties.RegistrationTeams("github", "acme", "groups", "roles",
						"", List.of(), List.of())));
	}

	private SsoBackfillProperties backfill() {
		return new SsoBackfillProperties(List.of(
				new SsoBackfillProperties.RegistrationBackfill("azure", BackfillMode.ENTRA_GRAPH,
						"tenant-1"),
				new SsoBackfillProperties.RegistrationBackfill("okta", BackfillMode.OKTA_API,
						"https://acme.okta.com"),
				new SsoBackfillProperties.RegistrationBackfill("google",
						BackfillMode.GOOGLE_DIRECTORY, "example.com"),
				new SsoBackfillProperties.RegistrationBackfill("github", BackfillMode.GITHUB_API,
						"acme-corp")));
	}

	private SsoBackfillOrchestrator orchestrator() {
		return new SsoBackfillOrchestrator(backfill(), claims(), audit, entra, okta, google,
				github);
	}

	private BackfillRequest request(boolean groupsPresent, boolean overage) {
		Map<String, Object> attributes = new HashMap<>();
		if (groupsPresent) {
			attributes.put("groups", List.of("eng-a"));
		}
		if (overage) {
			attributes.put("_claim_names", Map.of("groups", "src1"));
		}
		return new BackfillRequest("user-key-1", "op@example.com", "op", "user-token",
				attributes);
	}

	private BackfillRequest requestWith(String userKey, String email, String login) {
		return new BackfillRequest(userKey, email, login, null,
				Map.of("_claim_names", Map.of("groups", "src1")));
	}

	@Test
	@DisplayName("group presence follows the mapped claim names")
	void customClaimNames() {
		SsoClaimProperties custom = new SsoClaimProperties(List.of(
				new SsoClaimProperties.RegistrationTeams("azure", "acme", "entGroups",
						"entRoles", "tid", List.of(), List.of())));
		SsoBackfillOrchestrator orchestrator = new SsoBackfillOrchestrator(backfill(), custom,
				audit, entra, okta, google, github);
		Map<String, Object> attributes = new HashMap<>();
		attributes.put("groups", List.of("eng-a"));
		attributes.put("_claim_names", Map.of("groups", "src1"));
		when(entra.fetch("user-key-1", "tenant-1")).thenReturn(Optional.of(
				new BackfillResult(Map.of("group-1", "Engineering"), false)));

		BackfillOutcome outcome = orchestrator.backfill("azure", new BackfillRequest(
				"user-key-1", "op@example.com", "op", null, attributes), null, null);

		assertThat(outcome.status()).isEqualTo(BackfillStatus.SUCCEEDED);
		verify(entra, times(1)).fetch("user-key-1", "tenant-1");
	}

	@Test
	@DisplayName("actor falls back through email to login to unknown")
	void actorFallbacks() {
		when(entra.fetch("user-key-1", "tenant-1")).thenReturn(Optional.empty());

		SsoBackfillOrchestrator orchestrator = orchestrator();
		BackfillRequest noKey = requestWith(null, "op@example.com", "op");
		assertThat(orchestrator.backfill("azure", noKey, null, null).status())
				.isEqualTo(BackfillStatus.FAILED);
		verify(audit).record(eq(AuthAuditService.ACTION_SSO_BACKFILL), any(),
				eq("sso:" + RefreshService.sha256Hex("op@example.com")),
				any(), any(), any(), any());

		BackfillRequest loginOnly = requestWith(null, null, "op");
		assertThat(orchestrator.backfill("azure", loginOnly, null, null).status())
				.isEqualTo(BackfillStatus.FAILED);

		BackfillRequest anonymous = requestWith(null, null, null);
		assertThat(orchestrator.backfill("azure", anonymous, null, null).status())
				.isEqualTo(BackfillStatus.FAILED);
		verify(audit).record(eq(AuthAuditService.ACTION_SSO_BACKFILL), any(), eq("sso:unknown"),
				any(), any(), any(), any());
	}

	@Test
	@DisplayName("NONE entries skip without fetching")
	void noneModeSkips() {
		SsoBackfillProperties noneProps = new SsoBackfillProperties(List.of(
				new SsoBackfillProperties.RegistrationBackfill("azure", BackfillMode.NONE,
						"tenant-1")));
		SsoBackfillOrchestrator orchestrator = new SsoBackfillOrchestrator(noneProps, claims(),
				audit, entra, okta, google, github);

		BackfillOutcome outcome = orchestrator.backfill("azure", request(false, true), null, null);

		assertThat(outcome.status()).isEqualTo(BackfillStatus.SKIPPED);
		verify(entra, never()).fetch(any(), any());
		verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("entries without claim mappings skip silently")
	void missingClaimMappingSkips() {
		SsoBackfillOrchestrator orchestrator = new SsoBackfillOrchestrator(backfill(),
				new SsoClaimProperties(List.of()), audit, entra, okta, google, github);

		BackfillOutcome outcome = orchestrator.backfill("azure", request(false, true), null,
				null);

		assertThat(outcome.status()).isEqualTo(BackfillStatus.SKIPPED);
		verify(entra, never()).fetch(any(), any());
		verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("roles-only claims count as group presence")
	void rolesOnlySkips() {
		Map<String, Object> attributes = new HashMap<>();
		attributes.put("roles", List.of("eng-a"));
		attributes.put("_claim_names", Map.of("groups", "src1"));
		BackfillRequest rolesOnly = new BackfillRequest("user-key-1", "op@example.com", "op",
				"user-token", attributes);

		BackfillOutcome outcome = orchestrator().backfill("azure", rolesOnly, null, null);

		assertThat(outcome.status()).isEqualTo(BackfillStatus.SKIPPED);
		verify(entra, never()).fetch(any(), any());
	}

	@Test
	@DisplayName("overage markers without groups still trigger")
	void markerWithoutGroupsKeyTriggers() {
		Map<String, Object> attributes = new HashMap<>();
		attributes.put("_claim_names", Map.of("other", "src9"));
		attributes.put("hasgroups", Boolean.TRUE);
		when(entra.fetch("user-key-1", "tenant-1")).thenReturn(Optional.of(
				new BackfillResult(Map.of(), false)));

		BackfillOutcome outcome = orchestrator().backfill("azure", new BackfillRequest(
				"user-key-1", "op@example.com", "op", "user-token", attributes), null, null);

		assertThat(outcome.status()).isEqualTo(BackfillStatus.SUCCEEDED);
		verify(entra, times(1)).fetch("user-key-1", "tenant-1");
	}

	@Test
	@DisplayName("revalidate fetches through service modes and declines the rest")
	void revalidateDispatches() {
		when(entra.fetch("sub-1", "tenant-1")).thenReturn(Optional.of(
				new BackfillResult(Map.of("group-1", "Engineering"), false)));
		when(okta.fetch("https://acme.okta.com", "sub-2")).thenReturn(Optional.of(
				new BackfillResult(Map.of("00g1", "Engineering"), false)));
		when(google.fetchById("sub-3", "example.com")).thenReturn(Optional.of(
				new BackfillResult(Map.of("01a", "Engineering"), false)));

		assertThat(orchestrator().revalidate("azure", "sub-1")).isPresent();
		assertThat(orchestrator().revalidate("okta", "sub-2")).isPresent();
		assertThat(orchestrator().revalidate("google", "sub-3")).isPresent();
		assertThat(orchestrator().revalidate("github", "sub-4")).isEmpty();
		assertThat(orchestrator().revalidate("generic", "sub-5")).isEmpty();

		SsoBackfillProperties noneProps = new SsoBackfillProperties(List.of(
				new SsoBackfillProperties.RegistrationBackfill("azure", BackfillMode.NONE,
						"tenant-1")));
		SsoBackfillOrchestrator orchestrator = new SsoBackfillOrchestrator(noneProps, claims(),
				audit, entra, okta, google, github);
		assertThat(orchestrator.revalidate("azure", "sub-1")).isEmpty();
	}

	@Test
	@DisplayName("unconfigured registrations skip silently")
	void unconfiguredSkips() {
		BackfillOutcome outcome = orchestrator().backfill("generic", request(false, false), null,
				null);

		assertThat(outcome.status()).isEqualTo(BackfillStatus.SKIPPED);
		verify(audit, never()).record(any(), any(), any(), any(), any(), any(), any());
		verify(entra, never()).fetch(any(), any());
	}

	@Test
	@DisplayName("token-complete logins skip fetching")
	void tokenCompleteSkips() {
		BackfillOutcome outcome = orchestrator().backfill("azure", request(true, false), null,
				null);

		assertThat(outcome.status()).isEqualTo(BackfillStatus.SKIPPED);
		verify(entra, never()).fetch(any(), any());
	}

	@Test
	@DisplayName("absent groups without overage skip fetching")
	void absentWithoutOverageSkips() {
		BackfillOutcome outcome = orchestrator().backfill("azure", request(false, false), null,
				null);

		assertThat(outcome.status()).isEqualTo(BackfillStatus.SKIPPED);
		verify(entra, never()).fetch(any(), any());
	}

	@Test
	@DisplayName("overage fetches merge through as success")
	void overageSucceeds() {
		when(entra.fetch("user-key-1", "tenant-1")).thenReturn(Optional.of(
				new BackfillResult(Map.of("group-1", "Engineering"), false)));

		BackfillOutcome outcome = orchestrator().backfill("azure", request(false, true), "ip-1",
				"req-1");

		assertThat(outcome.status()).isEqualTo(BackfillStatus.SUCCEEDED);
		assertThat(outcome.result().groups()).containsExactly(Map.entry("group-1", "Engineering"));
	}

	@Test
	@DisplayName("failed fetches deny with an audit record")
	void failedFetchDenies() {
		when(entra.fetch("user-key-1", "tenant-1")).thenReturn(Optional.empty());

		BackfillOutcome outcome = orchestrator().backfill("azure", request(false, true), "ip-1",
				"req-1");

		assertThat(outcome.status()).isEqualTo(BackfillStatus.FAILED);
		verify(audit).record(eq(AuthAuditService.ACTION_SSO_BACKFILL), any(), any(), any(),
				any(), any(), any());
	}

	@Test
	@DisplayName("disabled verdicts succeed with the flag and an audit record")
	void disabledSucceedsFlagged() {
		when(entra.fetch("user-key-1", "tenant-1")).thenReturn(
				Optional.of(BackfillResult.forDisabledAccount()));

		BackfillOutcome outcome = orchestrator().backfill("azure", request(false, true), "ip-1",
				"req-1");

		assertThat(outcome.status()).isEqualTo(BackfillStatus.SUCCEEDED);
		assertThat(outcome.result().disabled()).isTrue();
		verify(audit).record(eq(AuthAuditService.ACTION_SSO_BACKFILL),
				eq(AuthAuditService.SEVERITY_WARN), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("okta falls back only when claims lack groups")
	void oktaFallback() {
		when(okta.fetch("https://acme.okta.com", "user-key-1")).thenReturn(Optional.of(
				new BackfillResult(Map.of("00g1", "Engineering"), false)));

		assertThat(orchestrator().backfill("okta", request(true, false), null, null).status())
				.isEqualTo(BackfillStatus.SKIPPED);
		verify(okta, never()).fetch(any(), any());
		assertThat(orchestrator().backfill("okta", request(false, false), null, null).status())
				.isEqualTo(BackfillStatus.SUCCEEDED);
	}

	@Test
	@DisplayName("google and github always fetch")
	void googleAndGithubAlwaysFetch() {
		when(google.fetch("op@example.com", "example.com")).thenReturn(Optional.of(
				new BackfillResult(Map.of("01a", "Engineering"), false)));
		when(github.fetch("op", "acme-corp", "user-token")).thenReturn(Optional.of(
				new BackfillResult(Map.of("eng", "Engineering"), false)));

		assertThat(orchestrator().backfill("google", request(false, false), null, null).status())
				.isEqualTo(BackfillStatus.SUCCEEDED);
		assertThat(orchestrator().backfill("github", request(false, false), null, null).status())
				.isEqualTo(BackfillStatus.SUCCEEDED);
	}

	@Test
	@DisplayName("google failures deny with an audit record")
	void googleFailureDenies() {
		when(google.fetch("op@example.com", "example.com")).thenReturn(Optional.empty());

		BackfillOutcome outcome = orchestrator().backfill("google", request(false, false), "ip-1",
				"req-1");

		assertThat(outcome.status()).isEqualTo(BackfillStatus.FAILED);
		verify(audit).record(eq(AuthAuditService.ACTION_SSO_BACKFILL), any(), any(), any(),
				any(), any(), any());
	}

	@Test
	@DisplayName("github failures deny with an audit record")
	void githubFailureDenies() {
		when(github.fetch("op", "acme-corp", "user-token")).thenReturn(Optional.empty());

		BackfillOutcome outcome = orchestrator().backfill("github", request(false, false), "ip-1",
				"req-1");

		assertThat(outcome.status()).isEqualTo(BackfillStatus.FAILED);
		verify(audit).record(eq(AuthAuditService.ACTION_SSO_BACKFILL), any(), any(), any(),
				any(), any(), any());
	}

	@Test
	@DisplayName("okta failures deny with an audit record")
	void oktaFailureDenies() {
		when(okta.fetch("https://acme.okta.com", "user-key-1")).thenReturn(Optional.empty());

		BackfillOutcome outcome = orchestrator().backfill("okta", request(false, false), "ip-1",
				"req-1");

		assertThat(outcome.status()).isEqualTo(BackfillStatus.FAILED);
		verify(audit).record(eq(AuthAuditService.ACTION_SSO_BACKFILL), any(), any(), any(),
				any(), any(), any());
	}
}
