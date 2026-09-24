package io.github.kxng0109.cacherelay.auth;

import java.util.List;
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

	/**
	 * Lists every link of one local account.
	 *
	 * @param userId owning account, never {@code null}
	 * @return links in no guaranteed order
	 */
	List<SsoLink> findByUserId(UUID userId);

	/**
	 * Lists every link for one external subject across registrations.
	 *
	 * @param subject IdP subject, never {@code null}
	 * @return links in no guaranteed order
	 */
	List<SsoLink> findBySubject(String subject);
}
