package io.github.kxng0109.cacherelay.auth;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that every configured extra {@code connect-src} origin is an
 * absolute {@code http(s)} origin without path, query, or fragment.
 *
 * <p>Scoped to the CSP extra-origins list only. Binding fails startup on
 * malformed values, so a typo can never silently widen the script/data
 * surface the policy constrains.</p>
 *
 * @since 1.8.0
 */
@Documented
@Constraint(validatedBy = CspConnectSrcValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface CspConnectSrc {

	/**
	 * Returns the violation message template.
	 *
	 * @return violation message
	 */
	String message() default
			"gateway.csp.extra-connect-src must list absolute http(s) origins without path, query, or fragment";

	/**
	 * Returns the validation groups.
	 *
	 * @return validation groups
	 */
	Class<?>[] groups() default {};

	/**
	 * Returns the payload types.
	 *
	 * @return payload types
	 */
	Class<? extends Payload>[] payload() default {};
}
