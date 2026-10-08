package io.github.kxng0109.cacherelay.contracts;

import java.util.Set;

/**
 * Shared deny-precedence glob policy for tool and agent authorization.
 *
 * <p>Both the MCP tool engine and the A2A agent engine enforce identical semantics: a null, blank, or unauthenticated
 * target is denied; the deny list takes absolute precedence; an empty allow list permits all; otherwise at least one
 * allow pattern must match. Patterns use the linear glob matcher (no regex), so adversarial patterns cannot trigger
 * catastrophic backtracking. Case folding is ASCII-only and locale-independent.</p>
 */
public final class RbacPolicy {

	private RbacPolicy() {
	}

	/**
	 * Checks a target against deny-then-allow glob sets.
	 *
	 * @param target candidate name, possibly {@code null} (never allowed)
	 * @param allowed allow patterns, null or empty means permit-all past the deny list
	 * @param denied deny patterns, null entries never match
	 * @return true when allowed, false when denied
	 */
	public static boolean isAllowed(String target, Set<String> allowed, Set<String> denied) {
		if (target == null || target.isBlank()) {
			return false;
		}
		String text = target.trim();

		if (denied != null && !denied.isEmpty()) {
			for (String pattern : denied) {
				if (matchesPattern(text, pattern)) {
					return false;
				}
			}
		}

		if (allowed == null || allowed.isEmpty()) {
			return true;
		}
		for (String pattern : allowed) {
			if (matchesPattern(text, pattern)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Tests text against a glob pattern ({@code *} and {@code ?} wildcards).
	 *
	 * <p>All other characters are literals. A blank or null pattern never matches.</p>
	 *
	 * @param text text to test, possibly {@code null} (never matches)
	 * @param globPattern glob pattern, possibly {@code null} or blank (never matches)
	 * @return true when the whole text matches the pattern
	 */
	public static boolean matchesPattern(String text, String globPattern) {
		if (text == null || globPattern == null || globPattern.isBlank()) {
			return false;
		}
		String pattern = globPattern.trim();
		if ("*".equals(pattern)) {
			return true;
		}
		int textIndex = 0;
		int patternIndex = 0;
		int starIndex = -1;
		int resumeIndex = 0;
		while (textIndex < text.length()) {
			if (patternIndex < pattern.length()
					&& (pattern.charAt(patternIndex) == '?'
					|| asciiEqual(pattern.charAt(patternIndex), text.charAt(textIndex)))) {
				textIndex++;
				patternIndex++;
			} else if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
				starIndex = patternIndex++;
				resumeIndex = textIndex;
			} else if (starIndex != -1) {
				patternIndex = starIndex + 1;
				textIndex = ++resumeIndex;
			} else {
				return false;
			}
		}
		while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
			patternIndex++;
		}
		return patternIndex == pattern.length();
	}

	/**
	 * Compares two characters with ASCII-only case folding.
	 *
	 * @param patternChar pattern character
	 * @param textChar text character
	 * @return true when equal ignoring ASCII case
	 */
	private static boolean asciiEqual(char patternChar, char textChar) {
		return patternChar == textChar
				|| asciiLower(patternChar) == asciiLower(textChar);
	}

	/**
	 * Lowercases an ASCII uppercase character.
	 *
	 * @param value character to fold
	 * @return folded character
	 */
	private static char asciiLower(char value) {
		return (value >= 'A' && value <= 'Z') ? (char) (value + 32) : value;
	}
}
