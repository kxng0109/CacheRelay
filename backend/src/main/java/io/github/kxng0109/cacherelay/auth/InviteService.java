package io.github.kxng0109.cacherelay.auth;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import io.github.kxng0109.cacherelay.notify.ChannelResult;
import io.github.kxng0109.cacherelay.notify.GraphEmailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Single-use invitations with conditional email delivery. Every invite yields a copyable
 * redemption link; when the Graph email channel is configured and an address was supplied,
 * the link is also emailed. Redemption is atomic (exactly one winner) and consumed or
 * expired invites answer 410 Gone. The first-ever redemption bootstraps the initial admin.
 * Links use the configured {@code gateway.auth.invite-base-url} frontend origin when set,
 * otherwise the caller's request host.
 */
@Service
public class InviteService {

	/** Invite created and link available. */
	public record CreatedInvite(String link, boolean emailed) {
	}

	/** Redemption outcome. */
	public sealed interface RedeemResult permits RedeemResult.Redeemed, RedeemResult.Gone,
			RedeemResult.Missing {
		/** Redeemed; carries the created account. */
		record Redeemed(UserAccount account, boolean bootstrapped) implements RedeemResult {
		}

		/** Consumed or expired. */
		record Gone() implements RedeemResult {
		}

		/** Unknown token. */
		record Missing() implements RedeemResult {
		}
	}

	private final InviteTokenRepository invites;
	private final UserAccountRepository users;
	private final AuthProperties properties;
	private final PasswordEncoder passwordEncoder;
	private final AuthAuditService audit;
	private final GraphEmailSender mail;
	private final SecureRandom random = new SecureRandom();

	/**
	 * Creates the service.
	 *
	 * @param invites         invite persistence
	 * @param users           account persistence
	 * @param properties      auth tuning surface
	 * @param passwordEncoder BCrypt encoder for redeemed accounts
	 * @param audit           audit log
	 * @param mail            Graph email channel (skips when unconfigured)
	 */
	public InviteService(InviteTokenRepository invites, UserAccountRepository users,
			AuthProperties properties, PasswordEncoder passwordEncoder, AuthAuditService audit,
			GraphEmailSender mail) {
		this.invites = invites;
		this.users = users;
		this.properties = properties;
		this.passwordEncoder = passwordEncoder;
		this.audit = audit;
		this.mail = mail;
	}

	/**
	 * Creates an invite and returns its redemption link, emailing it when possible.
	 *
	 * @param createdBy inviting account id, or {@code null} for master-key bootstrap
	 * @param email     invited address for delivery, or {@code null} for link-only
	 * @param admin     whether redemption creates an admin account
	 * @param baseUrl   request-derived public base URL, used only when
	 *                  {@code gateway.auth.invite-base-url} is blank
	 * @param requestId correlation id, or {@code null}
	 * @return link plus whether it was emailed
	 */
	@Transactional
	public CreatedInvite create(UUID createdBy, String email, boolean admin, String baseUrl,
			String requestId) {
		String token = randomToken();
		String emailHash = email != null ? audit.pseudonym(email) : null;
		invites.save(new InviteToken(
				RefreshService.sha256Hex(token),
				emailHash,
				admin,
				createdBy,
				Instant.now().plus(properties.inviteTtl())));
		String link = linkBase(baseUrl) + "/redeem?token=" + token;
		boolean emailed = false;
		if (email != null) {
			ChannelResult result = mail.sendDirect(email, "CacheRelay invite",
					"You are invited to CacheRelay. Redeem within "
							+ properties.inviteTtl().toHours() + " hours: " + link);
			emailed = result == ChannelResult.SENT;
		}
		String actor = createdBy != null ? createdBy.toString() : "master-key";
		audit.record(AuthAuditService.ACTION_INVITE_CREATED, AuthAuditService.SEVERITY_INFO,
				actor, "/v1/admin/invites", AuthAuditService.OUTCOME_SUCCESS, null, requestId);
		return new CreatedInvite(link, emailed);
	}

	/**
	 * Redeems an invite into a new local account. Exactly one concurrent redeemer wins;
	 * losers, replays, and expired invites resolve to gone.
	 *
	 * @param token    presented opaque token
	 * @param username new login name
	 * @param password new password (BCrypt-hashed)
	 * @param ip       remote address, or {@code null}
	 * @param requestId correlation id, or {@code null}
	 * @return redemption outcome
	 */
	@Transactional
	public RedeemResult redeem(String token, String username, String password, String ip,
			String requestId) {
		Optional<InviteToken> found = invites.findByTokenHash(RefreshService.sha256Hex(token));
		if (found.isEmpty()) {
			return new RedeemResult.Missing();
		}
		InviteToken invite = found.get();
		Instant now = Instant.now();
		if (invite.getConsumedAt() != null || now.isAfter(invite.getExpiresAt())) {
			return new RedeemResult.Gone();
		}
		if (users.findByUsernameIgnoreCase(username).isPresent()) {
			return new RedeemResult.Gone();
		}
		boolean bootstrapped = users.count() == 0;
		UserAccount account = new UserAccount(
				username,
				passwordEncoder.encode(password),
				invite.getEmailHash(),
				invite.isAdmin() || bootstrapped);
		users.saveAndFlush(account);
		int won = invites.consume(invite.getId(), account.getId(), now);
		if (won == 0) {
			if (TransactionSynchronizationManager.isActualTransactionActive()) {
				TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
			}
			return new RedeemResult.Gone();
		}
		String action = bootstrapped
				? AuthAuditService.ACTION_BOOTSTRAP_CONSUMED
				: AuthAuditService.ACTION_INVITE_CONSUMED;
		audit.record(action, AuthAuditService.SEVERITY_INFO, username, "/v1/auth/redeem",
				AuthAuditService.OUTCOME_SUCCESS, ip, requestId);
		return new RedeemResult.Redeemed(account, bootstrapped);
	}

	/**
	 * Resolves the link base for an invite: the configured frontend origin when set,
	 * otherwise the caller-supplied request-derived fallback. The configured value is
	 * revalidated here so programmatic use outside the Spring startup gate still fails
	 * closed instead of emitting hostile links.
	 *
	 * @param fallback request-derived base, used only when nothing is configured
	 * @return trimmed configured base or the fallback
	 * @throws IllegalStateException when the configured base is malformed
	 */
	private String linkBase(String fallback) {
		String configured = properties.inviteBaseUrl();
		if (configured.isBlank()) {
			return fallback;
		}
		AuthConfig.validateInviteBaseUrl(configured);
		return configured.trim().replaceAll("/+$", "");
	}

	private String randomToken() {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
