package io.github.kxng0109.cacherelay.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * One authentication audit event: who (pseudonymized), what, how severe, with what
 * outcome. Actor and network identifiers are keyed hashes, never raw values, so the
 * ledger identifies accounts without storing PII. Query by severity or action for
 * operator filtering; retention janitor deletes past the configured horizon.
 */
@Entity
@Table(name = "auth_audit")
@Getter
public class AuthAuditEvent {

	@Id
	private UUID id;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt = Instant.now();

	@Column(name = "actor_hash", nullable = false, length = 128)
	private String actorHash;

	@Column(nullable = false, length = 64)
	private String action;

	@Column(nullable = false, length = 16)
	private String severity;

	@Column(name = "resource_path", length = 512)
	private String resourcePath;

	@Column(nullable = false, length = 16)
	private String outcome;

	@Column(name = "ip_hash", length = 128)
	private String ipHash;

	@Column(name = "request_id", length = 64)
	private String requestId;

	/**
	 * No-argument constructor required by the JPA specification.
	 */
	protected AuthAuditEvent() {
	}

	/**
	 * Creates an event with a fresh identifier and the current timestamp.
	 *
	 * @param actorHash    keyed hash of the actor (username, email, or key id)
	 * @param action       event type, e.g. {@code ADMIN_LOGIN}
	 * @param severity     {@code INFO}, {@code WARN}, {@code ERROR}, or {@code CRITICAL}
	 * @param resourcePath request path, or {@code null}
	 * @param outcome      {@code SUCCESS} or {@code FAILURE}
	 * @param ipHash       keyed hash of the remote address, or {@code null}
	 * @param requestId    correlation id, or {@code null}
	 */
	public AuthAuditEvent(String actorHash, String action, String severity, String resourcePath,
			String outcome, String ipHash, String requestId) {
		this.id = UUID.randomUUID();
		this.actorHash = actorHash;
		this.action = action;
		this.severity = severity;
		this.resourcePath = resourcePath;
		this.outcome = outcome;
		this.ipHash = ipHash;
		this.requestId = requestId;
	}
}
