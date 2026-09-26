package io.github.kxng0109.cacherelay.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for self-issued JWTs: roundtrip, tamper rejection, secret handling.
 */
@DisplayName("JwtService")
class JwtServiceTest {

	private final AuthProperties properties = AuthProperties.defaults();
	private final AuthConfig config = new AuthConfig(properties, devEnvironment());

	private static Environment devEnvironment() {
		Environment environment = mock(Environment.class);
		when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});
		return environment;
	}
	private final JwtService service = new JwtService(
			config.authJwtEncoder(), config.authJwtDecoder(), properties);

	@Test
	@DisplayName("issued tokens validate with issuer, subject, and admin claims")
	void roundtrip() {
		UUID userId = UUID.randomUUID();

		String token = service.issueAccessToken(userId, "op", true, 300L);

		Jwt decoded = service.validate(token);
		assertThat(decoded.getSubject()).isEqualTo(userId.toString());
		assertThat(decoded.getClaimAsString("iss")).isEqualTo("cacherelay");
		assertThat(decoded.getClaimAsString("preferred_username")).isEqualTo("op");
		assertThat(decoded.getClaimAsBoolean("admin")).isTrue();
	}

	@Test
	@DisplayName("configured secrets agree between encoder and decoder")
	void configuredSecretRoundtrip() {
		AuthProperties configured = new AuthProperties(null, null, null, null, null, null,
				null, null, null, "cacherelay", "x".repeat(32), 180, null, 5, null, null, null);
		AuthConfig authConfig = new AuthConfig(configured, devEnvironment());
		JwtService service = new JwtService(authConfig.authJwtEncoder(),
				authConfig.authJwtDecoder(), configured);
		UUID userId = UUID.randomUUID();

		String token = service.issueAccessToken(userId, "op", false, 600L);

		assertThat(service.validate(token).getSubject()).isEqualTo(userId.toString());
	}

	@Test
	@DisplayName("FS-B10: issued tokens carry the service audience and foreign audiences are rejected")
	void audienceStampedAndEnforced() {
		UUID userId = UUID.randomUUID();

		String token = service.issueAccessToken(userId, "op", false, 300L);

		assertThat(service.validate(token).getAudience()).containsExactly(JwtService.AUDIENCE);

		JwtClaimsSet forged = JwtClaimsSet.builder()
				.issuer("cacherelay")
				.subject(userId.toString())
				.issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(300L))
				.audience(List.of("foreign-service"))
				.build();
		String forgedToken = config.authJwtEncoder().encode(JwtEncoderParameters.from(
				JwsHeader.with(MacAlgorithm.HS256).build(), forged)).getTokenValue();

		assertThatThrownBy(() -> service.validate(forgedToken)).isInstanceOf(JwtException.class);
	}

	@Test
	@DisplayName("FS-B10: tokens expired beyond the tight skew window are rejected")
	void tightSkewRejectsStaleTokens() {
		UUID userId = UUID.randomUUID();
		JwtClaimsSet stale = JwtClaimsSet.builder()
				.issuer("cacherelay")
				.subject(userId.toString())
				.issuedAt(Instant.now().minusSeconds(100L))
				.expiresAt(Instant.now().minusSeconds(40L))
				.audience(List.of(JwtService.AUDIENCE))
				.build();
		String token = config.authJwtEncoder().encode(JwtEncoderParameters.from(
				JwsHeader.with(MacAlgorithm.HS256).build(), stale)).getTokenValue();

		assertThatThrownBy(() -> service.validate(token)).isInstanceOf(JwtException.class);
	}

	@Test
	@DisplayName("tampered tokens are rejected")
	void tamperedRejected() {
		String token = service.issueAccessToken(UUID.randomUUID(), "op", false, 300L);
		String tampered = token.substring(0, token.length() - 2) + "xx";

		assertThatThrownBy(() -> service.validate(tampered)).isInstanceOf(JwtException.class);
	}

	@Test
	@DisplayName("short or blank secrets fall back to 32 ephemeral bytes")
	void secretFallback() {
		assertThat(JwtService.resolveSecretBytes("x".repeat(32))).hasSize(32);
		assertThat(JwtService.resolveSecretBytes("short")).hasSize(32);
		assertThat(JwtService.resolveSecretBytes("")).hasSize(32);
		assertThat(JwtService.resolveSecretBytes(null)).hasSize(32);
		byte[] first = JwtService.resolveSecretBytes("");
		byte[] second = JwtService.resolveSecretBytes("");
		assertThat(first).as("ephemeral secrets differ").isNotEqualTo(second);
	}

	@Test
	@DisplayName("pseudonyms are deterministic per secret and null-safe")
	void pseudonyms() {
		byte[] secret = JwtService.resolveSecretBytes("x".repeat(32));
		byte[] other = JwtService.resolveSecretBytes("y".repeat(32));

		assertThat(JwtService.pseudonymize("op@example.com", secret))
				.isEqualTo(JwtService.pseudonymize("op@example.com", secret))
				.hasSize(64)
				.isNotEqualTo(JwtService.pseudonymize("op@example.com", other));
		assertThat(JwtService.pseudonymize(null, secret)).isEqualTo("unknown");
	}
}
