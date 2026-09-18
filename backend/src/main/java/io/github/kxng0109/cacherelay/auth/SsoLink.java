package io.github.kxng0109.cacherelay.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * Links an external identity-provider subject to a local {@link UserAccount}.
 *
 * <p>Identity is the {@code (issuer, subject)} pair: stable, IdP-assigned, and never
 * derived from mutable claims such as email. The pair is unique, so one external
 * identity maps to exactly one local account.
 */
@Entity
@Table(name = "auth_sso_link")
@Getter
public class SsoLink {

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(nullable = false)
	private String issuer;

	@Column(nullable = false)
	private String subject;

	@Column(name = "registration_id", nullable = false, length = 64)
	private String registrationId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	/**
	 * No-argument constructor required by the JPA specification.
	 */
	protected SsoLink() {
	}

	/**
	 * Creates a link with a fresh identifier.
	 *
	 * @param userId         owning local account
	 * @param issuer         IdP issuer ({@code iss} claim)
	 * @param subject        IdP subject ({@code sub} claim)
	 * @param registrationId Spring {@code oauth2.client} registration id
	 */
	public SsoLink(UUID userId, String issuer, String subject, String registrationId) {
		this.id = UUID.randomUUID();
		this.userId = userId;
		this.issuer = issuer;
		this.subject = subject;
		this.registrationId = registrationId;
	}
}
