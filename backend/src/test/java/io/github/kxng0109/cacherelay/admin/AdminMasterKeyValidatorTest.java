package io.github.kxng0109.cacherelay.admin;

import io.github.kxng0109.cacherelay.config.SensitiveString;
import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AdminMasterKeyValidator")
class AdminMasterKeyValidatorTest {

	private final AdminMasterKeyValidator validator = new AdminMasterKeyValidator();

	private ConstraintValidatorContext context;

	@BeforeEach
	void stubViolationBuilder() {
		context = mock(ConstraintValidatorContext.class);
		ConstraintValidatorContext.ConstraintViolationBuilder builder =
				mock(ConstraintValidatorContext.ConstraintViolationBuilder.class);
		when(context.buildConstraintViolationWithTemplate(anyString())).thenReturn(builder);
		when(builder.addConstraintViolation()).thenReturn(context);
	}

	@Test
	@DisplayName("null holder passes (covered by @NotNull)")
	void nullHolderPasses() {
		assertThat(validator.isValid(null, context)).isTrue();
	}

	@Test
	@DisplayName("null value passes (covered by cascaded @NotBlank)")
	@SuppressWarnings("DataFlowIssue")
	void nullValuePasses() {
		assertThat(validator.isValid(new SensitiveString(null), context)).isTrue();
	}

	@Test
	@DisplayName("published defaults fail, case-insensitively")
	void knownDefaultsFail() {
		assertThat(validator.isValid(
				new SensitiveString("cacherelay_admin_secret_key"), context)).isFalse();
		assertThat(validator.isValid(
				new SensitiveString("CACHERELAY_ADMIN_SECRET_KEY"), context)).isFalse();
		assertThat(validator.isValid(new SensitiveString("changeme"), context)).isFalse();
	}

	@Test
	@DisplayName("short non-default secrets fail, 32 byte secrets pass")
	void lengthBoundary() {
		assertThat(validator.isValid(new SensitiveString("short"), context)).isFalse();
		assertThat(validator.isValid(new SensitiveString("x".repeat(31)), context)).isFalse();
		assertThat(validator.isValid(new SensitiveString("x".repeat(32)), context)).isTrue();
		assertThat(validator.isValid(
				new SensitiveString("test-only-admin-master-key-32b-min!!"), context)).isTrue();
	}
}
