package io.github.kxng0109.cacherelay.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * Single-use invitation: the presented token is stored as a SHA-256 hash and consumed
 * atomically exactly once, after which the invite endpoint answers 410 Gone. The invite
 * optionally pre-authorizes admin privilege for the created account.
 */
@Entity
@Table(name = "auth_invite")
@Getter
public class InviteToken {

	@Id
	private UUID id;

	@Column(name = "token_hash", nullable = false, length = 128, unique = true)
	private String tokenHash;

	@Column(name = "email_hash", length = 128)
	private String emailHash;

	@Column(nullable = false)
	private boolean admin;

	@Column(name = "created_by")
	private UUID createdBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "consumed_at")
	private Instant consumedAt;

	@Column(name = "consumed_by")
	private UUID consumedBy;

	/**
	 * No-argument constructor required by the JPA specification.
	 */
	protected InviteToken() {
	}

	/**
	 * Creates an invite with a fresh identifier.
	 *
	 * @param tokenHash SHA-256 hex of the opaque presented token
	 * @param emailHash keyed hash of the invited address, or {@code null}
	 * @param admin     whether redeeming creates an admin account
	 * @param createdBy inviting account, or {@code null} for bootstrap invites
	 * @param expiresAt invite expiry
	 */
	public InviteToken(String tokenHash, String emailHash, boolean admin, UUID createdBy,
			Instant expiresAt) {
		this.id = UUID.randomUUID();
		this.tokenHash = tokenHash;
		this.emailHash = emailHash;
		this.admin = admin;
		this.createdBy = createdBy;
		this.expiresAt = expiresAt;
	}
}
