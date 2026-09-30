package io.github.kxng0109.cacherelay.ledger;

import java.util.Map;
import java.util.OptionalInt;

/**
 * Curated default output dimensions for well-known embedding models.
 *
 * <p>Pricing data carries no vector sizes, and probing each model live would
 * turn a catalog read into provider calls — so curated constants fill the
 * gap, exactly like the quality tiers on the same response. A miss reads as
 * unknown (empty), never as a default dimension; chat-only models are
 * therefore always unknown. Values are vendor defaults: models with
 * Matryoshka-style truncation (OpenAI 3-series, mxbai-embed-large) can serve
 * shorter vectors on request, and bge-m3 additionally emits sparse and
 * multi-vector outputs — the number here is the default dense width.</p>
 */
public final class ModelEmbeddingDimensions {

	/**
	 * Vendor-default dense widths keyed by exact model id.
	 */
	private static final Map<String, Integer> DIMENSIONS = Map.ofEntries(
			Map.entry("text-embedding-3-small", 1536),
			Map.entry("text-embedding-3-large", 3072),
			Map.entry("text-embedding-ada-002", 1536),
			Map.entry("embed-english-v3.0", 1024),
			Map.entry("embed-multilingual-v3.0", 1024),
			Map.entry("nomic-embed-text", 768),
			Map.entry("nomic-embed-text-v1", 768),
			Map.entry("mxbai-embed-large-v1", 1024),
			Map.entry("bge-m3", 1024),
			Map.entry("voyage-3-lite", 512));

	private ModelEmbeddingDimensions() {
	}

	/**
	 * Looks up the curated default dimension width of a model id.
	 *
	 * @param modelId the model id to look up, possibly {@code null} or blank
	 * @return the default width, or empty when the model is unknown or not an embedding model
	 */
	public static OptionalInt dimensionsOf(String modelId) {
		if (modelId == null || modelId.isBlank()) {
			return OptionalInt.empty();
		}
		Integer dims = DIMENSIONS.get(modelId);
		return dims == null ? OptionalInt.empty() : OptionalInt.of(dims);
	}
}
