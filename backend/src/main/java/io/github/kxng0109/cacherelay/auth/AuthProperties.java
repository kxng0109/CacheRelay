package io.github.kxng0109.cacherelay.auth;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Human-authentication tuning surface, bound from {@code gateway.auth.*}.
 *
 * <p>Standard (non-admin) lifetimes are the relaxed defaults; every admin path uses the
 * strict {@code admin-*} counterparts. Refresh sessions carry both an idle expiry (sliding,
 * refreshed on use) and an absolute expiry (fixed at creation, never extended).
 *
 * @param accessTtl          access-JWT lifetime for standard accounts
 * @param adminAccessTtl     access-JWT lifetime for admin accounts (strict)
 * @param refreshTtl         idle refresh lifetime for standard accounts
 * @param adminRefreshTtl    idle refresh lifetime for admin accounts (strict)
 * @param absoluteMaxTtl     absolute session ceiling for standard accounts
 * @param adminAbsoluteMaxTtl absolute session ceiling for admin accounts (strict)
 * @param inviteTtl          invite-token lifetime from creation
 * @param cookieName         refresh-cookie name (dev profile drops the {@code __Host-} prefix)
 * @param cookieSameSite     refresh-cookie {@code SameSite} attribute, always explicit
 * @param jwtIssuer          {@code iss} claim stamped on self-issued access JWTs
 * @param jwtSecret          HMAC secret for self-issued JWTs (env-supplied; ephemeral when blank)
 * @param auditRetentionDays default audit-event retention in days
 * @param auditRetentionDaysByJurisdiction per-jurisdiction retention overrides (legal floor wins)
 * @param loginMaxAttempts   failed logins before lockout within {@code loginAttemptWindow}
 * @param loginAttemptWindow window for counting failed logins
 * @param loginLockout       lockout duration after {@code loginMaxAttempts} failures
 * @since 1.8.0
 */
@ConfigurationProperties(prefix = "gateway.auth")
public record AuthProperties(
		Duration accessTtl,
		Duration adminAccessTtl,
		Duration refreshTtl,
		Duration adminRefreshTtl,
		Duration absoluteMaxTtl,
		Duration adminAbsoluteMaxTtl,
		Duration inviteTtl,
		String cookieName,
		String cookieSameSite,
		String jwtIssuer,
		String jwtSecret,
		int auditRetentionDays,
		Map<String, Integer> auditRetentionDaysByJurisdiction,
		int loginMaxAttempts,
		Duration loginAttemptWindow,
		Duration loginLockout
) {
	/**
	 * Applies production-safe defaults for every property the operator leaves unset.
	 */
	public AuthProperties {
		accessTtl = accessTtl != null ? accessTtl : Duration.ofMinutes(10);
		adminAccessTtl = adminAccessTtl != null ? adminAccessTtl : Duration.ofMinutes(5);
		refreshTtl = refreshTtl != null ? refreshTtl : Duration.ofDays(14);
		adminRefreshTtl = adminRefreshTtl != null ? adminRefreshTtl : Duration.ofDays(7);
		absoluteMaxTtl = absoluteMaxTtl != null ? absoluteMaxTtl : Duration.ofDays(30);
		adminAbsoluteMaxTtl = adminAbsoluteMaxTtl != null ? adminAbsoluteMaxTtl : Duration.ofDays(7);
		inviteTtl = inviteTtl != null ? inviteTtl : Duration.ofHours(48);
		cookieName = cookieName != null ? cookieName : "__Host-refresh";
		cookieSameSite = cookieSameSite != null ? cookieSameSite : "Lax";
		jwtIssuer = jwtIssuer != null ? jwtIssuer : "cacherelay";
		jwtSecret = jwtSecret != null ? jwtSecret : "";
		auditRetentionDaysByJurisdiction = auditRetentionDaysByJurisdiction != null
				? Map.copyOf(auditRetentionDaysByJurisdiction)
				: Map.of();
		loginAttemptWindow = loginAttemptWindow != null ? loginAttemptWindow : Duration.ofMinutes(15);
		loginLockout = loginLockout != null ? loginLockout : Duration.ofMinutes(30);
	}

	/**
	 * Creates the default property set (all relaxed/strict pairs at their documented values).
	 *
	 * @return default properties
	 */
	public static AuthProperties defaults() {
		return new AuthProperties(null, null, null, null, null, null, null, null, null, null, "",
				180, new HashMap<>(), 5, null, null);
	}
}
