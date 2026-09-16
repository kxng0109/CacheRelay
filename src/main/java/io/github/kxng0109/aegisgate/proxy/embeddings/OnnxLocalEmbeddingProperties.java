package io.github.kxng0109.aegisgate.proxy.embeddings;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Opt-in in-process ONNX embedder for the L2 semantic cache path, bound from
 * {@code gateway.embeddings.local-onnx.*}.
 *
 * <p>When enabled, semantic-cache prompt embeddings run in-process (ONNX Runtime + DJL tokenizers)
 * instead of the upstream HTTP adapters &mdash; measured 18&ndash;33&nbsp;ms steady with no HTTP
 * round-trip and no iGPU wake penalty. The client-facing {@code /v1/embeddings} proxy always keeps
 * the HTTP adapters (contract preserved).</p>
 *
 * @param enabled        whether the local embedder runs (default {@code false})
 * @param modelPath      absolute path to the ONNX model file (e.g. nomic-embed-text-v1 export)
 * @param tokenizerPath  absolute path to the matching HuggingFace {@code tokenizer.json}
 * @param intraOpThreads ONNX Runtime intra-op thread count
 * @param maxTokens      maximum tokenized sequence length (truncation)
 */
@ConfigurationProperties("gateway.embeddings.local-onnx")
public record OnnxLocalEmbeddingProperties(
		@DefaultValue("false") boolean enabled,
		String modelPath,
		String tokenizerPath,
		@DefaultValue("2") int intraOpThreads,
		@DefaultValue("2048") int maxTokens
) {

	/**
	 * The documented defaults, mirroring the {@link DefaultValue} annotations.
	 */
	public static final OnnxLocalEmbeddingProperties DEFAULTS =
			new OnnxLocalEmbeddingProperties(false, null, null, 2, 2_048);
}
