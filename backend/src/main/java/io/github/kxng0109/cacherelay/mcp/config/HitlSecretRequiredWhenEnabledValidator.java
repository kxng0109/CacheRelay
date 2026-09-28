package io.github.kxng0109.cacherelay.mcp.config;

import io.github.kxng0109.cacherelay.config.SensitiveString;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Enforces {@link HitlSecretRequiredWhenEnabled} on {@link McpGatewayProperties}: when the gateway
 * is disabled every secret shape passes; when enabled, absent, blank, and short secrets fail with
 * the same messages the former field-level checks produced.
 */
public final class HitlSecretRequiredWhenEnabledValidator
		implements ConstraintValidator<HitlSecretRequiredWhenEnabled, McpGatewayProperties> {

	@Override
	public boolean isValid(McpGatewayProperties properties, ConstraintValidatorContext context) {
		if (properties == null || !properties.isEnabled()) {
			return true;
		}
		SensitiveString secret = properties.getHitlSecret();
		String value = secret == null ? null : secret.value();
		if (value == null) {
			reject(context,
					"GATEWAY_MCP_HITL_SECRET is required; provide a 32+ byte base64/random secret"
							+ " via the environment or a secret manager");
			return false;
		}
		if (value.isBlank()) {
			reject(context, "Value required!");
			return false;
		}
		if (value.length() < 32) {
			reject(context, "GATEWAY_MCP_HITL_SECRET must be at least 32 bytes");
			return false;
		}
		return true;
	}

	/**
	 * Replaces the default violation with a single message.
	 *
	 * @param context validation context
	 * @param message violation message
	 */
	private static void reject(ConstraintValidatorContext context, String message) {
		context.disableDefaultConstraintViolation();
		context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
	}
}
