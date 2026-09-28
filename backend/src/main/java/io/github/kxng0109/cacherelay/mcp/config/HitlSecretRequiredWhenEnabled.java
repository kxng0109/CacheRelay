package io.github.kxng0109.cacherelay.mcp.config;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * Requires the HITL resumption-token secret only when the MCP gateway is enabled (MCP-B36): a
 * disabled subsystem must never fail startup over its secret. Replaces unconditional field-level
 * presence/length checks, reproducing their exact messages for the enabled case.
 */
@Documented
@Constraint(validatedBy = HitlSecretRequiredWhenEnabledValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface HitlSecretRequiredWhenEnabled {

	String message() default "GATEWAY_MCP_HITL_SECRET is required when the MCP gateway is enabled";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
