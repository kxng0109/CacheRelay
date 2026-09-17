package io.github.kxng0109.cacherelay.proxy.embeddings;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Opt-in in-process ONNX embedder for the L2 semantic cache path.
 *
 * <p>Runs the configured ONNX model (e.g. the nomic-embed-text-v1 export) through ONNX Runtime with
 * DJL tokenizers: tokenize &rarr; forward pass &rarr; attention-masked mean pooling &rarr; L2
 * normalization. Measured 18&ndash;33&nbsp;ms steady on the local stack with no HTTP round-trip and
 * no iGPU wake penalty.</p>
 *
 * <p><b>Calibration warning:</b> local ONNX scores sit ~0.03 cosine below Ollama's GGUF output
 * (Pearson r=0.994, verified on the 57-pair eval, 2026-09-15). Enabling this embedder on an index
 * populated with Ollama-embedded vectors produces similarity mismatches: re-index and recalibrate
 * the similarity threshold (0.77 local &hArr; 0.80 Ollama) first.</p>
 *
 * <p>Construction fails fast when the configured model or tokenizer paths are missing, so a
 * misconfigured deployment never boots half-broken. {@link OrtSession#run} is thread-safe; the
 * cache path may call {@link #embed} concurrently.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "gateway.embeddings.local-onnx.enabled", havingValue = "true")
public class OnnxLocalEmbedder implements DisposableBean {

	private final OrtEnvironment environment;
	private final HuggingFaceTokenizer tokenizer;
	private final OrtSession session;
	private final Set<String> inputNames;
	private final int maxTokens;

	/**
	 * Creates the embedder from bound properties, failing fast on missing artifacts.
	 */
	public OnnxLocalEmbedder(OnnxLocalEmbeddingProperties properties) {
		this(properties, OrtEnvironment.getEnvironment());
	}

	/**
	 * Creates the embedder with an explicit ONNX environment (test-visible).
	 */
	OnnxLocalEmbedder(OnnxLocalEmbeddingProperties properties, OrtEnvironment environment) {
		this.environment = environment;
		this.maxTokens = properties.maxTokens();
		Path modelPath = requireArtifact(properties.modelPath(), "model");
		Path tokenizerPath = requireArtifact(properties.tokenizerPath(), "tokenizer");
		OrtSession createdSession = null;
		HuggingFaceTokenizer createdTokenizer = null;
		try (OrtSession.SessionOptions options = sessionOptions(properties.intraOpThreads())) {
			createdSession = environment.createSession(modelPath.toString(), options);
			this.inputNames = createdSession.getInputNames();
			createdTokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath, tokenizerOptions(properties.maxTokens()));
		} catch (Exception ex) {
			if (createdSession != null) {
				try {
					createdSession.close();
				} catch (Exception closeEx) {
					log.debug("ONNX session close ignored: {}", closeEx.getMessage());
				}
			}
			if (createdTokenizer != null) {
				createdTokenizer.close();
			}
			throw new IllegalStateException("Failed to initialize the local ONNX embedder: " + ex.getMessage(), ex);
		}
		this.session = createdSession;
		this.tokenizer = createdTokenizer;
		log.warn("Local ONNX embedder active (model={}, intraOpThreads={}, maxTokens={}). Local scores sit "
				         + "~0.03 cosine below Ollama GGUF (r=0.994): re-index and recalibrate the similarity "
				         + "threshold (0.77 local <=> 0.80 Ollama) before enabling on a populated index.",
				modelPath, properties.intraOpThreads(), properties.maxTokens());
	}

	private static Path requireArtifact(@Nullable String path, String kind) {
		if (path == null || path.isBlank()) {
			throw new IllegalStateException("gateway.embeddings.local-onnx." + kind + "-path is required when "
					                                    + "the local ONNX embedder is enabled");
		}
		Path resolved = Path.of(path);
		if (!Files.isRegularFile(resolved)) {
			throw new IllegalStateException("gateway.embeddings.local-onnx." + kind + "-path does not exist: " + path);
		}
		return resolved;
	}

	private static OrtSession.SessionOptions sessionOptions(int intraOpThreads) throws Exception {
		OrtSession.SessionOptions options = new OrtSession.SessionOptions();
		options.setIntraOpNumThreads(intraOpThreads);
		options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
		return options;
	}

	private static Map<String, String> tokenizerOptions(int maxTokens) {
		Map<String, String> options = new HashMap<>();
		options.put("truncation", "LONGEST_FIRST");
		options.put("maxLength", String.valueOf(maxTokens));
		return options;
	}

	/**
	 * Embeds one text into a unit-length vector via tokenize &rarr; forward pass &rarr; masked mean
	 * pooling &rarr; L2 normalization.
	 *
	 * @param text the prompt text to embed
	 * @return unit-length embedding vector
	 */
	public float[] embed(String text) throws Exception {
		Encoding encoding = tokenizer.encode(text);
		long[] ids = encoding.getIds();
		long[] mask = encoding.getAttentionMask();
		try (OnnxTensor idsTensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), new long[][]{ids});
		     OnnxTensor maskTensor = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), new long[][]{mask});
		     OnnxTensor typesTensor = inputNames.contains("token_type_ids")
				                              ? OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), new long[][]{new long[ids.length]})
				                              : null;
		     OrtSession.Result result = run(idsTensor, maskTensor, typesTensor)) {
			float[][] hiddenState = extractHiddenState(result);
			return poolAndNormalize(hiddenState, mask);
		}
	}

	private OrtSession.Result run(OnnxTensor idsTensor, OnnxTensor maskTensor, @Nullable OnnxTensor typesTensor)
			throws Exception {
		Map<String, OnnxTensor> feed = new HashMap<>();
		feed.put("input_ids", idsTensor);
		feed.put("attention_mask", maskTensor);
		if (typesTensor != null) {
			feed.put("token_type_ids", typesTensor);
		}
		return session.run(feed);
	}

	static float[][] extractHiddenState(OrtSession.Result result) {
		Object first = result.get(0);
		if (first == null) {
			throw new IllegalStateException("ONNX model returned no output tensor");
		}
		if (!(first instanceof OnnxValue onnxValue)) {
			throw new IllegalStateException("Unexpected ONNX output type: " + first.getClass().getSimpleName());
		}
		Object value;
		try {
			value = onnxValue.getValue();
		} catch (Exception ex) {
			throw new IllegalStateException("Failed to read the ONNX output tensor: " + ex.getMessage(), ex);
		}
		if (value instanceof float[][][] tensor) {
			return tensor[0];
		}
		throw new IllegalStateException("Unexpected ONNX output shape: " + value.getClass().getSimpleName());
	}

	/**
	 * Attention-masked mean pooling followed by L2 normalization. Masked-out positions are excluded
	 * from the mean; the result is scaled to unit length (zero-norm guarded: an all-masked input
	 * yields the zero vector rather than NaN).
	 *
	 * @param hiddenState   per-token hidden states, shape {@code [seq][dim]}
	 * @param attentionMask 1 for attended positions, 0 for padding
	 * @return pooled, unit-length vector
	 */
	static float[] poolAndNormalize(float[][] hiddenState, long[] attentionMask) {
		int seq = Math.min(hiddenState.length, attentionMask.length);
		if (seq == 0 || hiddenState[0].length == 0) {
			return new float[0];
		}
		int dim = hiddenState[0].length;
		double[] pooled = new double[dim];
		double maskSum = 0.0;
		for (int i = 0; i < seq; i++) {
			if (attentionMask[i] == 0) {
				continue;
			}
			maskSum += 1.0;
			for (int d = 0; d < dim; d++) {
				pooled[d] += hiddenState[i][d];
			}
		}
		float[] result = new float[dim];
		if (maskSum == 0.0) {
			return result;
		}
		double norm = 0.0;
		for (int d = 0; d < dim; d++) {
			pooled[d] /= maskSum;
			norm += pooled[d] * pooled[d];
		}
		norm = Math.sqrt(norm);
		if (norm == 0.0) {
			return result;
		}
		for (int d = 0; d < dim; d++) {
			result[d] = (float) (pooled[d] / norm);
		}
		return result;
	}

	@Override
	public void destroy() {
		closeQuietly();
	}

	private void closeQuietly() {
		if (session != null) {
			try {
				session.close();
			} catch (Exception ex) {
				log.debug("ONNX session close ignored: {}", ex.getMessage());
			}
		}
		if (tokenizer != null) {
			tokenizer.close();
		}
	}
}
