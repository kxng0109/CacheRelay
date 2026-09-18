package io.github.kxng0109.cacherelay.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Local username+password authentication with failure-counted lockout. Every attempt —
 * success or failure — is audited with a pseudonymized actor; failures inside the window
 * accumulate toward lockout, and lockouts are audited as critical. All rejections share
 * one generic outcome so callers cannot distinguish unknown users from bad passwords.
 */
@Service
public class LoginService {

	/** Authenticated; carries tokens and privilege. */
	public record LoginSuccess(String accessToken, String refreshToken, UUID userId, boolean admin)
			implements LoginResult {
	}

	/** Rejected (unknown, disabled, locked, or bad password — indistinguishable). */
	public record LoginRejected() implements LoginResult {
	}

	/** Outcome of a login attempt. */
	public sealed interface LoginResult permits LoginSuccess, LoginRejected {
	}

	private final UserAccountRepository users;
	private final PasswordEncoder passwordEncoder;
	private final JwtService jwt;
	private final RefreshService refresh;
	private final AuthProperties properties;
	private final AuthAuditService audit;

	/**
	 * Creates the service.
	 *
	 * @param users           account persistence
	 * @param passwordEncoder BCrypt encoder
	 * @param jwt             access-token issuer
	 * @param refresh         refresh family manager
	 * @param properties      auth tuning surface
	 * @param audit           audit log
	 */
	public LoginService(UserAccountRepository users, PasswordEncoder passwordEncoder,
			JwtService jwt, RefreshService refresh, AuthProperties properties,
			AuthAuditService audit) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.jwt = jwt;
		this.refresh = refresh;
		this.properties = properties;
		this.audit = audit;
	}

	/**
	 * Authenticates a local account and mints its session.
	 *
	 * @param username  login name (any case)
	 * @param password  presented password
	 * @param ip        remote address, or {@code null}
	 * @param requestId correlation id, or {@code null}
	 * @return success with tokens, or a generic rejection
	 */
	@Transactional
	public LoginResult login(String username, String password, String ip, String requestId) {
		String actorHash = audit.pseudonym(username);
		Instant windowStart = Instant.now().minus(properties.loginAttemptWindow());
		long failures = audit.recentFailures(actorHash, AuthAuditService.ACTION_LOCAL_LOGIN,
				windowStart);
		if (failures >= properties.loginMaxAttempts()) {
			audit.record(AuthAuditService.ACTION_LOCKOUT, AuthAuditService.SEVERITY_CRITICAL,
					username, "/v1/auth/login", AuthAuditService.OUTCOME_FAILURE, ip, requestId);
			return new LoginRejected();
		}
		Optional<UserAccount> found = users.findByUsernameIgnoreCase(username);
		boolean ok = found.isPresent()
				&& !found.get().isDisabled()
				&& found.get().getPasswordHash() != null
				&& passwordEncoder.matches(password, found.get().getPasswordHash());
		if (!ok) {
			audit.record(AuthAuditService.ACTION_LOCAL_LOGIN, AuthAuditService.SEVERITY_WARN,
					username, "/v1/auth/login", AuthAuditService.OUTCOME_FAILURE, ip, requestId);
			return new LoginRejected();
		}
		UserAccount account = found.get();
		long accessTtl = accessTtl(account.isAdmin()).toSeconds();
		String access = jwt.issueAccessToken(account.getId(), account.getUsername(),
				account.isAdmin(), accessTtl);
		String session = refresh.mint(account.getId(), account.isAdmin());
		audit.record(AuthAuditService.ACTION_LOCAL_LOGIN, AuthAuditService.SEVERITY_INFO,
				username, "/v1/auth/login", AuthAuditService.OUTCOME_SUCCESS, ip, requestId);
		return new LoginSuccess(access, session, account.getId(), account.isAdmin());
	}

	private java.time.Duration accessTtl(boolean admin) {
		return admin ? properties.adminAccessTtl() : properties.accessTtl();
	}
}
