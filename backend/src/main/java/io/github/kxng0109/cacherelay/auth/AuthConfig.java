package io.github.kxng0109.cacherelay.auth;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Beans for human authentication: self-issued JWT codec pair plus the delegating
 * password encoder for local accounts.
 *
 * <p>The HMAC secret comes from {@code gateway.auth.jwt-secret}; when blank an ephemeral
 * secret is minted (tokens invalidate on restart — safe, but production must configure a
 * stable secret for multi-instance agreement).
 */
@Configuration
@Slf4j
public class AuthConfig {

	private final byte[] secret;
	private final String issuer;

	/**
	 * Resolves the shared HMAC secret once so the encoder and decoder always agree.
	 *
	 * @param properties auth tuning surface
	 */
	public AuthConfig(AuthProperties properties) {
		this.secret = JwtService.resolveSecretBytes(properties.jwtSecret());
		this.issuer = properties.jwtIssuer();
		if (properties.jwtSecret() == null || properties.jwtSecret().isBlank()) {
			log.warn("gateway.auth.jwt-secret is blank: using an ephemeral signing secret. "
					+ "Sessions invalidate on restart and multi-instance hosts will disagree. "
					+ "Configure a stable 32+ byte secret for production.");
		}
	}

	/**
	 * Creates the JWT encoder over the shared HMAC secret.
	 *
	 * @return encoder for self-issued access tokens
	 */
	@Bean
	JwtEncoder authJwtEncoder() {
		OctetSequenceKey jwk =
				new OctetSequenceKey.Builder(secret).algorithm(JWSAlgorithm.HS256).build();
		return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
	}

	/**
	 * Creates the JWT decoder over the same secret, enforcing issuer and timestamps.
	 *
	 * @return decoder for presented access tokens
	 */
	@Bean
	JwtDecoder authJwtDecoder() {
		SecretKey key = new SecretKeySpec(secret, "HmacSHA256");
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).build();
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
		return decoder;
	}

	/**
	 * Creates the delegating password encoder for local accounts (BCrypt default).
	 *
	 * @return password encoder
	 */
	@Bean
	PasswordEncoder authPasswordEncoder() {
		return new BCryptPasswordEncoder();
	}
}
