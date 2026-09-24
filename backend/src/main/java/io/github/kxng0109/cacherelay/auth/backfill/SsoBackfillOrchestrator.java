package io.github.kxng0109.cacherelay.auth.backfill;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;

import io.github.kxng0109.cacherelay.auth.AuthAuditService;
import io.github.kxng0109.cacherelay.auth.BackfillMode;
import io.github.kxng0109.cacherelay.auth.SsoBackfillConfig;
import io.github.kxng0109.cacherelay.auth.SsoBackfillProperties;
import io.github.kxng0109.cacherelay.auth.SsoClaimProperties;
import io.github.kxng0109.cacherelay.auth.RefreshService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * First-login backfill dispatcher: decides per registration whether token
 * claims suffice and, when they cannot carry membership, runs the
 * registration's IdP client.
 *
 * <p>Token-complete logins skip fetching and must never deny. Attempted
 * fetches that fail, and IdP-disabled accounts, deny the login with an audit
 * record keyed by a subject hash (never raw identifiers). Clients are built
 * once from the startup-validated environment; per-call scope comes from the
 * backfill entry.</p>
 */
@Service
public class SsoBackfillOrchestrator {

	private static final String CALLBACK_PATH = "/oauth2/callback";

	static final String DEFAULT_ENTRA_LOGIN_BASE = "https://login.microsoftonline.com";

	static final String DEFAULT_ENTRA_GRAPH_BASE = "https://graph.microsoft.com";

	private final SsoBackfillProperties backfillProperties;

	private final SsoClaimProperties claimProperties;

	private final AuthAuditService audit;

	private final EntraGraphBackfillClient entra;

	private final OktaBackfillClient okta;

	private final GoogleBackfillClient google;

	private final GitHubBackfillClient github;

	/**
	 * Creates the orchestrator with production clients from the environment.
	 *
	 * @param backfillProperties per-registration backfill entries, never {@code null}
	 * @param claimProperties    per-registration claim mappings, never {@code null}
	 * @param environment        runtime environment for credentials, never {@code null}
	 * @param audit              audit log, never {@code null}
	 * @param clock              clock for token expiry, never {@code null}
	 */
	@Autowired
	public SsoBackfillOrchestrator(SsoBackfillProperties backfillProperties,
			SsoClaimProperties claimProperties, Environment environment, AuthAuditService audit,
			Clock clock) {
		this(backfillProperties, claimProperties, audit,
				new EntraGraphBackfillClient(
						environment.getProperty("SSO_AZURE_CLIENT_ID", ""),
						environment.getProperty("SSO_AZURE_CLIENT_SECRET", ""),
						environment.getProperty("GATEWAY_SSO_ENTRA_LOGIN_BASE",
								DEFAULT_ENTRA_LOGIN_BASE),
						environment.getProperty("GATEWAY_SSO_ENTRA_GRAPH_BASE",
								DEFAULT_ENTRA_GRAPH_BASE),
						clock),
				new OktaBackfillClient(
						environment.getProperty("SSO_OKTA_CLIENT_ID", ""),
						environment.getProperty(SsoBackfillConfig.OKTA_PRIVATE_KEY_PROPERTY, ""),
						environment.getProperty(SsoBackfillConfig.OKTA_KEY_ID_PROPERTY, ""),
						clock),
				new GoogleBackfillClient(
						environment.getProperty(SsoBackfillConfig.GOOGLE_SA_JSON_PROPERTY, ""),
						environment.getProperty(SsoBackfillConfig.GOOGLE_ADMIN_SUBJECT_PROPERTY,
								""),
						clock),
				new GitHubBackfillClient());
	}

	/**
	 * Creates the orchestrator with explicit clients (tests).
	 */
	SsoBackfillOrchestrator(SsoBackfillProperties backfillProperties,
			SsoClaimProperties claimProperties, AuthAuditService audit,
			EntraGraphBackfillClient entra, OktaBackfillClient okta, GoogleBackfillClient google,
			GitHubBackfillClient github) {
		this.backfillProperties = backfillProperties;
		this.claimProperties = claimProperties;
		this.audit = audit;
		this.entra = entra;
		this.okta = okta;
		this.google = google;
		this.github = github;
	}

	/**
	 * Runs the registration's backfill for one first login.
	 *
	 * @param registrationId Spring registration id, never {@code null}
	 * @param request        backfill inputs, never {@code null}
	 * @param ip             remote address, or {@code null}
	 * @param requestId      correlation id, or {@code null}
	 * @return skipped, succeeded, or failed verdict, never {@code null}
	 */
	public BackfillOutcome backfill(String registrationId, BackfillRequest request, String ip,
			String requestId) {
		Optional<SsoBackfillProperties.RegistrationBackfill> entry =
				backfillProperties.forRegistration(registrationId);
		Optional<SsoClaimProperties.RegistrationTeams> mapping =
				claimProperties.forRegistration(registrationId);
		if (entry.isEmpty() || mapping.isEmpty()) {
			return BackfillOutcome.skipped();
		}
		SsoBackfillProperties.RegistrationBackfill wiring = entry.get();
		boolean groupsPresent = !BackfillRequest.extractStrings(
				request.attributes().get(mapping.get().groupsClaim())).isEmpty()
				|| !BackfillRequest.extractStrings(
						request.attributes().get(mapping.get().rolesClaim())).isEmpty();
		boolean overagePresent = overageMarked(request.attributes());
		Attempt attempt = switch (wiring.mode()) {
			case ENTRA_GRAPH -> !groupsPresent && overagePresent
					? new Attempt(true, entra.fetch(request.userKey(), wiring.apiScope()))
					: new Attempt(false, Optional.empty());
			case OKTA_API -> !groupsPresent
					? new Attempt(true, okta.fetch(wiring.apiScope(), request.userKey()))
					: new Attempt(false, Optional.empty());
			case GOOGLE_DIRECTORY -> new Attempt(true,
					google.fetch(request.email(), wiring.apiScope()));
			case GITHUB_API -> new Attempt(true,
					github.fetch(request.login(), wiring.apiScope(), request.userToken()));
			case NONE -> new Attempt(false, Optional.empty());
		};
		if (!attempt.attempted()) {
			return BackfillOutcome.skipped();
		}
		if (attempt.fetched().isEmpty()) {
			audit.record(AuthAuditService.ACTION_SSO_BACKFILL, AuthAuditService.SEVERITY_WARN,
					actor(request), CALLBACK_PATH, AuthAuditService.OUTCOME_FAILURE, ip, requestId);
			return BackfillOutcome.failed();
		}
		if (attempt.fetched().get().disabled()) {
			audit.record(AuthAuditService.ACTION_SSO_BACKFILL, AuthAuditService.SEVERITY_WARN,
					actor(request), CALLBACK_PATH, AuthAuditService.OUTCOME_FAILURE, ip, requestId);
		}
		return BackfillOutcome.succeeded(attempt.fetched().get());
	}

	/**
	 * Revalidates one known user for the sweep: always fetches through the
	 * registration's service-credential mode. GitHub has no service
	 * credential for user reads, so it always declines (callers skip it
	 * rather than revoking on no signal).
	 *
	 * @param registrationId Spring registration id, never {@code null}
	 * @param subject        IdP subject key, never {@code null}
	 * @return groups plus disabled flag, or empty on any failure or skip
	 */
	public Optional<BackfillResult> revalidate(String registrationId, String subject) {
		Optional<SsoBackfillProperties.RegistrationBackfill> entry =
				backfillProperties.forRegistration(registrationId);
		if (entry.isEmpty()) {
			return Optional.empty();
		}
		return switch (entry.get().mode()) {
			case ENTRA_GRAPH -> entra.fetch(subject, entry.get().apiScope());
			case OKTA_API -> okta.fetch(entry.get().apiScope(), subject);
			case GOOGLE_DIRECTORY -> google.fetchById(subject, entry.get().apiScope());
			case GITHUB_API, NONE -> Optional.empty();
		};
	}
	private String actor(BackfillRequest request) {
		String subject = request.userKey() != null ? request.userKey()
				: request.email() != null ? request.email() : request.login();
		if (subject == null) {
			return "sso:unknown";
		}
		return "sso:" + RefreshService.sha256Hex(subject);
	}

	private boolean overageMarked(Map<String, Object> attributes) {
		Object names = attributes.get("_claim_names");
		if (names instanceof Map<?, ?> markers && markers.containsKey("groups")) {
			return true;
		}
		return Boolean.TRUE.equals(attributes.get("hasgroups"));
	}

	private record Attempt(boolean attempted, Optional<BackfillResult> fetched) {
	}
}
