package io.github.kxng0109.cacherelay.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Links external IdP identities to local accounts. Identity is the {@code (issuer,
 * subject)} pair — never email, which is mutable and not universally verified. Unknown
 * identities get a fresh non-admin shadow account; known ones return linked, with profile
 * fields refreshed from claims. Re-linking an existing local account by email is never
 * automatic (merge attacks); an operator invites instead.
 */
@Service
public class SsoAccountService {

	private final SsoLinkRepository links;
	private final UserAccountRepository users;
	private final AuthAuditService audit;

	/**
	 * Creates the service.
	 *
	 * @param links link persistence
	 * @param users account persistence
	 * @param audit audit log
	 */
	public SsoAccountService(SsoLinkRepository links, UserAccountRepository users,
			AuthAuditService audit) {
		this.links = links;
		this.users = users;
		this.audit = audit;
	}

	/**
	 * Resolves an authenticated external identity to a local account, creating a shadow
	 * account on first sight.
	 *
	 * @param issuer         IdP issuer ({@code iss})
	 * @param subject        IdP subject ({@code sub})
	 * @param email          email claim, or {@code null}
	 * @param registrationId Spring registration id
	 * @param ip             remote address, or {@code null}
	 * @param requestId      correlation id, or {@code null}
	 * @return linked account, or empty when the linked account is disabled
	 */
	@Transactional
	public Optional<UserAccount> resolve(String issuer, String subject, String email,
			String registrationId, String ip, String requestId) {
		Optional<SsoLink> link = links.findByIssuerAndSubject(issuer, subject);
		if (link.isPresent()) {
			Optional<UserAccount> account = users.findById(link.get().getUserId());
			if (account.isEmpty() || account.get().isDisabled()) {
				audit.record(AuthAuditService.ACTION_SSO_LOGIN, AuthAuditService.SEVERITY_WARN,
						subject, "/oauth2/callback", AuthAuditService.OUTCOME_FAILURE, ip,
						requestId);
				return Optional.empty();
			}
			audit.record(AuthAuditService.ACTION_SSO_LOGIN, AuthAuditService.SEVERITY_INFO,
					account.get().getUsername(), "/oauth2/callback",
					AuthAuditService.OUTCOME_SUCCESS, ip, requestId);
			return account;
		}
		UserAccount created = new UserAccount(
				uniqueUsername(email != null ? email : subject),
				null,
				email != null ? audit.pseudonym(email) : null,
				false
		);
		users.saveAndFlush(created);
		links.save(new SsoLink(created.getId(), issuer, subject, registrationId));
		audit.record(AuthAuditService.ACTION_SSO_LOGIN, AuthAuditService.SEVERITY_INFO,
				created.getUsername(), "/oauth2/callback", AuthAuditService.OUTCOME_SUCCESS, ip,
				requestId);
		return Optional.of(created);
	}

	private String uniqueUsername(String seed) {
		int at = seed.indexOf('@');
		String base = at > 0 ? seed.substring(0, at) : seed;
		base = base.replaceAll("[^A-Za-z0-9._-]", "-");
		if (base.length() > 200) {
			base = base.substring(0, 200);
		}
		if (base.length() < 3) {
			base = "sso-" + base;
		}
		String candidate = base;
		int suffix = 2;
		while (users.findByUsernameIgnoreCase(candidate).isPresent()) {
			candidate = base + "-" + suffix;
			suffix++;
		}
		return candidate;
	}
}
