package io.github.kxng0109.cacherelay.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ModelPriceCatalog#search(String, String, int)}: provider
 * and query filtering, ordering, limit handling, and snapshot reuse.
 */
@DisplayName("ModelPriceCatalog.search")
class ModelPriceCatalogSearchTest {

	private ModelPricingRepository repository;

	private ModelPriceCatalog catalog;

	@BeforeEach
	void setUp() {
		repository = mock(ModelPricingRepository.class);
		when(repository.findAll()).thenReturn(List.of(
				entity("gpt-5.6-luna", "openai", "0.0000025", "0.00001", 128000L, 16384L),
				entity("gpt-5.6-luna", "azure", "0.0000025", "0.00001", 128000L, 16384L),
				entity("llama-3.3-70b", "together_ai", "0.0000009", "0.0000009", 131072L, null),
				entity("llama-3.3-70b-versatile", "groq", "0.00000059", "0.00000079", 131072L, 32768L)));
		catalog = new ModelPriceCatalog(repository);
	}

	@Test
	@DisplayName("lists every model ordered by model id and provider")
	void listsAllOrdered() {
		List<ModelPricingEntry> results = catalog.search(null, null, 50);

		assertThat(results).extracting(ModelPricingEntry::modelId, ModelPricingEntry::provider)
				.containsExactly(
						Tuple.tuple("gpt-5.6-luna", "azure"),
						Tuple.tuple("gpt-5.6-luna", "openai"),
						Tuple.tuple("llama-3.3-70b", "together_ai"),
						Tuple.tuple("llama-3.3-70b-versatile", "groq"));
	}

	@Test
	@DisplayName("filters by provider ignoring case")
	void filtersByProvider() {
		List<ModelPricingEntry> results = catalog.search("TOGETHER_AI", null, 50);

		assertThat(results).extracting(ModelPricingEntry::modelId)
				.containsExactly("llama-3.3-70b");
	}

	@Test
	@DisplayName("filters by model id substring ignoring case")
	void filtersByQuery() {
		List<ModelPricingEntry> results = catalog.search(null, "LLAMA", 50);

		assertThat(results).extracting(ModelPricingEntry::modelId)
				.containsExactly("llama-3.3-70b", "llama-3.3-70b-versatile");
	}

	@Test
	@DisplayName("combines provider and query filters")
	void combinesFilters() {
		List<ModelPricingEntry> results = catalog.search("groq", "llama", 50);

		assertThat(results).extracting(ModelPricingEntry::modelId)
				.containsExactly("llama-3.3-70b-versatile");
	}

	@Test
	@DisplayName("blank filters are ignored")
	void blankFiltersAreIgnored() {
		assertThat(catalog.search("  ", "  ", 50)).hasSize(4);
	}

	@Test
	@DisplayName("clamps the limit defensively")
	void clampsLimit() {
		assertThat(catalog.search(null, null, 1))
				.extracting(ModelPricingEntry::modelId)
				.containsExactly("gpt-5.6-luna");
		assertThat(catalog.search(null, null, 0)).hasSize(1);
		assertThat(catalog.search(null, null, 5000)).hasSize(4);
	}

	@Test
	@DisplayName("reuses the cached snapshot across searches")
	void reusesCachedSnapshot() {
		catalog.search(null, null, 50);
		catalog.search("openai", "gpt", 50);

		verify(repository, times(1)).findAll();
	}

	@Test
	@DisplayName("carries context window bounds through the value form")
	void carriesContextWindow() {
		List<ModelPricingEntry> results = catalog.search("together_ai", null, 50);

		assertThat(results).singleElement().satisfies(entry -> {
			assertThat(entry.maxInputTokens()).isEqualTo(131072L);
			assertThat(entry.maxOutputTokens()).isNull();
		});
	}

	private static ModelPricingEntity entity(
			String modelId,
			String provider,
			String inputCost,
			String outputCost,
			Long maxInputTokens,
			Long maxOutputTokens
	) {
		return new ModelPricingEntity(
				modelId,
				provider,
				"chat",
				new BigDecimal(inputCost),
				new BigDecimal(outputCost),
				null,
				null,
				maxInputTokens,
				maxOutputTokens,
				"test",
				Instant.now());
	}
}
