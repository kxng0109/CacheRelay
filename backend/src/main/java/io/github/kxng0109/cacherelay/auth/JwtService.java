package io.github.kxng0109.cacherelay.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

/**
 * Issues and validates the short-lived access JWTs carried in SPA memory.
 *
 * <p>Tokens are HMAC-signed (HS256) with the operator-supplied {@code gateway.auth.jwt-secret}.
 * When no secret is configured the service mints an ephemeral one and warns: tokens then
 * invalidate on restart, which is safe but stateless-hostile — production must set the secret.
 * Validation goes through the shared {@link JwtDecoder} bean (issuer, expiry, signature).
 */
@Service
public class JwtService {

	private final JwtEncoder jwtEncoder;
	private final JwtDecoder jwtDecoder;
	private final AuthProperties properties;

	/**
	 * Creates the service, minting an ephemeral secret when none is configured.
	 *
	 * @param jwtEncoder encoder bound to the configured (or ephemeral) secret
	 * @param jwtDecoder decoder bound to the same secret
	 * @param properties auth tuning surface
	 */
	public JwtService(JwtEncoder jwtEncoder, JwtDecoder jwtDecoder, AuthProperties properties) {
		this.jwtEncoder = jwtEncoder;
		this.jwtDecoder = jwtDecoder;
		this.properties = properties;
	}

	/**
	 * Issues a signed access token for an authenticated account.
	 *
	 * @param userId   account identifier ({@code sub})
	 * @param username login name ({@code preferred_username})
	 * @param admin    whether admin claims are stamped
	 * @param ttlSeconds token lifetime in seconds (strict admin value for admins)
	 * @return compact JWT
	 */
	public String issueAccessToken(UUID userId, String username, boolean admin, long ttlSeconds) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(properties.jwtIssuer())
				.subject(userId.toString())
				.issuedAt(now)
				.expiresAt(now.plusSeconds(ttlSeconds))
				.id(UUID.randomUUID().toString())
				.claim("preferred_username", username)
				.claim("admin", admin)
				.build();
		return jwtEncoder.encode(JwtEncoderParameters.from(
				JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
	}

	/**
	 * Validates a presented token (signature, issuer, expiry).
	 *
	 * @param token compact JWT
	 * @return decoded claims
	 * @throws JwtException when invalid
	 */
	public Jwt validate(String token) {
		return jwtDecoder.decode(token);
	}

	/**
	 * Derives the HMAC key bytes from configuration, minting an ephemeral secret when blank.
	 *
	 * @param configured raw configured secret, possibly blank
	 * @return 32+ key bytes
	 */
	static byte[] resolveSecretBytes(String configured) {
		if (configured != null && !configured.isBlank()
				&& configured.getBytes(StandardCharsets.UTF_8).length >= 32) {
			return configured.getBytes(StandardCharsets.UTF_8);
		}
		byte[] ephemeral = new byte[32];
		new SecureRandom().nextBytes(ephemeral);
		return ephemeral;
	}

	/**
	 * Keyed pseudonymization for audit identifiers (usernames, emails, IPs): HMAC-SHA256
	 * with the JWT secret as pepper, hex-encoded. Raw values never reach the ledger.
	 *
	 * @param raw    raw identifier, possibly {@code null}
	 * @param secret pepper bytes
	 * @return hex pseudonym, or {@code "unknown"} for {@code null}
	 */
	static String pseudonymize(String raw, byte[] secret) {
		if (raw == null) {
			return "unknown";
		}
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			throw new IllegalStateException("Pseudonymization unavailable", e);
		}
	}
}
