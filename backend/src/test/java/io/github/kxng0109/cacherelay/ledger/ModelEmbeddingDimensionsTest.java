package io.github.kxng0109.cacherelay.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the curated embedding dimensions: vendor-default widths for
 * known models, empty for unknown, chat-only, blank, and null ids.
 */
@DisplayName("ModelEmbeddingDimensions")
class ModelEmbeddingDimensionsTest {

	@Test
	@DisplayName("known embedding models resolve their vendor-default widths")
	void knownModelsResolve() {
		assertThat(ModelEmbeddingDimensions.dimensionsOf("text-embedding-3-small"))
				.hasValue(1536);
		assertThat(ModelEmbeddingDimensions.dimensionsOf("text-embedding-3-large"))
				.hasValue(3072);
		assertThat(ModelEmbeddingDimensions.dimensionsOf("text-embedding-ada-002"))
				.hasValue(1536);
		assertThat(ModelEmbeddingDimensions.dimensionsOf("embed-english-v3.0"))
				.hasValue(1024);
		assertThat(ModelEmbeddingDimensions.dimensionsOf("nomic-embed-text"))
				.hasValue(768);
		assertThat(ModelEmbeddingDimensions.dimensionsOf("mxbai-embed-large-v1"))
				.hasValue(1024);
		assertThat(ModelEmbeddingDimensions.dimensionsOf("bge-m3"))
				.hasValue(1024);
		assertThat(ModelEmbeddingDimensions.dimensionsOf("voyage-3-lite"))
				.hasValue(512);
	}

	@Test
	@DisplayName("unknown, chat-only, blank, and null ids resolve empty, never a default")
	@SuppressWarnings("DataFlowIssue")
	void unknownResolvesEmpty() {
		assertThat(ModelEmbeddingDimensions.dimensionsOf("gpt-5.6-luna")).isEmpty();
		assertThat(ModelEmbeddingDimensions.dimensionsOf("no-such-model")).isEmpty();
		assertThat(ModelEmbeddingDimensions.dimensionsOf("")).isEmpty();
		assertThat(ModelEmbeddingDimensions.dimensionsOf("   ")).isEmpty();
		assertThat(ModelEmbeddingDimensions.dimensionsOf(null)).isEmpty();
	}
}
