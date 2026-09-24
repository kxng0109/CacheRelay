package io.github.kxng0109.cacherelay.auth.backfill;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * One backfill attempt's inputs. Identity fields are tried per mode; unused
 * fields stay {@code null} without harm. Attributes carry the raw principal
 * claims so the orchestrator evaluates group presence against the
 * registration's configured claim names.
 *
 * @param userKey    stable user key (oid, Okta id), or {@code null}
 * @param email      verified email, or {@code null}
 * @param login      login handle, or {@code null}
 * @param userToken  user's OAuth token (GitHub only), or {@code null}
 * @param attributes raw principal attributes, never {@code null}
 */
public record BackfillRequest(
		String userKey,
		String email,
		String login,
		String userToken,
		Map<String, Object> attributes
) {

	/**
	 * Extracts claim strings defensively: single strings, collections of
	 * strings, and string arrays count; blanks, nulls, and non-strings never
	 * do.
	 *
	 * @param claim raw claim value, or {@code null}
	 * @return extracted strings in encounter order, never {@code null}
	 */
	public static Set<String> extractStrings(Object claim) {
		Set<String> values = new LinkedHashSet<>();
		if (claim instanceof String single) {
			addIfPresent(values, single);
		} else if (claim instanceof Iterable<?> iterable) {
			for (Object element : iterable) {
				if (element instanceof String text) {
					addIfPresent(values, text);
				}
			}
		} else if (claim instanceof String[] array) {
			for (String text : array) {
				addIfPresent(values, text);
			}
		}
		return values;
	}

	private static void addIfPresent(Set<String> values, String text) {
		if (text != null && !text.isBlank()) {
			values.add(text);
		}
	}
}
