package io.github.kxng0109.cacherelay.security.guardrail.secret;

import java.util.ArrayList;
import java.util.List;

/**
 * Linear-time JSON Web Token locator replacing the backtracking JWT regular expression (SEC-B02).
 *
 * <p>Accepts exactly the token shape the expression matched: three dot-separated base64url segments
 * where the header and payload segments start with {@code ey} (JSON {@code "eyJ"} prefix), the first
 * two segments are at least 12 characters, the signature at least 10 with up to two {@code =} pad
 * characters, and both ends sit on word boundaries. Deliberately uncapped above: recall for
 * realistic large tokens matters more than bounding already-linear work.
 *
 * <p>Linearity argument: candidates are found with {@code indexOf} and gated on a word boundary, so
 * full segment parses start on pairwise-disjoint runs (an occurrence inside another candidate's run
 * never has a boundary). Total work is linear in the input length on every input, unlike the nested
 * unbounded quantifiers it replaces.
 */
public final class JwtTokenMatcher {

	/**
	 * Rule id this matcher serves in {@link SecretScannerRuleDatabase} (branch point in the scanner).
	 */
	public static final String RULE_ID = "jwt-signed-token";

	/**
	 * Structural JWT candidate: the token bytes plus its offsets for reporting.
	 *
	 * @param token matched token text
	 * @param start start offset in the scanned text (inclusive)
	 * @param end   end offset in the scanned text (exclusive)
	 */
	public record Candidate(String token, int start, int end) {
	}

	private JwtTokenMatcher() {
	}

	/**
	 * Locates every structural JWT candidate in the text, left to right.
	 *
	 * @param text input text, possibly {@code null}
	 * @return candidates in discovery order, never {@code null}
	 */
	public static List<Candidate> findCandidates(String text) {
		List<Candidate> candidates = new ArrayList<>();
		if (text == null || text.isEmpty()) {
			return candidates;
		}
		int length = text.length();
		int from = 0;
		while (true) {
			int start = text.indexOf("ey", from);
			if (start < 0) {
				return candidates;
			}
			from = start + 1;
			if (start > 0 && isWordChar(text.charAt(start - 1))) {
				continue;
			}
			int seg1End = scanRun(text, start, length);
			if (seg1End - start < 12 || seg1End >= length || text.charAt(seg1End) != '.') {
				continue;
			}
			int seg2Start = seg1End + 1;
			if (!text.startsWith("ey", seg2Start)) {
				continue;
			}
			int seg2End = scanRun(text, seg2Start, length);
			if (seg2End - seg2Start < 12 || seg2End >= length || text.charAt(seg2End) != '.') {
				continue;
			}
			int seg3Start = seg2End + 1;
			int seg3End = scanRun(text, seg3Start, length);
			if (seg3End - seg3Start < 10) {
				continue;
			}
			int end = seg3End;
			int pads = 0;
			while (pads < 2 && end < length && text.charAt(end) == '=') {
				end++;
				pads++;
			}
			if (end < length && isWordChar(text.charAt(end))) {
				continue;
			}
			candidates.add(new Candidate(text.substring(start, end), start, end));
			from = end;
		}
	}

	private static int scanRun(String text, int from, int length) {
		int pos = from;
		while (pos < length && isTokenChar(text.charAt(pos))) {
			pos++;
		}
		return pos;
	}

	private static boolean isTokenChar(char c) {
		return (c >= 'a' && c <= 'z')
				|| (c >= 'A' && c <= 'Z')
				|| (c >= '0' && c <= '9')
				|| c == '_' || c == '-';
	}

	private static boolean isWordChar(char c) {
		return (c >= 'a' && c <= 'z')
				|| (c >= 'A' && c <= 'Z')
				|| (c >= '0' && c <= '9')
				|| c == '_';
	}
}
