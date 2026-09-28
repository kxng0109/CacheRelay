package io.github.kxng0109.cacherelay.auth;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates {@link CspConnectSrc} origin lists.
 *
 * <p>Null and empty lists pass (same-origin default). Every entry must match
 * {@code https?://host[:port]} with no path, query, fragment, or wildcard:
 * the value is interpolated verbatim into the {@code Content-Security-Policy}
 * header, so anything outside that shape fails closed at startup instead of
 * shipping a broken or over-broad policy.</p>
 *
 * @since 1.8.0
 */
public final class CspConnectSrcValidator
		implements ConstraintValidator<CspConnectSrc, List<String>> {

	/**
	 * Absolute http(s) origin with an optional port and nothing after it.
	 */
	private static final Pattern ORIGIN =
			Pattern.compile("^https?://[A-Za-z0-9._\\-]+(?::\\d{1,5})?$");

	@Override
	public boolean isValid(List<String> origins, ConstraintValidatorContext context) {
		if (origins == null || origins.isEmpty()) {
			return true;
		}
		for (String origin : origins) {
			if (origin == null || !ORIGIN.matcher(origin).matches()) {
				return false;
			}
		}
		return true;
	}
}
