package io.github.kxng0109.cacherelay.admin;

import io.github.kxng0109.cacherelay.config.SensitiveString;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Validates {@link AdminMasterKey} against a {@link SensitiveString} holder.
 *
 * <p>Null holders pass (covered by {@code @NotNull}); blank values fail the
 * length check. Known published defaults fail even when long enough, compared
 * case-insensitively so trivial capitalization does not bypass the denylist.</p>
 *
 * @since 1.7.0
 */
public final class AdminMasterKeyValidator
		implements ConstraintValidator<AdminMasterKey, SensitiveString> {

	/**
	 * Published default key material that must never authenticate an environment.
	 */
	private static final Set<String> KNOWN_DEFAULTS = Set.of(
			"cacherelay_admin_secret_key",
			"changeme",
			"admin",
			"password",
			"secret",
			"cacherelay_secret");

	@Override
	public boolean isValid(SensitiveString secret, ConstraintValidatorContext context) {
		if (secret == null || secret.value() == null) {
			return true;
		}
		String value = secret.value();
		if (value.isBlank()) {
			return false;
		}
		String lowered = value.toLowerCase(Locale.ROOT);
		if (KNOWN_DEFAULTS.contains(lowered)) {
			buildMessage(context,
					"gateway.admin.master-key must not be a published default; rotate to a fresh 32+ byte secret");
			return false;
		}
		if (value.getBytes(StandardCharsets.UTF_8).length < 32) {
			buildMessage(context,
					"gateway.admin.master-key must be at least 32 bytes");
			return false;
		}
		return true;
	}

	/**
	 * Replaces the default violation with the given message.
	 *
	 * @param context active validation context
	 * @param message replacement violation message
	 */
	private void buildMessage(ConstraintValidatorContext context, String message) {
		context.disableDefaultConstraintViolation();
		context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
	}
}
