package io.github.kxng0109.cacherelay.ledger;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for sampled routing decisions.
 */
public interface RoutingDecisionRepository extends JpaRepository<RoutingDecisionEntity, UUID> {
}
