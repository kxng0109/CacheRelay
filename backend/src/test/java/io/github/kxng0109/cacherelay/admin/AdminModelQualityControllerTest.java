package io.github.kxng0109.cacherelay.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import io.github.kxng0109.cacherelay.admin.dto.QualityResponse;
import io.github.kxng0109.cacherelay.admin.dto.SetQualityRequest;
import io.github.kxng0109.cacherelay.ledger.ModelQualityCatalog;
import io.github.kxng0109.cacherelay.ledger.ModelQualityEntity;
import io.github.kxng0109.cacherelay.ledger.ModelQualityRepository;
import io.github.kxng0109.cacherelay.ledger.ModelQualityTier;

/**
 * Unit tests for {@link AdminModelQualityController}: tier curation writes,
 * validation, and unrating.
 */
@DisplayName("AdminModelQualityController")
class AdminModelQualityControllerTest {

	private ModelQualityRepository repository;

	private ModelQualityCatalog catalog;

	private AdminModelQualityController controller;

	@BeforeEach
	void setUp() {
		repository = mock(ModelQualityRepository.class);
		catalog = mock(ModelQualityCatalog.class);
		controller = new AdminModelQualityController(repository, catalog);
	}

	@Test
	@DisplayName("upserts the tier and invalidates the snapshot")
	void upsertsTier() {
		when(repository.save(any(ModelQualityEntity.class))).thenAnswer(call -> call.getArgument(0));

		ResponseEntity<QualityResponse> response = controller.setQuality(
				"gpt-5.6-luna", new SetQualityRequest(ModelQualityTier.FRONTIER, "AA-Index:72.3@2026-09-01"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().modelId()).isEqualTo("gpt-5.6-luna");
		assertThat(response.getBody().tier()).isEqualTo(ModelQualityTier.FRONTIER);
		assertThat(response.getBody().benchmarkRefs()).isEqualTo("AA-Index:72.3@2026-09-01");
		verify(repository).save(any(ModelQualityEntity.class));
		verify(catalog).invalidate();
	}

	@Test
	@DisplayName("blank benchmark refs persist as null")
	void blankRefsPersistNull() {
		when(repository.save(any(ModelQualityEntity.class))).thenAnswer(call -> call.getArgument(0));

		ResponseEntity<QualityResponse> response =
				controller.setQuality("m", new SetQualityRequest(ModelQualityTier.BUDGET, "  "));

		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().benchmarkRefs()).isNull();
	}

	@Test
	@DisplayName("rejects blank model ids and oversized refs")
	void rejectsBadInput() {
		assertThatThrownBy(() -> controller.setQuality("  ",
				new SetQualityRequest(ModelQualityTier.STANDARD, null)))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThatThrownBy(() -> controller.setQuality("m",
				new SetQualityRequest(ModelQualityTier.STANDARD, "x".repeat(2001))))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		verify(repository, never()).save(any(ModelQualityEntity.class));
	}

	@Test
	@DisplayName("rejects a null request and a null tier")
	void rejectsNullRequestAndTier() {
		assertThatThrownBy(() -> controller.setQuality("m", null))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThatThrownBy(() -> controller.setQuality("m", new SetQualityRequest(null, null)))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		verify(repository, never()).save(any(ModelQualityEntity.class));
	}

	@Test
	@DisplayName("persists null refs and rejects malformed ids and refs")
	void validatesIdsAndRefs() {
		when(repository.save(any(ModelQualityEntity.class))).thenAnswer(call -> call.getArgument(0));

		ResponseEntity<QualityResponse> stored =
				controller.setQuality("m", new SetQualityRequest(ModelQualityTier.STANDARD, null));
		assertThat(stored.getBody()).isNotNull();
		assertThat(stored.getBody().benchmarkRefs()).isNull();

		String tooLong = "m".repeat(129);
		assertThatThrownBy(() -> controller.setQuality(tooLong,
				new SetQualityRequest(ModelQualityTier.STANDARD, null)))
				.isInstanceOf(ResponseStatusException.class);
		assertThatThrownBy(() -> controller.setQuality(null,
				new SetQualityRequest(ModelQualityTier.STANDARD, null)))
				.isInstanceOf(ResponseStatusException.class);
		assertThatThrownBy(() -> controller.setQuality("m",
				new SetQualityRequest(ModelQualityTier.STANDARD, "<evil>")))
				.isInstanceOf(ResponseStatusException.class);
	}

	@Test
	@DisplayName("deletes the rating and invalidates the snapshot")
	void deletesRating() {
		when(repository.existsById("m")).thenReturn(true);

		ResponseEntity<Void> response = controller.deleteQuality("m");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		verify(repository).deleteById("m");
		verify(catalog).invalidate();
	}

	@Test
	@DisplayName("deleting an unrated model answers 404")
	void deleteUnknownAnswers404() {
		when(repository.existsById("nope")).thenReturn(false);

		assertThatThrownBy(() -> controller.deleteQuality("nope"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		verify(repository, never()).deleteById(any());
	}

	@Test
	@DisplayName("reads the current rating when present")
	void readsRating() {
		ModelQualityEntity entity = new ModelQualityEntity(
				"m", ModelQualityTier.STANDARD, null, Instant.now());
		when(repository.findById("m")).thenReturn(Optional.of(entity));

		ResponseEntity<QualityResponse> response = controller.getQuality("m");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().tier()).isEqualTo(ModelQualityTier.STANDARD);
	}

	@Test
	@DisplayName("reading an unrated model answers 404")
	void readUnknownAnswers404() {
		when(repository.findById("nope")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> controller.getQuality("nope"))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}
}
