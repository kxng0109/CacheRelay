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
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
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
 * <p>The HMAC secret comes from {@code gateway.auth.jwt-secret} and is mandatory outside
 * the {@code dev} and {@code test} profiles: startup fails when it is missing or shorter
 * than 32 bytes, so sessions survive restarts and validate across instances. Under
 * {@code dev}/{@code test} a blank secret mints an ephemeral one (single-instance safe,
 * restart-invalid) with a warning.</p>
 *
 * <p>The invite-link base comes from {@code gateway.auth.invite-base-url} and is optional:
 * blank keeps the request-derived fallback with a warning, while a malformed explicit
 * value fails startup in every profile.</p>
 */
@Configuration
@Slf4j
public class AuthConfig {

	private final byte[] secret;
	private final String issuer;

	/**
	 * Resolves the shared HMAC secret once so the encoder and decoder always agree.
	 *
	 * @param properties  auth tuning surface
	 * @param environment active Spring profiles (ephemeral secrets are dev/test-only)
	 * @throws IllegalStateException when no stable secret is configured outside dev/test
	 */
	public AuthConfig(AuthProperties properties, Environment environment) {
		List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
		this.secret = resolveSecret(properties.jwtSecret(), activeProfiles);
		this.issuer = properties.jwtIssuer();
		validateInviteBaseUrl(properties.inviteBaseUrl());
		if (properties.inviteBaseUrl().isBlank()) {
			log.warn("gateway.auth.invite-base-url is blank: invite links derive from the incoming "
					+ "request host. Set GATEWAY_AUTH_INVITE_BASE_URL to the public frontend origin "
					+ "in split-origin deployments.");
		}
		if (isEphemeral(properties.jwtSecret())) {
			log.warn("gateway.auth.jwt-secret is blank: using an ephemeral signing secret. "
					+ "Sessions invalidate on restart and multi-instance hosts will disagree. "
					+ "Configure a stable 32+ byte secret for production.");
		}
	}

	/**
	 * Returns whether the given profiles relax secret requirements (dev/test only).
	 *
	 * @param activeProfiles active Spring profile names
	 * @return true when {@code dev} or {@code test} is active
	 */
	static boolean isRelaxedProfile(List<String> activeProfiles) {
		return activeProfiles.contains("dev") || activeProfiles.contains("test");
	}

	/**
	 * Returns whether the configured secret falls back to an ephemeral one.
	 *
	 * @param configured raw configured secret, possibly blank
	 * @return true when blank (ephemeral minted by {@link JwtService#resolveSecretBytes})
	 */
	private static boolean isEphemeral(String configured) {
		return configured == null || configured.isBlank();
	}

	/**
	 * Resolves the HMAC secret, failing fast outside dev/test when no stable 32+ byte
	 * secret is configured.
	 *
	 * @param configured     raw configured secret, possibly blank
	 * @param activeProfiles active Spring profile names
	 * @return 32+ key bytes (stable or ephemeral)
	 * @throws IllegalStateException when the secret is missing or short outside dev/test
	 */
	static byte[] resolveSecret(String configured, List<String> activeProfiles) {
		if (configured != null && !configured.isBlank()
				&& configured.getBytes(StandardCharsets.UTF_8).length >= 32) {
			return configured.getBytes(StandardCharsets.UTF_8);
		}
		if (!isRelaxedProfile(activeProfiles)) {
			throw new IllegalStateException(
					"gateway.auth.jwt-secret (GATEWAY_AUTH_JWT_SECRET) is required outside the dev/test "
							+ "profiles: provide a stable 32+ byte secret via the environment or a secret "
							+ "manager. Active profiles: " + activeProfiles);
		}
		return JwtService.resolveSecretBytes(configured);
	}

	/**
	 * Validates the configured invite-link base URL, failing fast on explicit
	 * misconfiguration in any profile. Blank stays unset (invite links derive from
	 * the incoming request host); anything else must be an absolute {@code http(s)}
	 * URL with a host and no user info, query, or fragment, so generated links can
	 * never carry a hostile scheme or a poisoned host.
	 *
	 * @param configured raw configured base, possibly {@code null} or blank
	 * @throws IllegalStateException when a non-blank value is not an absolute http(s) URL
	 */
	static void validateInviteBaseUrl(String configured) {
		if (configured == null || configured.isBlank()) {
			return;
		}
		String trimmed = configured.trim();
		URI base;
		try {
			base = new URI(trimmed);
		} catch (URISyntaxException invalid) {
			throw new IllegalStateException(inviteBaseMessage(trimmed), invalid);
		}
		String scheme = base.getScheme();
		if (base.isOpaque() || scheme == null
				|| (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
				|| base.getHost() == null || base.getUserInfo() != null
				|| base.getRawQuery() != null || base.getRawFragment() != null) {
			throw new IllegalStateException(inviteBaseMessage(trimmed));
		}
	}

	private static String inviteBaseMessage(String trimmed) {
		return "gateway.auth.invite-base-url (GATEWAY_AUTH_INVITE_BASE_URL) must be an absolute "
				+ "http(s) URL with a host and no user info, query, or fragment when set; blank keeps "
				+ "the request-derived fallback. Offending value: '" + trimmed + "'";
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
