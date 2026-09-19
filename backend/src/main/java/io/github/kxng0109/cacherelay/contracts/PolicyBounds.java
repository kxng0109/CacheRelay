package io.github.kxng0109.cacherelay.contracts;

/**
 * Shared bounds for key policy pattern sets (SEC-06/FS-03): caps the number of patterns
 * per set and the length of each pattern so admin-supplied globs cannot pin matching
 * workers or exhaust memory. The same constants back Bean Validation annotations on the
 * admin DTOs and bootstrap binding (fail fast with 400 / startup failure).
 *
 * @since 1.7.0
 */
public final class PolicyBounds {

	/**
	 * Maximum number of patterns in a single policy set.
	 */
	public static final int MAX_PATTERNS = 64;

	/**
	 * Maximum length of a single pattern in characters.
	 */
	public static final int MAX_PATTERN_LENGTH = 256;

	/**
	 * Rejects blank or whitespace-padded patterns (leading/trailing whitespace is never
	 * significant in a glob and almost always a copy-paste mistake).
	 */
	public static final String NON_BLANK_PATTERN = "\\S(?:.*\\S)?";

	private PolicyBounds() {
	}
}
