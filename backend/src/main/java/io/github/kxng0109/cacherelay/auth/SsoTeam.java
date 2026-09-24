package io.github.kxng0109.cacherelay.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * One IdP-bound team: a stable {@code (issuer, group id)} binding inside an
 * org. Teams materialize on first login when a presented group matches a
 * configured pattern; renames converge on display name while identity never
 * moves. The per-org unassigned team carries the {@link #UNASSIGNED_GROUP_ID}
 * marker and holds accounts with no mapped team under least privilege.
 */
@Entity
@Table(name = "sso_team")
@Getter
public class SsoTeam {

	/**
	 * Group marker for the per-org unassigned team.
	 */
	public static final String UNASSIGNED_GROUP_ID = "__unassigned__";

	@Id
	private UUID id;

	@Column(name = "org_id", nullable = false)
	private UUID orgId;

	@Column(name = "idp_issuer", nullable = false, length = 256)
	private String idpIssuer;

	@Column(name = "idp_group_id", nullable = false, length = 256)
	private String idpGroupId;

	@Column(nullable = false, length = 128)
	private String name;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	/**
	 * No-argument constructor required by the JPA specification.
	 */
	protected SsoTeam() {
	}

	/**
	 * @param orgId      owning org, never {@code null}
	 * @param idpIssuer  IdP issuer the binding came from, never {@code null}
	 * @param idpGroupId stable IdP group id, never {@code null}
	 * @param name       display name, never {@code null}
	 */
	public SsoTeam(UUID orgId, String idpIssuer, String idpGroupId, String name) {
		this.id = UUID.randomUUID();
		this.orgId = orgId;
		this.idpIssuer = idpIssuer;
		this.idpGroupId = idpGroupId;
		this.name = name;
	}

	/**
	 * Refreshes the display name, keeping identity stable across IdP renames.
	 *
	 * @param name new display name, never {@code null}
	 */
	public void rename(String name) {
		this.name = name;
		this.updatedAt = Instant.now();
	}
}
