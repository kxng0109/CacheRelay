package io.github.kxng0109.cacherelay.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * One node in a refresh-token family: the presented opaque token is stored as a
 * SHA-256 hash (never plaintext). Rotation marks the presented row replaced and inserts
 * a successor in the same family; reuse of a replaced row revokes the whole family.
 * Mutation goes through atomic conditional updates on the repository, never read-then-write.
 */
@Entity
@Table(name = "auth_refresh_token")
@Getter
public class RefreshToken {

	@Id
	private UUID id;

	@Column(name = "family_id", nullable = false)
	private UUID familyId;

	@Column(name = "token_hash", nullable = false, length = 128, unique = true)
	private String tokenHash;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "absolute_expires_at", nullable = false)
	private Instant absoluteExpiresAt;

	@Column(name = "replaced_by")
	private UUID replacedBy;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	/**
	 * No-argument constructor required by the JPA specification.
	 */
	protected RefreshToken() {
	}

	/**
	 * Creates a live token row with a fresh identifier.
	 *
	 * @param familyId          rotation family shared with predecessors and successors
	 * @param tokenHash         SHA-256 hex of the opaque presented token
	 * @param userId            owning account
	 * @param expiresAt         idle expiry (sliding, refreshed on rotation)
	 * @param absoluteExpiresAt absolute session ceiling (fixed, never extended)
	 */
	public RefreshToken(UUID familyId, String tokenHash, UUID userId, Instant expiresAt,
			Instant absoluteExpiresAt) {
		this.id = UUID.randomUUID();
		this.familyId = familyId;
		this.tokenHash = tokenHash;
		this.userId = userId;
		this.expiresAt = expiresAt;
		this.absoluteExpiresAt = absoluteExpiresAt;
	}
}
