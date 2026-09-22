package io.github.kxng0109.cacherelay.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ModelQualityCatalog}: curated tier reads served from a
 * short-lived snapshot, misses reading as unrated, and invalidation reloading.
 */
@DisplayName("ModelQualityCatalog")
@ExtendWith(MockitoExtension.class)
class ModelQualityCatalogTest {

	@Mock
	private ModelQualityRepository repository;

	@Test
	@DisplayName("returns the curated tier and benchmark refs for a rated model")
	void returnsCuratedTier() {
		ModelQualityEntity entity = new ModelQualityEntity(
				"gpt-5.6-luna", ModelQualityTier.FRONTIER, "AA-Index:72.3@2026-09-01", Instant.now());
		when(repository.findAll()).thenReturn(List.of(entity));
		ModelQualityCatalog catalog = new ModelQualityCatalog(repository);

		Optional<ModelQualityEntry> found = catalog.qualityOf("gpt-5.6-luna");

		assertThat(found).isPresent();
		assertThat(found.get().tier()).isEqualTo(ModelQualityTier.FRONTIER);
		assertThat(found.get().benchmarkRefs()).isEqualTo("AA-Index:72.3@2026-09-01");
	}

	@Test
	@DisplayName("unrated models read as empty, never as a default tier")
	void unratedReadsEmpty() {
		when(repository.findAll()).thenReturn(List.of());
		ModelQualityCatalog catalog = new ModelQualityCatalog(repository);

		assertThat(catalog.qualityOf("unknown-model")).isEmpty();
	}

	@Test
	@DisplayName("duplicate rows resolve to the last write")
	void duplicateRowsResolveLast() {
		ModelQualityEntity first = new ModelQualityEntity(
				"m", ModelQualityTier.BUDGET, null, Instant.now());
		ModelQualityEntity second = new ModelQualityEntity(
				"m", ModelQualityTier.FRONTIER, null, Instant.now());
		when(repository.findAll()).thenReturn(List.of(first, second));
		ModelQualityCatalog catalog = new ModelQualityCatalog(repository);

		assertThat(catalog.qualityOf("m")).isPresent();
		assertThat(catalog.qualityOf("m").get().tier()).isEqualTo(ModelQualityTier.FRONTIER);
	}

	@Test
	@DisplayName("blank model ids read as empty without touching the repository")
	void blankReadsEmpty() {
		ModelQualityCatalog catalog = new ModelQualityCatalog(repository);

		assertThat(catalog.qualityOf(null)).isEmpty();
		assertThat(catalog.qualityOf("   ")).isEmpty();
	}

	@Test
	@DisplayName("invalidation reloads curated tiers")
	void invalidationReloads() {
		when(repository.findAll()).thenReturn(List.of());
		ModelQualityCatalog catalog = new ModelQualityCatalog(repository);
		assertThat(catalog.qualityOf("gpt-5.6-luna")).isEmpty();

		ModelQualityEntity entity = new ModelQualityEntity(
				"gpt-5.6-luna", ModelQualityTier.BUDGET, null, Instant.now());
		when(repository.findAll()).thenReturn(List.of(entity));
		catalog.invalidate();

		Optional<ModelQualityEntry> found = catalog.qualityOf("gpt-5.6-luna");
		assertThat(found).isPresent();
		assertThat(found.get().tier()).isEqualTo(ModelQualityTier.BUDGET);
		assertThat(found.get().benchmarkRefs()).isNull();
	}
}
