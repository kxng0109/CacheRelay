package io.github.kxng0109.cacherelay.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * Local human account: username plus an optional BCrypt password hash.
 *
 * <p>SSO-only accounts carry a {@code null} password hash and authenticate exclusively
 * through linked {@code auth_sso_link} rows. Usernames are unique case-insensitively
 * (see {@code uq_auth_user_username}); lookups must use the case-insensitive repository
 * method. Email addresses are never stored raw — only a keyed hash for audit correlation.
 */
@Entity
@Table(name = "auth_user")
@Getter
public class UserAccount {

	@Id
	private UUID id;

	@Column(nullable = false, length = 255)
	private String username;

	@Column(name = "password_hash", length = 255)
	private String passwordHash;

	@Column(name = "email_hash", length = 128)
	private String emailHash;

	@Column(nullable = false)
	private boolean admin;

	@Column(nullable = false)
	private boolean disabled;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	/**
	 * No-argument constructor required by the JPA specification.
	 */
	protected UserAccount() {
	}

	/**
	 * Creates an account with a fresh identifier.
	 *
	 * @param username     login name, unique case-insensitively
	 * @param passwordHash BCrypt hash, or {@code null} for SSO-only accounts
	 * @param emailHash    keyed email hash, or {@code null} when unknown
	 * @param admin        whether the account holds administrative privilege
	 */
	public UserAccount(String username, String passwordHash, String emailHash, boolean admin) {
		this.id = UUID.randomUUID();
		this.username = username;
		this.passwordHash = passwordHash;
		this.emailHash = emailHash;
		this.admin = admin;
	}
}
