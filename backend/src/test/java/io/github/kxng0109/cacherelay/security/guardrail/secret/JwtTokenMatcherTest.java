package io.github.kxng0109.cacherelay.security.guardrail.secret;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Detection parity for the structural JWT locator (FS-B03): every realistic verdict the
 * backtracking expression produced is preserved, without the superlinear scan.
 */
@DisplayName("JwtTokenMatcher")
class JwtTokenMatcherTest {

	private static final String JWT =
			"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
					+ ".eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ"
					+ ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";

	@Test
	@DisplayName("detects a realistic three-segment token with exact offsets")
	void detectsRealisticToken() {
		String text = "bearer " + JWT + " leaked";

		List<JwtTokenMatcher.Candidate> candidates = JwtTokenMatcher.findCandidates(text);

		assertThat(candidates).hasSize(1);
		assertThat(candidates.getFirst().token()).isEqualTo(JWT);
		assertThat(candidates.getFirst().start()).isEqualTo(7);
		assertThat(candidates.getFirst().end()).isEqualTo(7 + JWT.length());
	}

	@Test
	@DisplayName("scanner corroborates the token under the jwt-signed-token rule")
	void scannerDetectsToken() {
		IngressSecretScanner scanner = new IngressSecretScanner();
		String text = "{\"auth\": \"" + JWT + "\"}";
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

		SecretScanResult result = scanner.scan(bytes, text);

		assertThat(result.detected()).isTrue();
		assertThat(result.ruleId()).isEqualTo("jwt-signed-token");
	}

	@Test
	@DisplayName("rejects two-segment, short-segment, and non-ey payloads")
	void rejectsMalformedShapes() {
		assertThat(JwtTokenMatcher.findCandidates("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0")).isEmpty();
		assertThat(JwtTokenMatcher.findCandidates("eyabcdefgh.ij")).isEmpty();
		assertThat(JwtTokenMatcher.findCandidates("eyAAAAAAAAAAAAAA.zzzzzzzzzzzz.qqqqqqqqqq")).isEmpty();
		assertThat(JwtTokenMatcher.findCandidates("not a token at all")).isEmpty();
		assertThat(JwtTokenMatcher.findCandidates(null)).isEmpty();
		assertThat(JwtTokenMatcher.findCandidates("")).isEmpty();
	}

	@Test
	@DisplayName("mid-word ey prefix is not a boundary")
	void rejectsMidWordPrefix() {
		assertThat(JwtTokenMatcher.findCandidates("x" + JWT)).isEmpty();
	}

	@Test
	@DisplayName("padded signature is accepted")
	void acceptsPaddedSignature() {
		String padded = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
				+ ".eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4ifQ"
				+ ".SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c==";

		List<JwtTokenMatcher.Candidate> candidates = JwtTokenMatcher.findCandidates(padded);

		assertThat(candidates).hasSize(1);
		assertThat(candidates.getFirst().token()).isEqualTo(padded);
	}

	@Test
	@DisplayName("FS-B12: segment-length, dot, and end-of-input arms all reject")
	void segmentBoundaryArms() {		assertThat(JwtTokenMatcher.findCandidates("hey eyes")).isEmpty();
		assertThat(JwtTokenMatcher.findCandidates("eyJ")).isEmpty();
		assertThat(JwtTokenMatcher.findCandidates("xx ey" + "A".repeat(20) + " end")).isEmpty();
		assertThat(JwtTokenMatcher.findCandidates("ey" + "A".repeat(20))).isEmpty();
		assertThat(JwtTokenMatcher.findCandidates("eyAAAAAAAAAAAA.QQQQQQQQQQQQ.")).isEmpty();
		assertThat(JwtTokenMatcher.findCandidates("eyAAAAAAAAAAAA.eyBBBB.")).isEmpty();
		assertThat(JwtTokenMatcher.findCandidates("eyAAAAAAAAAAAA.eyBBBBBBBBBBBB.ccccccccc")).isEmpty();
	}

	@Test
	@DisplayName("FS-B12: pad counts, trailing boundaries, and token alphabet arms")
	void padAndBoundaryArms() {
		String header = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9";
		String payload = "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4ifQ";
		String sig = "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV-adQssw5c";
		String onePad = header + "." + payload + "." + sig + "=";

		assertThat(JwtTokenMatcher.findCandidates(onePad)).hasSize(1);
		assertThat(JwtTokenMatcher.findCandidates(onePad + "x")).isEmpty();
		assertThat(JwtTokenMatcher.findCandidates(onePad + " ")).hasSize(1);
		assertThat(JwtTokenMatcher.findCandidates("A" + JWT)).isEmpty();
		assertThat(JwtTokenMatcher.findCandidates("0" + JWT)).isEmpty();
		assertThat(JwtTokenMatcher.findCandidates("_" + JWT)).isEmpty();
		assertThat(JwtTokenMatcher.findCandidates("." + JWT)).hasSize(1);
		assertThat(JwtTokenMatcher.findCandidates("-" + JWT)).hasSize(1);
	}
}
