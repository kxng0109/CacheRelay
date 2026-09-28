package io.github.kxng0109.cacherelay.mcp.config;

import io.github.kxng0109.cacherelay.config.SensitiveString;
import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link HitlSecretRequiredWhenEnabledValidator}: every shape
 * of absent secret fails with a message when enabled, and everything passes
 * when the gateway is disabled.
 */
@DisplayName("HitlSecretRequiredWhenEnabledValidator")
class HitlSecretRequiredWhenEnabledTest {

	private final HitlSecretRequiredWhenEnabledValidator validator =
			new HitlSecretRequiredWhenEnabledValidator();
	private final ConstraintValidatorContext context =
			mock(ConstraintValidatorContext.class, RETURNS_DEEP_STUBS);

	private static McpGatewayProperties properties(boolean enabled, String secret) {
		McpGatewayProperties properties = new McpGatewayProperties();
		properties.setEnabled(enabled);
		properties.setHitlSecret(secret == null ? null : new SensitiveString(secret));
		return properties;
	}

	@Test
	@DisplayName("null properties and disabled gateways always pass")
	void nullAndDisabledPass() {
		assertThat(validator.isValid(null, context)).isTrue();
		assertThat(validator.isValid(properties(false, null), context)).isTrue();
		assertThat(validator.isValid(properties(false, "short"), context)).isTrue();
	}

	@Test
	@DisplayName("absent, blank, and short secrets fail with a message when enabled")
	void absentBlankAndShortFail() {
		assertThat(validator.isValid(properties(true, null), context)).isFalse();
		assertThat(validator.isValid(properties(true, "   "), context)).isFalse();
		assertThat(validator.isValid(properties(true, "too-short"), context)).isFalse();
		verify(context, times(3)).buildConstraintViolationWithTemplate(anyString());
	}

	@Test
	@DisplayName("32+ byte secrets pass when enabled")
	void sufficientSecretPasses() {
		assertThat(validator.isValid(properties(true, "x".repeat(32)), context)).isTrue();
	}
}
