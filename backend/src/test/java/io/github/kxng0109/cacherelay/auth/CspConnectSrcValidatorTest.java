package io.github.kxng0109.cacherelay.auth;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the {@code connect-src} origin validator (FE-17).
 */
@DisplayName("CspConnectSrcValidator")
class CspConnectSrcValidatorTest {

	private final CspConnectSrcValidator validator = new CspConnectSrcValidator();

	private final ConstraintValidatorContext context = mock(ConstraintValidatorContext.class);

	@Test
	@DisplayName("null and empty lists pass (same-origin default)")
	void nullAndEmptyPass() {
		assertThat(validator.isValid(null, context)).isTrue();
		assertThat(validator.isValid(List.of(), context)).isTrue();
	}

	@Test
	@DisplayName("absolute http(s) origins with optional ports pass")
	void absoluteOriginsPass() {
		assertThat(validator.isValid(List.of("http://localhost:9091"), context)).isTrue();
		assertThat(validator.isValid(
				List.of("https://metrics.internal", "https://grafana.example.com:3000"), context))
				.isTrue();
	}

	@Test
	@DisplayName("paths, queries, wildcards, and non-http schemes fail")
	void malformedOriginsFail() {
		assertThat(validator.isValid(List.of("http://localhost:9091/api"), context)).isFalse();
		assertThat(validator.isValid(List.of("https://example.com?x=1"), context)).isFalse();
		assertThat(validator.isValid(List.of("https://*.example.com"), context)).isFalse();
		assertThat(validator.isValid(List.of("ws://localhost:9091"), context)).isFalse();
		assertThat(validator.isValid(List.of("localhost:9091"), context)).isFalse();
		assertThat(validator.isValid(List.of(""), context)).isFalse();
		assertThat(validator.isValid(List.of("http://localhost:9091/"), context)).isFalse();
	}

	@Test
	@DisplayName("null entries fail without throwing")
	void nullEntryFails() {
		List<String> withNull = new ArrayList<>();
		withNull.add(null);

		assertThat(validator.isValid(withNull, context)).isFalse();
	}
}
