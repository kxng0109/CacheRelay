package io.github.kxng0109.cacherelay.admin;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that the admin master key carries at least 32 bytes of key material
 * and is not a published default value.
 *
 * <p>Scoped to the admin master key only. Must NOT be placed on the shared
 * {@code SensitiveString} record component — other secrets using that type have
 * their own length rules.</p>
 *
 * @since 1.7.0
 */
@Documented
@Constraint(validatedBy = AdminMasterKeyValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminMasterKey {

	/**
	 * Returns the violation message template.
	 *
	 * @return violation message
	 */
	String message() default
			"gateway.admin.master-key must be at least 32 bytes and must not be a published default";

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
