package io.github.kxng0109.cacherelay.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.github.kxng0109.cacherelay.admin.dto.ModelCatalogEntryResponse;
import io.github.kxng0109.cacherelay.admin.dto.ModelCatalogResponse;
import io.github.kxng0109.cacherelay.ledger.ModelPriceCatalog;
import io.github.kxng0109.cacherelay.ledger.ModelPricingEntry;
import io.github.kxng0109.cacherelay.ledger.ModelQualityCatalog;
import io.github.kxng0109.cacherelay.ledger.ModelQualityEntry;
import io.github.kxng0109.cacherelay.ledger.ModelQualityTier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@link AdminModelCatalogController}: envelope mapping,
 * parameter passthrough, and validation of the search bounds.
 */
@DisplayName("AdminModelCatalogController")
class AdminModelCatalogControllerTest {

	private ModelPriceCatalog catalog;

	private AdminModelCatalogController controller;

	@BeforeEach
	void setUp() {
		catalog = mock(ModelPriceCatalog.class);
		controller = new AdminModelCatalogController(catalog);
	}

	@Test
	@DisplayName("maps catalog entries into the response envelope")
	void mapsCatalogEntries() {
		when(catalog.search(null, null, 50)).thenReturn(List.of(
				new ModelPricingEntry("gpt-5.6-luna", "openai", "chat",
						new BigDecimal("0.0000025"), new BigDecimal("0.00001"),
						new BigDecimal("0.00000025"), new BigDecimal("0.000003125"),
						128000L, 16384L)));

		ResponseEntity<ModelCatalogResponse> response = controller.searchCatalog(null, null, 50);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().models()).hasSize(1);
		ModelCatalogEntryResponse entry = response.getBody().models().getFirst();
		assertThat(entry.modelId()).isEqualTo("gpt-5.6-luna");
		assertThat(entry.provider()).isEqualTo("openai");
		assertThat(entry.maxInputTokens()).isEqualTo(128000L);
		assertThat(entry.maxOutputTokens()).isEqualTo(16384L);
		assertThat(entry.cacheReadInputTokenCost()).isEqualByComparingTo("0.00000025");
	}

	@Test
	@DisplayName("passes provider and query filters through to the catalog")
	void passesFiltersThrough() {
		when(catalog.search("openai", "gpt", 10)).thenReturn(List.of());

		controller.searchCatalog("openai", "gpt", 10);

		verify(catalog).search("openai", "gpt", 10);
	}

	@Test
	@DisplayName("annotates entries with curated quality tiers")
	void annotatesQualityTiers() {
		ModelQualityCatalog quality = mock(ModelQualityCatalog.class);
		assertAnnotatedWithQuality(quality);
	}

	private void assertAnnotatedWithQuality(ModelQualityCatalog quality) {
		when(catalog.search(null, null, 50)).thenReturn(List.of(
				new ModelPricingEntry("gpt-5.6-luna", "openai", "chat",
						new BigDecimal("0.0000025"), new BigDecimal("0.00001"),
						null, null, null, null),
				new ModelPricingEntry("unrated-model", "openai", "chat",
						new BigDecimal("0.0000025"), new BigDecimal("0.00001"),
						null, null, null, null)));
		when(quality.qualityOf("gpt-5.6-luna")).thenReturn(Optional.of(
				new ModelQualityEntry("gpt-5.6-luna", ModelQualityTier.FRONTIER,
						"AA-Index:72.3@2026-09-01", Instant.now())));
		when(quality.qualityOf("unrated-model")).thenReturn(Optional.empty());
		AdminModelCatalogController aware = new AdminModelCatalogController(catalog, quality);

		ResponseEntity<ModelCatalogResponse> response = aware.searchCatalog(null, null, 50);

		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().models()).hasSize(2);
		ModelCatalogEntryResponse rated = response.getBody().models().getFirst();
		assertThat(rated.qualityTier()).isEqualTo("FRONTIER");
		assertThat(rated.benchmarkRefs()).isEqualTo("AA-Index:72.3@2026-09-01");
		ModelCatalogEntryResponse unrated = response.getBody().models().get(1);
		assertThat(unrated.qualityTier()).isNull();
		assertThat(unrated.benchmarkRefs()).isNull();
	}

	@Test
	@DisplayName("accepts the limit boundaries")
	void acceptsLimitBoundaries() {
		when(catalog.search(null, null, 1)).thenReturn(List.of());
		when(catalog.search(null, null, 200)).thenReturn(List.of());

		ResponseEntity<ModelCatalogResponse> minimum = controller.searchCatalog(null, null, 1);
		ResponseEntity<ModelCatalogResponse> maximum = controller.searchCatalog(null, null, 200);

		assertThat(minimum.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(maximum.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(maximum.getBody()).isNotNull();
		assertThat(maximum.getBody().models()).isEmpty();
	}

	@Test
	@DisplayName("rejects a limit outside 1 through 200 with 400")
	void rejectsInvalidLimit() {
		assertThatThrownBy(() -> controller.searchCatalog(null, null, 0))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThatThrownBy(() -> controller.searchCatalog(null, null, 201))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("rejects an oversized provider filter with 400")
	void rejectsOversizedProvider() {
		assertThatThrownBy(() -> controller.searchCatalog("p".repeat(65), null, 50))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	@DisplayName("rejects an oversized query with 400")
	void rejectsOversizedQuery() {
		assertThatThrownBy(() -> controller.searchCatalog(null, "q".repeat(129), 50))
				.isInstanceOf(ResponseStatusException.class)
				.extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}
}
