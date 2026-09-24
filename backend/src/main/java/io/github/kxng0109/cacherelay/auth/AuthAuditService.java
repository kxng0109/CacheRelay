package io.github.kxng0109.cacherelay.auth;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Severity-filterable authentication audit log. Every mutating auth event (logins, refreshes,
 * reuse detections, invites, consumption, lockouts) lands here with a pseudonymized actor,
 * never raw PII. Operators filter by severity or action; the janitor enforces retention.
 */
@Service
public class AuthAuditService {

	/** Successful or attempted local login. */
	public static final String ACTION_LOCAL_LOGIN = "LOCAL_LOGIN";
	/** SSO login via an external provider. */
	public static final String ACTION_SSO_LOGIN = "SSO_LOGIN";
	/** Refresh rotation (success or reuse). */
	public static final String ACTION_REFRESH = "REFRESH_TOKEN_ROTATED";
	/** Reuse of a rotated token (whole family revoked). */
	public static final String ACTION_REUSE_DETECTED = "REUSE_DETECTED";
	/** Invite created. */
	public static final String ACTION_INVITE_CREATED = "INVITE_CREATED";
	/** Invite redeemed. */
	public static final String ACTION_INVITE_CONSUMED = "INVITE_CONSUMED";
	/** First-admin bootstrap consumed. */
	public static final String ACTION_BOOTSTRAP_CONSUMED = "BOOTSTRAP_CONSUMED";
	/** Account locked after repeated failures. */
	public static final String ACTION_LOCKOUT = "ACCOUNT_LOCKED";
	/** Admin mutation (key/circuit/budget/notification change). */
	public static final String ACTION_ADMIN_MUTATION = "ADMIN_MUTATION";
	/** Admin read of another user's usage dashboard (drill-down). */
	public static final String ACTION_DASHBOARD_VIEW = "DASHBOARD_VIEW";
	/** SSO team provisioning outcome (success or tenant denial). */
	public static final String ACTION_SSO_TEAMS_SYNC = "SSO_TEAMS_SYNC";
	/** First-login backfill attempt outcome (failure or IdP-disabled). */
	public static final String ACTION_SSO_BACKFILL = "SSO_BACKFILL";
	/** Sweep revocation of an IdP-disabled account. */
	public static final String ACTION_SSO_REVOKE = "SSO_REVOKE";
	/** Webhook authentication outcome (invalid signatures). */
	public static final String ACTION_WEBHOOK_AUTH = "WEBHOOK_AUTH";
	/** Admin read or erasure of captured usage content. */
	public static final String ACTION_CAPTURE_READ = "CAPTURE_READ";

	/** Informational events. */
	public static final String SEVERITY_INFO = "INFO";
	/** Suspicious but non-compromising events. */
	public static final String SEVERITY_WARN = "WARN";
	/** Failures demanding attention. */
	public static final String SEVERITY_ERROR = "ERROR";
	/** Active compromise indicators (reuse, lockout storms). */
	public static final String SEVERITY_CRITICAL = "CRITICAL";

	/** Success outcome. */
	public static final String OUTCOME_SUCCESS = "SUCCESS";
	/** Failure outcome. */
	public static final String OUTCOME_FAILURE = "FAILURE";

	private final AuthAuditRepository repository;
	private final byte[] pepper;

	/**
	 * Creates the service.
	 *
	 * @param repository audit persistence
	 * @param properties auth tuning surface (secret doubles as HMAC pepper)
	 */
	public AuthAuditService(AuthAuditRepository repository, AuthProperties properties) {
		this.repository = repository;
		this.pepper = JwtService.resolveSecretBytes(properties.jwtSecret());
	}

	/**
	 * Records one audit event, pseudonymizing actor and network identifiers.
	 *
	 * @param action       event type
	 * @param severity     {@code INFO}, {@code WARN}, {@code ERROR}, or {@code CRITICAL}
	 * @param actorRaw     raw actor identifier (username, email, or key id)
	 * @param resourcePath request path, or {@code null}
	 * @param outcome      {@code SUCCESS} or {@code FAILURE}
	 * @param ipRaw        raw remote address, or {@code null}
	 * @param requestId    correlation id, or {@code null}
	 */
	@Transactional
	public void record(String action, String severity, String actorRaw, String resourcePath,
			String outcome, String ipRaw, String requestId) {
		repository.save(new AuthAuditEvent(
				JwtService.pseudonymize(actorRaw, pepper),
				action,
				severity,
				resourcePath,
				outcome,
				ipRaw != null ? JwtService.pseudonymize(ipRaw, pepper) : null,
				requestId));
	}

	/**
	 * Pseudonymizes an identifier the same way records do (for lockout counting).
	 *
	 * @param raw raw identifier
	 * @return hex pseudonym
	 */
	public String pseudonym(String raw) {
		return JwtService.pseudonymize(raw, pepper);
	}

	/**
	 * Counts recent failures of one action for one actor (lockout decisions).
	 *
	 * @param actorHash pseudonymized actor
	 * @param action    event type
	 * @param since     window start
	 * @return failure count inside the window
	 */
	@Transactional(readOnly = true)
	public long recentFailures(String actorHash, String action, Instant since) {
		return repository.countByActorHashAndActionAndOutcomeAndOccurredAtAfter(
				actorHash, action, OUTCOME_FAILURE, since);
	}

	/**
	 * Lists events of one severity, newest first (operator filtering).
	 *
	 * @param severity severity to filter
	 * @return matching events newest first
	 */
	@Transactional(readOnly = true)
	public List<AuthAuditEvent> bySeverity(String severity) {
		return repository.findBySeverityOrderByOccurredAtDesc(severity);
	}

	/**
	 * Lists events of one action type, newest first (operator filtering).
	 *
	 * @param action action to filter
	 * @return matching events newest first
	 */
	@Transactional(readOnly = true)
	public List<AuthAuditEvent> byAction(String action) {
		return repository.findByActionOrderByOccurredAtDesc(action);
	}
}
