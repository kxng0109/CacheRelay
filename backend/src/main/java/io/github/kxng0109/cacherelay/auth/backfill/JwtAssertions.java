package io.github.kxng0109.cacherelay.auth.backfill;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Shared RS256 assertion minting for service-app backfill flows (Okta
 * private key JWT, Google domain-wide delegation). PKCS8 PEM only: anything
 * else fails closed with {@code null}, surfaced by the gate or the login
 * denial.
 */
public final class JwtAssertions {

	private JwtAssertions() {
	}

	/**
	 * Parses a PKCS8 RSA private key from PEM text.
	 *
	 * @param pem PEM text, never {@code null}
	 * @return private key, or {@code null} when unparsable
	 */
	public static PrivateKey parseRsaPrivateKey(String pem) {
		if (pem == null) {
			return null;
		}
		try {
			String stripped = pem.replace("-----BEGIN PRIVATE KEY-----", "")
					.replace("-----END PRIVATE KEY-----", "")
					.replaceAll("\\s", "");
			byte[] decoded = Base64.getDecoder().decode(stripped);
			return KeyFactory.getInstance("RSA")
					.generatePrivate(new PKCS8EncodedKeySpec(decoded));
		} catch (NoSuchAlgorithmException | InvalidKeySpecException | RuntimeException failed) {
			return null;
		}
	}

	/**
	 * Mints a signed RS256 assertion.
	 *
	 * @param key    signing key, never {@code null}
	 * @param kid    key id header, or {@code null} to omit
	 * @param issuer token issuer, never {@code null}
	 * @param subject token subject, never {@code null}
	 * @param audience token audience, never {@code null}
	 * @param issuedAt issued-at instant, never {@code null}
	 * @param lifetime lifetime duration, never {@code null}
	 * @param extraClaims additional claims, never {@code null}
	 * @return compact JWT, or {@code null} when signing fails
	 */
	public static String signRs256(PrivateKey key, String kid, String issuer, String subject,
			String audience, Instant issuedAt, Duration lifetime,
			Map<String, Object> extraClaims) {
		try {
			JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
					.issuer(issuer)
					.subject(subject)
					.audience(audience)
					.issueTime(Date.from(issuedAt))
					.expirationTime(Date.from(issuedAt.plus(lifetime)))
					.jwtID(UUID.randomUUID().toString());
			for (Map.Entry<String, Object> entry : extraClaims.entrySet()) {
				claims.claim(entry.getKey(), entry.getValue());
			}
			JWSHeader.Builder header = new JWSHeader.Builder(JWSAlgorithm.RS256);
			if (kid != null && !kid.isBlank()) {
				header.keyID(kid);
			}
			SignedJWT jwt = new SignedJWT(header.build(), claims.build());
			jwt.sign(new RSASSASigner(key));
			return jwt.serialize();
		} catch (RuntimeException failed) {
			return null;
		} catch (com.nimbusds.jose.JOSEException failed) {
			return null;
		}
	}
}
