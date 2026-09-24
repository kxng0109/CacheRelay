package io.github.kxng0109.cacherelay.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link SsoOrg}.
 */
public interface SsoOrgRepository extends JpaRepository<SsoOrg, UUID> {

	/**
	 * Finds the org for a configured slug.
	 *
	 * @param slug org slug, never {@code null}
	 * @return the org when present
	 */
	Optional<SsoOrg> findBySlug(String slug);
}
