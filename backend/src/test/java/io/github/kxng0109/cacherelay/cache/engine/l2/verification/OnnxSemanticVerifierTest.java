package io.github.kxng0109.cacherelay.cache.engine.l2.verification;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import io.github.kxng0109.cacherelay.cache.config.CacheRelayCacheProperties;
import io.github.kxng0109.cacherelay.cache.config.CacheRelayCacheProperties.OnnxVerifierProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OnnxSemanticVerifier 2nd-stage L2 semantic verification")
class OnnxSemanticVerifierTest {

	@TempDir
	Path tempDir;

	private final HostCpuFeatureDetector cpuDetector = mock(HostCpuFeatureDetector.class);

	@Test
	@DisplayName("computeSoftmax0 computes stable probabilities for entailment logit")
	void computeSoftmax0Stable() {
		// Equivalence: l0 == l1 -> 0.5
		assertThat(OnnxSemanticVerifier.computeSoftmax0(0.0f, 0.0f)).isCloseTo(0.5, offset(1e-5));
		assertThat(OnnxSemanticVerifier.computeSoftmax0(10.0f, 10.0f)).isCloseTo(0.5, offset(1e-5));

		// High entailment: l0 >> l1
		assertThat(OnnxSemanticVerifier.computeSoftmax0(5.0f, -5.0f)).isCloseTo(0.99995, offset(1e-4));

		// Strong contradiction: l0 << l1
		assertThat(OnnxSemanticVerifier.computeSoftmax0(-5.0f, 5.0f)).isCloseTo(0.000045, offset(1e-4));

		// Extreme numbers do not overflow to NaN
		assertThat(OnnxSemanticVerifier.computeSoftmax0(1000.0f, 1000.0f)).isCloseTo(0.5, offset(1e-5));
	}

	@Test
	@DisplayName("verify returns true when entailment probability meets or exceeds threshold")
	void verifyPassesWhenScoreAboveThreshold() throws Exception {
		CacheRelayCacheProperties properties = createProperties(true, 0.90, 4);
		OrtEnvironment environment = mock(OrtEnvironment.class);
		OrtSession session = mock(OrtSession.class);
		HuggingFaceTokenizer tokenizer = mock(HuggingFaceTokenizer.class);
		Encoding encoding = mock(Encoding.class);

		when(tokenizer.encode("queryA", "queryB")).thenReturn(encoding);
		when(encoding.getIds()).thenReturn(new long[]{101, 2054, 102});
		when(encoding.getAttentionMask()).thenReturn(new long[]{1, 1, 1});
		when(encoding.getTypeIds()).thenReturn(new long[]{0, 0, 0});
		when(session.getInputNames()).thenReturn(Set.of("input_ids", "attention_mask", "token_type_ids"));

		OrtSession.Result result = mock(OrtSession.Result.class);
		OnnxValue onnxValue = mock(OnnxValue.class);
		when(result.get(0)).thenReturn(onnxValue);
		// Logits [-1.0, 4.0] -> Softmax(1) = exp(4) / (exp(4) + exp(-1)) ~= 0.993 >= 0.90
		when(onnxValue.getValue()).thenReturn(new float[][]{{-1.0f, 4.0f}});
		when(session.run(anyMap())).thenReturn(result);

		OnnxSemanticVerifier verifier = new OnnxSemanticVerifier(
				properties, cpuDetector, environment, session, tokenizer
		);

		assertThat(verifier.verify("queryA", "queryB")).isTrue();
	}

	@Test
	@DisplayName("verify returns false when entailment probability falls below threshold")
	void verifyRejectsWhenScoreBelowThreshold() throws Exception {
		CacheRelayCacheProperties properties = createProperties(true, 0.90, 4);
		OrtEnvironment environment = mock(OrtEnvironment.class);
		OrtSession session = mock(OrtSession.class);
		HuggingFaceTokenizer tokenizer = mock(HuggingFaceTokenizer.class);
		Encoding encoding = mock(Encoding.class);

		when(tokenizer.encode("queryA", "queryB")).thenReturn(encoding);
		when(encoding.getIds()).thenReturn(new long[]{101, 2054, 102});
		when(encoding.getAttentionMask()).thenReturn(new long[]{1, 1, 1});
		when(encoding.getTypeIds()).thenReturn(new long[]{0, 0, 0});
		when(session.getInputNames()).thenReturn(Set.of("input_ids", "attention_mask"));

		OrtSession.Result result = mock(OrtSession.Result.class);
		OnnxValue onnxValue = mock(OnnxValue.class);
		when(result.get(0)).thenReturn(onnxValue);
		// Logits [3.0, -1.0] -> Softmax(1) ~= 0.018 < 0.90
		when(onnxValue.getValue()).thenReturn(new float[][]{{3.0f, -1.0f}});
		when(session.run(anyMap())).thenReturn(result);

		OnnxSemanticVerifier verifier = new OnnxSemanticVerifier(
				properties, cpuDetector, environment, session, tokenizer
		);

		assertThat(verifier.verify("queryA", "queryB")).isFalse();
	}

	@Test
	@DisplayName("verify fails closed to false when semaphore bulkhead is saturated")
	void verifyFailsClosedOnBulkheadSaturation() throws Exception {
		// Bulkhead with exactly 1 permit
		CacheRelayCacheProperties properties = createProperties(true, 0.90, 1);
		OrtEnvironment environment = mock(OrtEnvironment.class);
		OrtSession session = mock(OrtSession.class);
		HuggingFaceTokenizer tokenizer = mock(HuggingFaceTokenizer.class);

		CountDownLatch threadHoldingLock = new CountDownLatch(1);
		CountDownLatch releaseLock = new CountDownLatch(1);

		when(tokenizer.encode(anyString(), anyString())).thenAnswer(invocation -> {
			threadHoldingLock.countDown();
			releaseLock.await(3, TimeUnit.SECONDS);
			Encoding encoding = mock(Encoding.class);
			when(encoding.getIds()).thenReturn(new long[]{1});
			when(encoding.getAttentionMask()).thenReturn(new long[]{1});
			return encoding;
		});

		OnnxSemanticVerifier verifier = new OnnxSemanticVerifier(
				properties, cpuDetector, environment, session, tokenizer
		);

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Boolean> firstCall = executor.submit(() -> verifier.verify("A", "B"));
			assertThat(threadHoldingLock.await(2, TimeUnit.SECONDS)).isTrue();

			// Second concurrent call must immediately encounter bulkhead saturation and return false
			boolean secondCallResult = verifier.verify("C", "D");
			assertThat(secondCallResult).isFalse();

			releaseLock.countDown();
			firstCall.get(2, TimeUnit.SECONDS);
		} finally {
			releaseLock.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	@DisplayName("verify fails closed when inference throws an exception")
	void verifyFailsClosedOnException() throws Exception {
		CacheRelayCacheProperties properties = createProperties(true, 0.90, 4);
		OrtEnvironment environment = mock(OrtEnvironment.class);
		OrtSession session = mock(OrtSession.class);
		HuggingFaceTokenizer tokenizer = mock(HuggingFaceTokenizer.class);

		when(tokenizer.encode(anyString(), anyString())).thenThrow(new RuntimeException("Inference failure"));

		OnnxSemanticVerifier verifier = new OnnxSemanticVerifier(
				properties, cpuDetector, environment, session, tokenizer
		);

		assertThat(verifier.verify("queryA", "queryB")).isFalse();
	}

	@Test
	@DisplayName("disabled verifier gracefully passes through and isEnabled is false")
	void disabledVerifierPassesThrough() {
		CacheRelayCacheProperties properties = createProperties(false, 0.90, 4);

		OnnxSemanticVerifier verifier = new OnnxSemanticVerifier(properties, cpuDetector);

		assertThat(verifier.isEnabled()).isFalse();
		assertThat(verifier.verify("A", "B")).isTrue();
	}

	@Test
	@DisplayName("unconfigured model path degrades gracefully to disabled")
	void unconfiguredModelPathDegrades() {
		CacheRelayCacheProperties properties = new CacheRelayCacheProperties();
		OnnxVerifierProperties verifierProps = properties.getSemantic().getOnnxVerifier();
		verifierProps.setEnabled(true);
		verifierProps.setModelPath(null);
		verifierProps.setTokenizerPath(null);

		OnnxSemanticVerifier verifier = new OnnxSemanticVerifier(properties, cpuDetector);

		assertThat(verifier.isEnabled()).isFalse();
		assertThat(verifier.verify("A", "B")).isTrue();
	}

	@Test
	@DisplayName("resolveModelPath selects int8 sibling or directory model when tier is ACCELERATED_INT8")
	void resolveModelPathSelectsInt8() throws IOException {
		Path dir = Files.createDirectory(tempDir.resolve("models_dir"));
		Path fp32 = Files.createFile(dir.resolve("model.onnx"));
		Path int8 = Files.createFile(dir.resolve("model_int8.onnx"));

		// Directory resolution
		Path resolvedDir = OnnxSemanticVerifier.resolveModelPath(dir.toString(), CpuAccelerationTier.ACCELERATED_INT8);
		assertThat(resolvedDir).isEqualTo(int8);

		Path resolvedBaseline = OnnxSemanticVerifier.resolveModelPath(dir.toString(), CpuAccelerationTier.BASELINE_FP32);
		assertThat(resolvedBaseline).isEqualTo(fp32);

		// File sibling resolution
		Path resolvedSibling = OnnxSemanticVerifier.resolveModelPath(fp32.toString(), CpuAccelerationTier.ACCELERATED_INT8);
		assertThat(resolvedSibling).isEqualTo(int8);
	}

	@Test
	@DisplayName("resolveModelPath handles quantized candidates, missing files, and non-parent paths")
	void resolveModelPathEdgeCases() throws IOException {
		Path dir = Files.createDirectory(tempDir.resolve("models_quant_dir"));
		Path quant = Files.createFile(dir.resolve("model_quantized.onnx"));

		// Directory resolution with model_quantized.onnx
		Path resolvedQuant = OnnxSemanticVerifier.resolveModelPath(dir.toString(), CpuAccelerationTier.ACCELERATED_INT8);
		assertThat(resolvedQuant).isEqualTo(quant);

		// Directory without matching models returns base dir
		Path emptyDir = Files.createDirectory(tempDir.resolve("empty_models_dir"));
		Path resolvedEmpty = OnnxSemanticVerifier.resolveModelPath(emptyDir.toString(), CpuAccelerationTier.ACCELERATED_INT8);
		assertThat(resolvedEmpty).isEqualTo(emptyDir);

		// Sibling resolution with _quantized.onnx
		Path fp32Fake = Files.createFile(tempDir.resolve("custom_model.onnx"));
		Path quantSibling = Files.createFile(tempDir.resolve("custom_model_quantized.onnx"));
		Path resolvedSiblingQuant = OnnxSemanticVerifier.resolveModelPath(fp32Fake.toString(), CpuAccelerationTier.ACCELERATED_INT8);
		assertThat(resolvedSiblingQuant).isEqualTo(quantSibling);

		// Path already containing int8 does not re-substitute
		Path int8Direct = Files.createFile(tempDir.resolve("already_int8.onnx"));
		Path resolvedInt8Direct = OnnxSemanticVerifier.resolveModelPath(int8Direct.toString(), CpuAccelerationTier.ACCELERATED_INT8);
		assertThat(resolvedInt8Direct).isEqualTo(int8Direct);
	}

	@Test
	@DisplayName("padOrTruncate handles equal length, shorter length, and longer length")
	void padOrTruncateTests() {
		long[] exact64 = new long[64];
		assertThat(OnnxSemanticVerifier.padOrTruncate(exact64, 64)).isSameAs(exact64);

		long[] shortArray = new long[]{1, 2, 3};
		long[] padded = OnnxSemanticVerifier.padOrTruncate(shortArray, 64);
		assertThat(padded).hasSize(64);
		assertThat(padded[0]).isEqualTo(1);
		assertThat(padded[1]).isEqualTo(2);
		assertThat(padded[2]).isEqualTo(3);
		assertThat(padded[3]).isEqualTo(0);

		long[] longArray = new long[80];
		longArray[0] = 99;
		long[] truncated = OnnxSemanticVerifier.padOrTruncate(longArray, 64);
		assertThat(truncated).hasSize(64);
		assertThat(truncated[0]).isEqualTo(99);
	}

	@Test
	@DisplayName("resolveModelPath handles relative paths without parent directory")
	void resolveModelPathNoParent() {
		Path relative = Path.of("no_parent_model.onnx");
		Path resolved = OnnxSemanticVerifier.resolveModelPath(relative.toString(), CpuAccelerationTier.ACCELERATED_INT8);
		assertThat(resolved).isEqualTo(relative);
	}

	@Test
	@DisplayName("executeInference handles various inputNames combinations")
	void executeInferenceInputCombinations() throws Exception {
		CacheRelayCacheProperties properties = createProperties(true, 0.90, 4);
		OrtEnvironment environment = mock(OrtEnvironment.class);
		OrtSession session = mock(OrtSession.class);
		HuggingFaceTokenizer tokenizer = mock(HuggingFaceTokenizer.class);
		Encoding encoding = mock(Encoding.class);

		when(tokenizer.encode(anyString(), anyString())).thenReturn(encoding);
		when(encoding.getIds()).thenReturn(new long[]{101});
		when(encoding.getAttentionMask()).thenReturn(new long[]{1});
		when(encoding.getTypeIds()).thenReturn(new long[]{0});

		OrtSession.Result result = mock(OrtSession.Result.class);
		OnnxValue onnxValue = mock(OnnxValue.class);
		when(result.get(0)).thenReturn(onnxValue);
		when(onnxValue.getValue()).thenReturn(new float[][]{{-1.0f, 4.0f}});
		when(session.run(anyMap())).thenReturn(result);

		// Only attention_mask
		when(session.getInputNames()).thenReturn(Set.of("attention_mask"));
		OnnxSemanticVerifier verifier = new OnnxSemanticVerifier(properties, cpuDetector, environment, session, tokenizer);
		assertThat(verifier.verify("A", "B")).isTrue();

		// Only input_ids
		when(session.getInputNames()).thenReturn(Set.of("input_ids"));
		assertThat(verifier.verify("A", "B")).isTrue();

		// Both attention_mask and token_type_ids
		when(session.getInputNames()).thenReturn(Set.of("attention_mask", "token_type_ids"));
		assertThat(verifier.verify("A", "B")).isTrue();
	}

	@Test
	@DisplayName("resolveTokenizerPath resolves tokenizer.json in directory or falls back to path")
	void resolveTokenizerPathTests() throws IOException {
		Path dir = Files.createDirectory(tempDir.resolve("tok_dir"));
		Path tokJson = Files.createFile(dir.resolve("tokenizer.json"));

		assertThat(OnnxSemanticVerifier.resolveTokenizerPath(dir.toString())).isEqualTo(tokJson);

		Path emptyDir = Files.createDirectory(tempDir.resolve("tok_empty_dir"));
		assertThat(OnnxSemanticVerifier.resolveTokenizerPath(emptyDir.toString())).isEqualTo(emptyDir);

		Path directFile = tempDir.resolve("custom_tok.json");
		assertThat(OnnxSemanticVerifier.resolveTokenizerPath(directFile.toString())).isEqualTo(directFile);
	}

	@Test
	@DisplayName("close covers null session and null tokenizer branches independently")
	void closeIndependentNullBranches() throws Exception {
		CacheRelayCacheProperties properties = createProperties(true, 0.90, 4);
		OrtEnvironment environment = mock(OrtEnvironment.class);
		OrtSession session = mock(OrtSession.class);
		HuggingFaceTokenizer tokenizer = mock(HuggingFaceTokenizer.class);

		// session != null, tokenizer == null
		OnnxSemanticVerifier v1 = new OnnxSemanticVerifier(properties, cpuDetector, environment, session, null);
		v1.close();
		verify(session).close();

		// session == null, tokenizer != null
		OnnxSemanticVerifier v2 = new OnnxSemanticVerifier(properties, cpuDetector, environment, null, tokenizer);
		v2.close();
		verify(tokenizer).close();
	}

	@Test
	@DisplayName("extractLogits correctly extracts 2D, 1D, and 3D tensors and handles invalid shapes")
	void extractLogitsShapes() throws Exception {
		OrtSession.Result result = mock(OrtSession.Result.class);
		OnnxValue onnxValue = mock(OnnxValue.class);
		when(result.get(0)).thenReturn(onnxValue);

		// 1D vector -> reshaped to 2D
		when(onnxValue.getValue()).thenReturn(new float[]{1.0f, 2.0f});
		float[][] from1D = OnnxSemanticVerifier.extractLogits(result);
		assertThat(from1D).isDeepEqualTo(new float[][]{{1.0f, 2.0f}});

		// 3D tensor -> first batch extracted
		when(onnxValue.getValue()).thenReturn(new float[][][]{{{1.0f, 2.0f}}});
		float[][] from3D = OnnxSemanticVerifier.extractLogits(result);
		assertThat(from3D).isDeepEqualTo(new float[][]{{1.0f, 2.0f}});

		// Invalid shape
		when(onnxValue.getValue()).thenReturn("invalid_string_output");
		org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
				() -> OnnxSemanticVerifier.extractLogits(result));

		// Null result element
		when(result.get(0)).thenReturn(null);
		org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
				() -> OnnxSemanticVerifier.extractLogits(result));

		// Exception reading value
		when(result.get(0)).thenReturn(onnxValue);
		when(onnxValue.getValue()).thenThrow(new RuntimeException("JNI read error"));
		org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
				() -> OnnxSemanticVerifier.extractLogits(result));
	}

	@Test
	@DisplayName("verify fails closed when logits shape is malformed or empty")
	void verifyFailsClosedOnMalformedLogits() throws Exception {
		CacheRelayCacheProperties properties = createProperties(true, 0.90, 4);
		OrtEnvironment environment = mock(OrtEnvironment.class);
		OrtSession session = mock(OrtSession.class);
		HuggingFaceTokenizer tokenizer = mock(HuggingFaceTokenizer.class);
		Encoding encoding = mock(Encoding.class);

		when(tokenizer.encode(anyString(), anyString())).thenReturn(encoding);
		when(encoding.getIds()).thenReturn(new long[]{101});
		when(encoding.getAttentionMask()).thenReturn(new long[]{1});
		when(session.getInputNames()).thenReturn(Set.of("input_ids"));

		OrtSession.Result result = mock(OrtSession.Result.class);
		OnnxValue onnxValue = mock(OnnxValue.class);
		when(result.get(0)).thenReturn(onnxValue);
		// Single logit instead of 2 logits -> invalid shape
		when(onnxValue.getValue()).thenReturn(new float[][]{{1.0f}});
		when(session.run(anyMap())).thenReturn(result);

		OnnxSemanticVerifier verifier = new OnnxSemanticVerifier(
				properties, cpuDetector, environment, session, tokenizer
		);

		assertThat(verifier.verify("A", "B")).isFalse();
	}

	@Test
	@DisplayName("close and destroy swallow exceptions safely")
	void closeAndDestroySwallowExceptions() throws Exception {
		CacheRelayCacheProperties properties = createProperties(true, 0.90, 4);
		OrtEnvironment environment = mock(OrtEnvironment.class);
		OrtSession session = mock(OrtSession.class);
		HuggingFaceTokenizer tokenizer = mock(HuggingFaceTokenizer.class);

		org.mockito.Mockito.doThrow(new RuntimeException("Session close fail")).when(session).close();
		org.mockito.Mockito.doThrow(new RuntimeException("Tokenizer close fail")).when(tokenizer).close();

		OnnxSemanticVerifier verifier = new OnnxSemanticVerifier(
				properties, cpuDetector, environment, session, tokenizer
		);

		verifier.destroy(); // calls close()

		verify(session).close();
		verify(tokenizer).close();
	}

	@Test
	@DisplayName("constructor handles missing files, blank paths, and tier configs")
	void constructorBranchTests() throws IOException {
		when(cpuDetector.detectTier()).thenReturn(CpuAccelerationTier.BASELINE_FP32);

		// 1. Explicit tier != AUTO
		CacheRelayCacheProperties props1 = createProperties(false, 0.90, 0); // maxConcurrency <= 0 -> 4
		props1.getSemantic().getOnnxVerifier().setTier(CpuAccelerationTier.ACCELERATED_INT8);
		OnnxSemanticVerifier verifier1 = new OnnxSemanticVerifier(props1, cpuDetector);
		assertThat(verifier1.getEffectiveTier()).isEqualTo(CpuAccelerationTier.ACCELERATED_INT8);

		// 2. Blank paths
		CacheRelayCacheProperties props2 = createProperties(true, 0.90, 4);
		props2.getSemantic().getOnnxVerifier().setModelPath("   ");
		props2.getSemantic().getOnnxVerifier().setTokenizerPath("   ");
		OnnxSemanticVerifier verifier2 = new OnnxSemanticVerifier(props2, cpuDetector);
		assertThat(verifier2.isEnabled()).isFalse();

		// 3. Nonexistent files
		CacheRelayCacheProperties props3 = createProperties(true, 0.90, 4);
		props3.getSemantic().getOnnxVerifier().setModelPath(tempDir.resolve("nonexistent_model.onnx").toString());
		props3.getSemantic().getOnnxVerifier().setTokenizerPath(tempDir.resolve("nonexistent_tok.json").toString());
		OnnxSemanticVerifier verifier3 = new OnnxSemanticVerifier(props3, cpuDetector);
		assertThat(verifier3.isEnabled()).isFalse();

		// 4. One path null, other valid
		CacheRelayCacheProperties props4 = createProperties(true, 0.90, 4);
		props4.getSemantic().getOnnxVerifier().setModelPath(null);
		props4.getSemantic().getOnnxVerifier().setTokenizerPath("some/path");
		assertThat(new OnnxSemanticVerifier(props4, cpuDetector).isEnabled()).isFalse();

		CacheRelayCacheProperties props5 = createProperties(true, 0.90, 4);
		props5.getSemantic().getOnnxVerifier().setModelPath("some/path");
		props5.getSemantic().getOnnxVerifier().setTokenizerPath(null);
		assertThat(new OnnxSemanticVerifier(props5, cpuDetector).isEnabled()).isFalse();

		// 5. Tokenizer path blank while model is valid
		CacheRelayCacheProperties props6 = createProperties(true, 0.90, 4);
		props6.getSemantic().getOnnxVerifier().setModelPath("some/path");
		props6.getSemantic().getOnnxVerifier().setTokenizerPath("   ");
		assertThat(new OnnxSemanticVerifier(props6, cpuDetector).isEnabled()).isFalse();

		// 6. Model exists, tokenizer does not
		Path existingModel = Files.createFile(tempDir.resolve("existing_for_test.onnx"));
		CacheRelayCacheProperties props7 = createProperties(true, 0.90, 4);
		props7.getSemantic().getOnnxVerifier().setModelPath(existingModel.toString());
		props7.getSemantic().getOnnxVerifier().setTokenizerPath(tempDir.resolve("missing_tok_for_test.json").toString());
		assertThat(new OnnxSemanticVerifier(props7, cpuDetector).isEnabled()).isFalse();

		// 7. Public constructor with tier == null and tier == AUTO
		CacheRelayCacheProperties propsTierNull = createProperties(false, 0.90, 4);
		propsTierNull.getSemantic().getOnnxVerifier().setTier(null);
		assertThat(new OnnxSemanticVerifier(propsTierNull, cpuDetector).getEffectiveTier()).isEqualTo(CpuAccelerationTier.BASELINE_FP32);

		CacheRelayCacheProperties propsTierAuto = createProperties(false, 0.90, 4);
		propsTierAuto.getSemantic().getOnnxVerifier().setTier(CpuAccelerationTier.AUTO);
		assertThat(new OnnxSemanticVerifier(propsTierAuto, cpuDetector).getEffectiveTier()).isEqualTo(CpuAccelerationTier.BASELINE_FP32);
	}

	@Test
	@DisplayName("verify passes through when session, tokenizer, or environment is null")
	void verifyPassesThroughWhenComponentsNull() {
		CacheRelayCacheProperties properties = createProperties(true, 0.90, 4);
		OrtEnvironment environment = mock(OrtEnvironment.class);
		OrtSession session = mock(OrtSession.class);
		HuggingFaceTokenizer tokenizer = mock(HuggingFaceTokenizer.class);

		assertThat(new OnnxSemanticVerifier(properties, cpuDetector, null, session, tokenizer).verify("A", "B")).isTrue();
		assertThat(new OnnxSemanticVerifier(properties, cpuDetector, environment, null, tokenizer).verify("A", "B")).isTrue();
		assertThat(new OnnxSemanticVerifier(properties, cpuDetector, environment, session, null).verify("A", "B")).isTrue();
	}

	@Test
	@DisplayName("executeInference handles missing inputs, null typeIds, and length mismatches")
	void executeInferenceInputVariations() throws Exception {
		CacheRelayCacheProperties properties = createProperties(true, 0.90, 4);
		OrtEnvironment environment = mock(OrtEnvironment.class);
		OrtSession session = mock(OrtSession.class);
		HuggingFaceTokenizer tokenizer = mock(HuggingFaceTokenizer.class);
		Encoding encoding = mock(Encoding.class);

		when(tokenizer.encode("queryA", "queryB")).thenReturn(encoding);
		when(encoding.getIds()).thenReturn(new long[]{101, 2054, 102});
		when(encoding.getAttentionMask()).thenReturn(new long[]{1, 1, 1});
		// Case A: typeIds is null
		when(encoding.getTypeIds()).thenReturn(null);
		when(session.getInputNames()).thenReturn(Set.of("token_type_ids"));

		OrtSession.Result result = mock(OrtSession.Result.class);
		OnnxValue onnxValue = mock(OnnxValue.class);
		when(result.get(0)).thenReturn(onnxValue);
		when(onnxValue.getValue()).thenReturn(new float[][]{{-1.0f, 4.0f}});
		when(session.run(anyMap())).thenReturn(result);

		OnnxSemanticVerifier verifier = new OnnxSemanticVerifier(
				properties, cpuDetector, environment, session, tokenizer
		);
		assertThat(verifier.verify("queryA", "queryB")).isTrue();

		// Case B: typeIds length mismatch (shorter than ids)
		when(encoding.getTypeIds()).thenReturn(new long[]{0});
		assertThat(verifier.verify("queryA", "queryB")).isTrue();

		// Case C: Empty input names (none of the three present)
		when(session.getInputNames()).thenReturn(Set.of());
		assertThat(verifier.verify("queryA", "queryB")).isTrue();
	}

	@Test
	@DisplayName("extractLogits with empty 3D tensor throws IllegalStateException")
	void extractLogitsEmpty3D() throws Exception {
		OrtSession.Result result = mock(OrtSession.Result.class);
		OnnxValue onnxValue = mock(OnnxValue.class);
		when(result.get(0)).thenReturn(onnxValue);
		when(onnxValue.getValue()).thenReturn(new float[][][]{});

		org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
				() -> OnnxSemanticVerifier.extractLogits(result));
	}

	@Test
	@DisplayName("constructor handles corrupt model and corrupt tokenizer during initialization")
	void constructorCorruptArtifacts() throws IOException {
		when(cpuDetector.detectTier()).thenReturn(CpuAccelerationTier.BASELINE_FP32);

		// Case 1: Corrupt model file
		Path corruptModel = Files.writeString(tempDir.resolve("corrupt_model.onnx"), "not an onnx file");
		Path dummyTok = Files.writeString(tempDir.resolve("dummy_tok.json"), "{}");
		CacheRelayCacheProperties props1 = createProperties(true, 0.90, 4);
		props1.getSemantic().getOnnxVerifier().setModelPath(corruptModel.toString());
		props1.getSemantic().getOnnxVerifier().setTokenizerPath(dummyTok.toString());
		OnnxSemanticVerifier verifier1 = new OnnxSemanticVerifier(props1, cpuDetector);
		assertThat(verifier1.isEnabled()).isFalse();

		// Case 2: Real model exists, but corrupt tokenizer
		Path realModel = Path.of("models/verifier/model.onnx");
		if (Files.isRegularFile(realModel)) {
			Path corruptTok = Files.writeString(tempDir.resolve("corrupt_tok.json"), "not valid json tokenizer");
			CacheRelayCacheProperties props2 = createProperties(true, 0.90, 4);
			props2.getSemantic().getOnnxVerifier().setModelPath(realModel.toString());
			props2.getSemantic().getOnnxVerifier().setTokenizerPath(corruptTok.toString());
			OnnxSemanticVerifier verifier2 = new OnnxSemanticVerifier(props2, cpuDetector);
			assertThat(verifier2.isEnabled()).isFalse();
		}
	}

	@Test
	@DisplayName("test-visible constructor covers tier and concurrency branches")
	void testVisibleConstructorBranches() {
		when(cpuDetector.detectTier()).thenReturn(CpuAccelerationTier.BASELINE_FP32);

		// tier == null
		CacheRelayCacheProperties props1 = createProperties(true, 0.90, 0);
		props1.getSemantic().getOnnxVerifier().setTier(null);
		OnnxSemanticVerifier v1 = new OnnxSemanticVerifier(props1, cpuDetector, null, null, null);
		assertThat(v1.getEffectiveTier()).isEqualTo(CpuAccelerationTier.BASELINE_FP32);

		// tier == AUTO
		CacheRelayCacheProperties props2 = createProperties(true, 0.90, -1);
		props2.getSemantic().getOnnxVerifier().setTier(CpuAccelerationTier.AUTO);
		OnnxSemanticVerifier v2 = new OnnxSemanticVerifier(props2, cpuDetector, null, null, null);
		assertThat(v2.getEffectiveTier()).isEqualTo(CpuAccelerationTier.BASELINE_FP32);

		// tier == ACCELERATED_INT8
		CacheRelayCacheProperties props3 = createProperties(true, 0.90, 2);
		props3.getSemantic().getOnnxVerifier().setTier(CpuAccelerationTier.ACCELERATED_INT8);
		OnnxSemanticVerifier v3 = new OnnxSemanticVerifier(props3, cpuDetector, null, null, null);
		assertThat(v3.getEffectiveTier()).isEqualTo(CpuAccelerationTier.ACCELERATED_INT8);
	}

	@Test
	@DisplayName("resolveModelPath covers baseline file and quantized file branches")
	void resolveModelPathMoreBranches() throws IOException {
		// BASELINE_FP32 with regular file
		Path file = Files.createFile(tempDir.resolve("fp32_direct.onnx"));
		Path resolvedFp32 = OnnxSemanticVerifier.resolveModelPath(file.toString(), CpuAccelerationTier.BASELINE_FP32);
		assertThat(resolvedFp32).isEqualTo(file);

		// ACCELERATED_INT8 with file containing 'quantized'
		Path quantFile = Files.createFile(tempDir.resolve("some_quantized.onnx"));
		Path resolvedQuant = OnnxSemanticVerifier.resolveModelPath(quantFile.toString(), CpuAccelerationTier.ACCELERATED_INT8);
		assertThat(resolvedQuant).isEqualTo(quantFile);
	}

	@Test
	@DisplayName("verify fails closed when logits length is zero")
	void verifyFailsClosedOnZeroLengthLogits() throws Exception {
		CacheRelayCacheProperties properties = createProperties(true, 0.90, 4);
		OrtEnvironment environment = mock(OrtEnvironment.class);
		OrtSession session = mock(OrtSession.class);
		HuggingFaceTokenizer tokenizer = mock(HuggingFaceTokenizer.class);
		Encoding encoding = mock(Encoding.class);

		when(tokenizer.encode(anyString(), anyString())).thenReturn(encoding);
		when(encoding.getIds()).thenReturn(new long[]{101});
		when(encoding.getAttentionMask()).thenReturn(new long[]{1});
		when(session.getInputNames()).thenReturn(Set.of("input_ids"));

		OrtSession.Result result = mock(OrtSession.Result.class);
		OnnxValue onnxValue = mock(OnnxValue.class);
		when(result.get(0)).thenReturn(onnxValue);
		when(onnxValue.getValue()).thenReturn(new float[0][]);
		when(session.run(anyMap())).thenReturn(result);

		OnnxSemanticVerifier verifier = new OnnxSemanticVerifier(
				properties, cpuDetector, environment, session, tokenizer
		);

		assertThat(verifier.verify("A", "B")).isFalse();
	}
	void closeWithNullComponentsIsSafe() {
		CacheRelayCacheProperties properties = createProperties(true, 0.90, 4);
		OnnxSemanticVerifier verifier = new OnnxSemanticVerifier(
				properties, cpuDetector, null, null, null
		);
		verifier.close();
		assertThat(verifier.isEnabled()).isTrue();
	}

	@Test
	@DisplayName("close closes session and tokenizer safely")
	void closeCleansUpResources() throws Exception {
		CacheRelayCacheProperties properties = createProperties(true, 0.90, 4);
		OrtEnvironment environment = mock(OrtEnvironment.class);
		OrtSession session = mock(OrtSession.class);
		HuggingFaceTokenizer tokenizer = mock(HuggingFaceTokenizer.class);

		OnnxSemanticVerifier verifier = new OnnxSemanticVerifier(
				properties, cpuDetector, environment, session, tokenizer
		);

		verifier.close();

		verify(session).close();
		verify(tokenizer).close();
	}

	private static CacheRelayCacheProperties createProperties(boolean enabled, double threshold, int maxConcurrency) {
		CacheRelayCacheProperties properties = new CacheRelayCacheProperties();
		OnnxVerifierProperties verifierProps = properties.getSemantic().getOnnxVerifier();
		verifierProps.setEnabled(enabled);
		verifierProps.setThreshold(threshold);
		verifierProps.setMaxConcurrency(maxConcurrency);
		return properties;
	}
}
