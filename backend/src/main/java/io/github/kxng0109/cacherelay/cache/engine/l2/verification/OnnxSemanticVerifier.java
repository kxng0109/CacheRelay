package io.github.kxng0109.cacherelay.cache.engine.l2.verification;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import io.github.kxng0109.cacherelay.cache.config.CacheRelayCacheProperties;
import io.github.kxng0109.cacherelay.cache.config.CacheRelayCacheProperties.OnnxVerifierProperties;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * 2nd-stage L2 semantic verification engine executing an ONNX cross-encoder model.
 *
 * <p>Validates semantic candidate matches retrieved from vector search using a sequence classification
 * model. Runs with bounded concurrency via a fail-closed semaphore bulkhead to protect baseline CPU
 * resources under surge conditions. Employs single-threaded sequential execution
 * ({@code intra_op_num_threads=1}, {@code inter_op_num_threads=1}, {@code ORT_SEQUENTIAL},
 * {@code session.intra_op.allow_spinning=0}).</p>
 */
@Component
public class OnnxSemanticVerifier implements DisposableBean, AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(OnnxSemanticVerifier.class);

	private final boolean enabled;
	private final double threshold;
	private final Semaphore bulkhead;
	private final @Nullable OrtEnvironment environment;
	private final @Nullable OrtSession session;
	private final @Nullable HuggingFaceTokenizer tokenizer;
	private final Set<String> inputNames;
	private final CpuAccelerationTier effectiveTier;

	/**
	 * Creates the verifier from bound properties and host CPU feature detection.
	 *
	 * @param properties         cache configuration properties
	 * @param cpuFeatureDetector host CPU hardware ISA detector
	 */
	@Autowired
	public OnnxSemanticVerifier(
			CacheRelayCacheProperties properties,
			HostCpuFeatureDetector cpuFeatureDetector
	) {
		OnnxVerifierProperties verifierProps = properties.getSemantic().getOnnxVerifier();
		this.threshold = verifierProps.getThreshold();
		int maxConcurrency = verifierProps.getMaxConcurrency() > 0 ? verifierProps.getMaxConcurrency() : 4;
		this.bulkhead = new Semaphore(maxConcurrency);

		CpuAccelerationTier targetTier = verifierProps.getTier();
		if (targetTier == null || targetTier == CpuAccelerationTier.AUTO) {
			this.effectiveTier = cpuFeatureDetector.detectTier();
		} else {
			this.effectiveTier = targetTier;
		}

		if (!verifierProps.isEnabled()) {
			this.enabled = false;
			this.environment = null;
			this.session = null;
			this.tokenizer = null;
			this.inputNames = Set.of();
			log.info("ONNX semantic verifier disabled by configuration");
			return;
		}

		String modelPathStr = verifierProps.getModelPath();
		String tokenizerPathStr = verifierProps.getTokenizerPath();
		if (modelPathStr == null || modelPathStr.isBlank()
				|| tokenizerPathStr == null || tokenizerPathStr.isBlank()) {
			this.enabled = false;
			this.environment = null;
			this.session = null;
			this.tokenizer = null;
			this.inputNames = Set.of();
			log.warn("ONNX semantic verifier enabled but model-path or tokenizer-path unconfigured; "
					+ "degrading to deterministic fast-filter only");
			return;
		}

		Path resolvedModel = resolveModelPath(modelPathStr, this.effectiveTier);
		Path resolvedTokenizer = resolveTokenizerPath(tokenizerPathStr);

		if (!Files.exists(resolvedModel) || !Files.exists(resolvedTokenizer)) {
			this.enabled = false;
			this.environment = null;
			this.session = null;
			this.tokenizer = null;
			this.inputNames = Set.of();
			log.warn("ONNX semantic verifier artifacts missing (model={}, tokenizer={}); "
					+ "degrading to deterministic fast-filter only", resolvedModel, resolvedTokenizer);
			return;
		}

		OrtEnvironment env = null;
		OrtSession sess = null;
		HuggingFaceTokenizer tok = null;
		Set<String> inputs = Set.of();
		boolean initSuccess = false;

		try {
			env = OrtEnvironment.getEnvironment();
			try (OrtSession.SessionOptions options = createSessionOptions()) {
				sess = env.createSession(resolvedModel.toString(), options);
				inputs = sess.getInputNames();
			}
			Map<String, String> tokOptions = Map.of("truncation", "LONGEST_FIRST", "maxLength", "512");
			tok = HuggingFaceTokenizer.newInstance(resolvedTokenizer, tokOptions);
			initSuccess = true;
			log.info("ONNX semantic verifier initialized (model={}, tier={}, maxConcurrency={}, threshold={})",
					resolvedModel, this.effectiveTier, maxConcurrency, this.threshold);
		} catch (Exception ex) {
			log.warn("Failed initializing ONNX semantic verifier: {}; degrading to deterministic fast-filter",
					ex.getMessage());
		}

		if (initSuccess) {
			this.enabled = true;
			this.environment = env;
			this.session = sess;
			this.tokenizer = tok;
			this.inputNames = inputs;
		} else {
			this.enabled = false;
			this.environment = null;
			if (sess != null) {
				try {
					sess.close();
				} catch (Exception ex) {
					log.debug("Session close ignored during failed init: {}", ex.getMessage());
				}
			}
			if (tok != null) {
				try {
					tok.close();
				} catch (Exception ex) {
					log.debug("Tokenizer close ignored during failed init: {}", ex.getMessage());
				}
			}
			this.session = null;
			this.tokenizer = null;
			this.inputNames = Set.of();
		}
	}

	/**
	 * Test-visible constructor allowing injection of mock ONNX components.
	 */
	OnnxSemanticVerifier(
			CacheRelayCacheProperties properties,
			HostCpuFeatureDetector cpuFeatureDetector,
			@Nullable OrtEnvironment environment,
			@Nullable OrtSession session,
			@Nullable HuggingFaceTokenizer tokenizer
	) {
		OnnxVerifierProperties verifierProps = properties.getSemantic().getOnnxVerifier();
		this.enabled = verifierProps.isEnabled();
		this.threshold = verifierProps.getThreshold();
		int maxConcurrency = verifierProps.getMaxConcurrency() > 0 ? verifierProps.getMaxConcurrency() : 4;
		this.bulkhead = new Semaphore(maxConcurrency);
		this.effectiveTier = verifierProps.getTier() != null && verifierProps.getTier() != CpuAccelerationTier.AUTO
				? verifierProps.getTier()
				: cpuFeatureDetector.detectTier();
		this.environment = environment;
		this.session = session;
		this.tokenizer = tokenizer;
		this.inputNames = session != null ? session.getInputNames() : Set.of();
	}

	/**
	 * Indicates whether the ONNX verifier is active and ready to perform cross-encoder evaluations.
	 *
	 * @return true if enabled and initialized
	 */
	public boolean isEnabled() {
		return enabled;
	}

	/**
	 * Returns the effective acceleration tier selected for inference.
	 *
	 * @return effective CPU acceleration tier
	 */
	public CpuAccelerationTier getEffectiveTier() {
		return effectiveTier;
	}

	/**
	 * Verifies whether incoming user prompt and cached prompt are semantically equivalent.
	 *
	 * @param queryA incoming user prompt
	 * @param queryB cached candidate prompt
	 * @return true if semantic equivalence probability &ge; threshold; false if below or on bulkhead saturation
	 */
	public boolean verify(String queryA, String queryB) {
		if (!enabled || session == null || tokenizer == null || environment == null) {
			return true;
		}

		if (!bulkhead.tryAcquire()) {
			log.warn("ONNX verifier bulkhead saturated; failing closed to cache miss");
			return false;
		}

		try {
			return executeInference(queryA, queryB);
		} catch (Exception ex) {
			log.warn("ONNX semantic inference failed: {}; failing closed", ex.getMessage());
			return false;
		} finally {
			bulkhead.release();
		}
	}

	private static final int DEFAULT_SEQ_LENGTH = 64;

	static long[] padOrTruncate(long[] array, int targetLen) {
		if (array.length == targetLen) {
			return array;
		}
		long[] result = new long[targetLen];
		System.arraycopy(array, 0, result, 0, Math.min(array.length, targetLen));
		return result;
	}

	private boolean executeInference(String queryA, String queryB) throws Exception {
		if (tokenizer == null || session == null || environment == null) {
			return false;
		}

		Encoding encoding = tokenizer.encode(queryA, queryB);
		long[] rawIds = encoding.getIds();
		long[] rawMask = encoding.getAttentionMask();
		long[] rawTypeIds = encoding.getTypeIds();

		long[] ids = padOrTruncate(rawIds, DEFAULT_SEQ_LENGTH);
		long[] mask = padOrTruncate(rawMask, DEFAULT_SEQ_LENGTH);
		long[] types = padOrTruncate(rawTypeIds != null && rawTypeIds.length == rawIds.length ? rawTypeIds : new long[rawIds.length], DEFAULT_SEQ_LENGTH);

		Map<String, OnnxTensor> feed = new HashMap<>();
		OnnxTensor idsTensor = null;
		OnnxTensor maskTensor = null;
		OnnxTensor typesTensor = null;

		try {
			OrtEnvironment tensorEnv = OrtEnvironment.getEnvironment();
			if (inputNames.contains("input_ids")) {
				idsTensor = OnnxTensor.createTensor(tensorEnv, new long[][]{ids});
				feed.put("input_ids", idsTensor);
			}
			if (inputNames.contains("attention_mask")) {
				maskTensor = OnnxTensor.createTensor(tensorEnv, new long[][]{mask});
				feed.put("attention_mask", maskTensor);
			}
			if (inputNames.contains("token_type_ids")) {
				typesTensor = OnnxTensor.createTensor(tensorEnv, new long[][]{types});
				feed.put("token_type_ids", typesTensor);
			}

			try (OrtSession.Result result = session.run(feed)) {
				float[][] logits = extractLogits(result);
				if (logits.length == 0 || logits[0].length < 2) {
					log.warn("ONNX model returned invalid logits shape; failing closed");
					return false;
				}
				float l0 = logits[0][0];
				float l1 = logits[0][1];
				// Index 1 is the match/paraphrase class (QQP/PAWS standard: 1=duplicate, 0=not duplicate)
				double matchProbability = computeSoftmax0(l1, l0);
				log.debug("ONNX semantic verifier score: matchProb={}, threshold={}", matchProbability, threshold);
				return matchProbability >= threshold;
			}
		} finally {
			if (idsTensor != null) {
				idsTensor.close();
			}
			if (maskTensor != null) {
				maskTensor.close();
			}
			if (typesTensor != null) {
				typesTensor.close();
			}
		}
	}

	static float[][] extractLogits(OrtSession.Result result) {
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
			throw new IllegalStateException("Failed reading ONNX output: " + ex.getMessage(), ex);
		}
		if (value instanceof float[][] matrix) {
			return matrix;
		} else if (value instanceof float[] vector) {
			return new float[][]{vector};
		} else if (value instanceof float[][][] tensor && tensor.length > 0) {
			return tensor[0];
		}
		throw new IllegalStateException("Unexpected ONNX output shape: " + value.getClass().getName());
	}

	/**
	 * Computes 2-way Softmax for index 0 (entailment/match) over logits [l0, l1].
	 *
	 * @param l0 logit for index 0 (entailment / match)
	 * @param l1 logit for index 1 (contradiction / mismatch)
	 * @return probability of index 0 in range [0.0, 1.0]
	 */
	public static double computeSoftmax0(float l0, float l1) {
		double max = Math.max(l0, l1);
		double exp0 = Math.exp(l0 - max);
		double exp1 = Math.exp(l1 - max);
		return exp0 / (exp0 + exp1);
	}

	static Path resolveModelPath(String configuredPath, CpuAccelerationTier tier) {
		Path base = Path.of(configuredPath);
		if (Files.isDirectory(base)) {
			if (tier == CpuAccelerationTier.ACCELERATED_INT8) {
				Path int8Path = base.resolve("model_int8.onnx");
				if (Files.isRegularFile(int8Path)) {
					return int8Path;
				}
				Path quantPath = base.resolve("model_quantized.onnx");
				if (Files.isRegularFile(quantPath)) {
					return quantPath;
				}
			}
			Path fp32Path = base.resolve("model.onnx");
			if (Files.isRegularFile(fp32Path)) {
				return fp32Path;
			}
			return base;
		}

		if (tier == CpuAccelerationTier.ACCELERATED_INT8 && Files.isRegularFile(base)) {
			String fileName = base.getFileName().toString();
			if (!fileName.contains("int8") && !fileName.contains("quantized")) {
				Path parent = base.getParent();
				if (parent != null) {
					Path int8Candidate = parent.resolve(fileName.replace(".onnx", "_int8.onnx"));
					if (Files.isRegularFile(int8Candidate)) {
						return int8Candidate;
					}
					Path quantCandidate = parent.resolve(fileName.replace(".onnx", "_quantized.onnx"));
					if (Files.isRegularFile(quantCandidate)) {
						return quantCandidate;
					}
				}
			}
		}

		return base;
	}

	static Path resolveTokenizerPath(String configuredPath) {
		Path base = Path.of(configuredPath);
		if (Files.isDirectory(base)) {
			Path candidate = base.resolve("tokenizer.json");
			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
		}
		return base;
	}

	private static OrtSession.SessionOptions createSessionOptions() throws Exception {
		OrtSession.SessionOptions options = new OrtSession.SessionOptions();
		options.setIntraOpNumThreads(1);
		options.setInterOpNumThreads(1);
		options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
		options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
		options.addConfigEntry("session.intra_op.allow_spinning", "0");
		return options;
	}

	@Override
	public void destroy() {
		close();
	}

	@Override
	public void close() {
		if (session != null) {
			try {
				session.close();
			} catch (Exception ex) {
				log.debug("ONNX session close ignored: {}", ex.getMessage());
			}
		}
		if (tokenizer != null) {
			try {
				tokenizer.close();
			} catch (Exception ex) {
				log.debug("Tokenizer close ignored: {}", ex.getMessage());
			}
		}
	}
}
