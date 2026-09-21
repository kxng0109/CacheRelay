package io.github.kxng0109.cacherelay.admin.dto;

import io.github.kxng0109.cacherelay.contracts.BootstrapKey;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Key request policy bounds (SEC-06/FS-03)")
class KeyRequestValidationTest {

	private final Validator validator =
			Validation.buildDefaultValidatorFactory().getValidator();

	private static Set<String> patterns(int count, String base) {
		return IntStream.range(0, count).mapToObj(i -> base + i).collect(Collectors.toSet());
	}

	@Test
	@DisplayName("65 patterns are rejected")
	void tooManyPatternsRejected() {
		CreateKeyRequest request = new CreateKeyRequest(
				"owner", "name", 60, 1000, patterns(65, "model-"), Set.of(),
				Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), null, null);

		assertThat(validator.validate(request)).isNotEmpty();
	}

	@Test
	@DisplayName("257-char patterns are rejected")
	void tooLongPatternRejected() {
		CreateKeyRequest request = new CreateKeyRequest(
				"owner", "name", 60, 1000, Set.of("x".repeat(257)), Set.of(),
				Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), null, null);

		assertThat(validator.validate(request)).isNotEmpty();
	}

	@Test
	@DisplayName("blank patterns are rejected on create and update")
	void blankPatternsRejected() {
		CreateKeyRequest create = new CreateKeyRequest(
				"owner", "name", 60, 1000, Set.of("  "), Set.of(),
				Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), null, null);
		assertThat(validator.validate(create)).isNotEmpty();

		UpdateKeyRequest update = new UpdateKeyRequest(
				null, null, null, null, null, Set.of(""), null,
				null, null, null, null, null, null, null);
		assertThat(validator.validate(update)).isNotEmpty();
	}

	@Test
	@DisplayName("null sets default to empty without violations")
	void nullSetsDefault() {
		CreateKeyRequest request = new CreateKeyRequest(
				"owner", "name", 60, 1000, null, null, null, null, null, null, null, null, null, null);

		assertThat(validator.validate(request)).isEmpty();
		assertThat(request.allowedModels()).isEmpty();
		assertThat(request.deniedPrompts()).isEmpty();
	}

	@Test
	@DisplayName("64 valid patterns pass")
	void boundsAcceptValid() {
		CreateKeyRequest request = new CreateKeyRequest(
				"owner", "name", 60, 1000, patterns(64, "model-"), Set.of("openai"),
				Set.of("postgres__*"), Set.of(), Set.of("postgres://*"), Set.of(),
				Set.of("server__review_*"), Set.of(), null, null);

		Set<ConstraintViolation<CreateKeyRequest>> violations = validator.validate(request);
		assertThat(violations).isEmpty();
	}

	@Test
	@DisplayName("oversized bootstrap policy fails startup binding")
	void bootstrapBoundsFailFast() {
		Set<String> oversized = new HashSet<>();
		oversized.add("x".repeat(300));
		BootstrapKey key =
				new BootstrapKey(
						"owner", "name", "gw-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 5, 50,
						oversized, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
						Set.of(), Set.of(), null);

		assertThat(validator.validate(key)).isNotEmpty();
	}
}
