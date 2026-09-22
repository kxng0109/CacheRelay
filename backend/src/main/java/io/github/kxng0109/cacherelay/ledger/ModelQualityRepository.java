package io.github.kxng0109.cacherelay.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for admin-curated model quality tiers.
 */
public interface ModelQualityRepository extends JpaRepository<ModelQualityEntity, String> {
}
