package io.github.kxng0109.cacherelay.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * One SSO organization: the tenant boundary for team provisioning, resolved
 * from the registration's configured org slug (one org per IdP tenant by
 * default, multi-org per tenant via distinct registrations).
 */
@Entity
@Table(name = "sso_org")
@Getter
public class SsoOrg {

	@Id
	private UUID id;

	@Column(nullable = false, unique = true, length = 64)
	private String slug;

	@Column(name = "display_name", nullable = false, length = 128)
	private String displayName;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	/**
	 * No-argument constructor required by the JPA specification.
	 */
	protected SsoOrg() {
	}

	/**
	 * @param slug        stable org key, lowercase, never {@code null}
	 * @param displayName human name, never {@code null}
	 */
	public SsoOrg(String slug, String displayName) {
		this.id = UUID.randomUUID();
		this.slug = slug;
		this.displayName = displayName;
	}
}
