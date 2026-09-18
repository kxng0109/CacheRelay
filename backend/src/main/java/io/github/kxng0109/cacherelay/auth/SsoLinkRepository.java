package io.github.kxng0109.cacherelay.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link SsoLink}.
 */
public interface SsoLinkRepository extends JpaRepository<SsoLink, UUID> {

	/**
	 * Finds the link for an external identity.
	 *
	 * @param issuer  IdP issuer ({@code iss} claim)
	 * @param subject IdP subject ({@code sub} claim)
	 * @return the link, or empty
	 */
	Optional<SsoLink> findByIssuerAndSubject(String issuer, String subject);
}
