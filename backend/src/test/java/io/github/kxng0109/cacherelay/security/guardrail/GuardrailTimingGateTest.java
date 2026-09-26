package io.github.kxng0109.cacherelay.security.guardrail;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;

import io.github.kxng0109.cacherelay.security.guardrail.pii.PiiScanner;
import io.github.kxng0109.cacherelay.security.guardrail.secret.IngressSecretScanner;
import io.github.kxng0109.cacherelay.security.guardrail.secret.SecretRule;
import io.github.kxng0109.cacherelay.security.guardrail.secret.SecretScannerRuleDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Adversarial timing gate for every guardrail pattern (FS-B03, SEC-B20).
 *
 * <p>Each secret rule and the full PII scan must finish near-miss inputs within budget. Near-miss
 * inputs (valid anchor followed by a long run that can never complete the pattern) are the classic
 * ReDoS trigger: a backtracking engine burns superlinear time while a linear scanner passes in
 * milliseconds. The timeouts are preemptive, so a vulnerable pattern fails fast instead of hanging
 * the suite.
 */
@DisplayName("Guardrail adversarial timing gate (FS-B03)")
class GuardrailTimingGateTest {

	// Spec size: 1 MiB inputs, 250 ms per rule. The same superlinear mechanism fails
	// at any size; smaller inputs were used only to bound orphan-thread burn while
	// reproducing against the vulnerable patterns (see the FS-B03 report).
	private static final int SCAN_BYTES = 1024 * 1024;

	private static final Duration PER_RULE_BUDGET = Duration.ofMillis(250);

	@Test
	@DisplayName("every secret rule finishes near-miss scans within budget")
	void secretRulesMeetPerRuleBudget() {
		for (SecretRule rule : SecretScannerRuleDatabase.getRules()) {
			String anchor = rule.prefixAnchor() == null ? "" : rule.prefixAnchor();
			String letterRun = anchor + "a".repeat(SCAN_BYTES);
			String dotRun = anchor + ".".repeat(SCAN_BYTES);
			// Boundary-separated anchor repetitions: every occurrence is a fresh
			// match attempt, which is the shape that turns backtracking linear
			// scans into quadratic blowups (single-anchor runs stay linear).
			String anchorRepeat = (" " + anchor).repeat(
					Math.max(1, SCAN_BYTES / (anchor.length() + 1)));
			assertTimeoutPreemptively(PER_RULE_BUDGET,
					() -> exhaustMatches(rule, letterRun),
					() -> "rule " + rule.id() + " exceeded budget on letter-run");
			assertTimeoutPreemptively(PER_RULE_BUDGET,
					() -> exhaustMatches(rule, dotRun),
					() -> "rule " + rule.id() + " exceeded budget on dot-run");
			assertTimeoutPreemptively(PER_RULE_BUDGET,
					() -> exhaustMatches(rule, anchorRepeat),
					() -> "rule " + rule.id() + " exceeded budget on anchor-repeat");
		}
	}

	@Test
	@DisplayName("full PII scan finishes adversarial inputs within budget")
	void piiScanMeetsWholeScanBudget() {
		PiiScanner scanner = new PiiScanner();
		String emailTrap = "a".repeat(SCAN_BYTES / 2) + "@" + "a.".repeat(SCAN_BYTES / 4);
		String digitRun = "1".repeat(SCAN_BYTES);
		assertTimeoutPreemptively(Duration.ofSeconds(2),
				() -> scanner.scan(emailTrap),
				() -> "PII scan exceeded budget on email trap");
		assertTimeoutPreemptively(Duration.ofSeconds(2),
				() -> scanner.scan(digitRun),
				() -> "PII scan exceeded budget on digit run");
	}

	@Test
	@DisplayName("ingress scan finishes a JWT near-miss within budget")
	void ingressJwtNearMissMeetsBudget() {
		IngressSecretScanner scanner = new IngressSecretScanner();
		String trap = " ey".repeat(SCAN_BYTES / 3);
		byte[] bytes = trap.getBytes(StandardCharsets.UTF_8);
		assertTimeoutPreemptively(Duration.ofSeconds(1),
				() -> scanner.scan(bytes, trap),
				() -> "ingress scan exceeded budget on JWT near-miss");
	}

	private static void exhaustMatches(SecretRule rule, String input) {
		Matcher matcher = rule.pattern().matcher(input);
		while (matcher.find()) {
			// Consume every match exactly like the scanner loop does.
		}
	}
}
