package io.github.kxng0109.cacherelay.auth.backfill;

import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JwtAssertions")
class JwtAssertionsTest {

	@Test
	@DisplayName("valid PEM parses and signs verifiable assertions")
	void signAndVerify() throws Exception {
		assertThat(JwtAssertions.parseRsaPrivateKey(TestKeys.RSA_PRIVATE_PEM)).isNotNull();

		String compact = JwtAssertions.signRs256(TestKeys.RSA.getPrivate(), "kid-1", "iss-1",
				"sub-1", "https://example.invalid/token", Instant.parse("2026-09-23T12:00:00Z"),
				Duration.ofSeconds(300L), Map.of("scope", "a b"));

		assertThat(compact).isNotNull();
		SignedJWT parsed = SignedJWT.parse(compact);
		assertThat(parsed.verify(new RSASSAVerifier((RSAPublicKey)
				TestKeys.RSA.getPublic()))).isTrue();
		assertThat(parsed.getJWTClaimsSet().getIssuer()).isEqualTo("iss-1");
		assertThat(parsed.getJWTClaimsSet().getSubject()).isEqualTo("sub-1");
		assertThat(parsed.getJWTClaimsSet().getStringClaim("scope")).isEqualTo("a b");
		assertThat(parsed.getHeader().getKeyID()).isEqualTo("kid-1");
	}

	@Test
	@DisplayName("blank kids are omitted from the header")
	void blankKidOmitted() throws Exception {
		String compact = JwtAssertions.signRs256(TestKeys.RSA.getPrivate(), "  ", "iss-1",
				"sub-1", "https://example.invalid/token", Instant.now(), Duration.ofSeconds(60L),
				Map.of());

		assertThat(compact).isNotNull();
		String header = new String(Base64.getUrlDecoder().decode(compact.split("\\.")[0]),
				StandardCharsets.UTF_8);
		assertThat(header).doesNotContain("kid");
	}

	@Test
	@DisplayName("garbage keys fail closed")
	void garbageKeyDenied() {
		assertThat(JwtAssertions.parseRsaPrivateKey("not-a-pem")).isNull();
		assertThat(JwtAssertions.parseRsaPrivateKey(null)).isNull();
		assertThat(JwtAssertions.parseRsaPrivateKey(
				"-----BEGIN PRIVATE KEY-----\n!!!\n-----END PRIVATE KEY-----")).isNull();
	}
}
