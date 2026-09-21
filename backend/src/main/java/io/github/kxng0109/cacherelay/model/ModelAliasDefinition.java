package io.github.kxng0109.cacherelay.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * Admin-managed model alias: a named routing plan (provider chain plus
 * failover strategy) created at runtime through the admin API and overlaid
 * onto the file-bound aliases. The chain is stored as a JSON array of
 * provider steps; file-bound aliases from configuration always win on name
 * conflict and can never be shadowed by a row here.
 */
@Entity
@Table(name = "model_alias")
@Getter
public class ModelAliasDefinition {

	@Id
	@Column(name = "name", nullable = false, length = 64)
	private String name;

	@Column(name = "chain_json", nullable = false, columnDefinition = "TEXT")
	private String chainJson;

	@Column(name = "strategy", nullable = false, length = 16)
	private String strategy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	/**
	 * No-argument constructor required by the JPA specification.
	 */
	protected ModelAliasDefinition() {
	}

	/**
	 * Creates a model alias definition with fresh timestamps.
	 *
	 * @param name      client facing model name (primary key)
	 * @param chainJson provider chain as a JSON array, never stored raw
	 * @param strategy  failover strategy name
	 */
	public ModelAliasDefinition(String name, String chainJson, String strategy) {
		this.name = name;
		this.chainJson = chainJson;
		this.strategy = strategy;
	}

	/**
	 * Replaces the routing plan and refreshes the update timestamp.
	 *
	 * @param chainJson provider chain as a JSON array
	 * @param strategy  failover strategy name
	 */
	public void replacePlan(String chainJson, String strategy) {
		this.chainJson = chainJson;
		this.strategy = strategy;
		this.updatedAt = Instant.now();
	}
}
