package io.github.kxng0109.cacherelay.model;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link ModelAliasDefinition}, keyed by model name.
 * Name uniqueness is enforced by the primary key; callers translate
 * integrity violations into HTTP 409 Conflict.
 */
public interface ModelAliasRepository extends JpaRepository<ModelAliasDefinition, String> {
}
