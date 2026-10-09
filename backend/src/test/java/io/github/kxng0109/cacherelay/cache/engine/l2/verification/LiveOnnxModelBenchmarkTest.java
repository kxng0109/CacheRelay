package io.github.kxng0109.cacherelay.cache.engine.l2.verification;

import io.github.kxng0109.cacherelay.cache.config.CacheRelayCacheProperties;
import io.github.kxng0109.cacherelay.cache.config.CacheRelayCacheProperties.OnnxVerifierProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Live ONNX Model Physical Weights Latency & Accuracy Benchmark")
class LiveOnnxModelBenchmarkTest {

	private static final Logger log = LoggerFactory.getLogger(LiveOnnxModelBenchmarkTest.class);
	private static final Path MODELS_DIR = Path.of("models/verifier");

	static boolean hasPhysicalModels() {
		return Files.isRegularFile(MODELS_DIR.resolve("model.onnx"))
				&& Files.isRegularFile(MODELS_DIR.resolve("model_int8.onnx"))
				&& Files.isRegularFile(MODELS_DIR.resolve("tokenizer.json"));
	}

	@Test
	@EnabledIf("hasPhysicalModels")
	@DisplayName("benchmark real FP32 and INT8 latency and accuracy on CPU")
	void benchmarkRealModelsOnCpu() {
		HostCpuFeatureDetector cpuDetector = mock(HostCpuFeatureDetector.class);

		// 1. Benchmark Baseline FP32
		when(cpuDetector.detectTier()).thenReturn(CpuAccelerationTier.BASELINE_FP32);
		CacheRelayCacheProperties fp32Props = createProperties(CpuAccelerationTier.BASELINE_FP32);
		try (OnnxSemanticVerifier fp32Verifier = new OnnxSemanticVerifier(fp32Props, cpuDetector)) {
			assertThat(fp32Verifier.isEnabled()).isTrue();
			runLatencyBenchmark("Baseline FP32", fp32Verifier, 500);
			runEvaluationSuite("Baseline FP32", fp32Verifier);
		}

		// 2. Benchmark Accelerated INT8
		when(cpuDetector.detectTier()).thenReturn(CpuAccelerationTier.ACCELERATED_INT8);
		CacheRelayCacheProperties int8Props = createProperties(CpuAccelerationTier.ACCELERATED_INT8);
		try (OnnxSemanticVerifier int8Verifier = new OnnxSemanticVerifier(int8Props, cpuDetector)) {
			assertThat(int8Verifier.isEnabled()).isTrue();
			runLatencyBenchmark("Accelerated INT8", int8Verifier, 500);
			runEvaluationSuite("Accelerated INT8", int8Verifier);
		}
	}

	private void runEvaluationSuite(String modelName, OnnxSemanticVerifier verifier) {
		log.info("========== Evaluating Semantic Accuracy: {} ==========", modelName);

		// Semantic Paraphrase should match
		boolean paraphrase = verifier.verify("How do I reset my password", "I forgot my password how to reset");
		log.info("Paraphrase ['reset password' <=> 'forgot password how to reset']: Match={}", paraphrase);
		assertThat(paraphrase).isTrue();

		// Dissimilar questions should NEVER match
		boolean diffTopic1 = verifier.verify("How do I reset my password", "How do I delete my account");
		log.info("Different topic ['reset password' <=> 'delete account']: Match={}", diffTopic1);
		assertThat(diffTopic1).isFalse();

		boolean diffTopic2 = verifier.verify("Explain quantum computing", "Explain photosynthesis in plants");
		log.info("Different topic ['quantum' <=> 'photosynthesis']: Match={}", diffTopic2);
		assertThat(diffTopic2).isFalse();

		boolean diffTopic3 = verifier.verify("Where can I buy a car", "Where can I rent an apartment");
		log.info("Different topic ['buy car' <=> 'rent apartment']: Match={}", diffTopic3);
		assertThat(diffTopic3).isFalse();
	}

	private void runLatencyBenchmark(String modelName, OnnxSemanticVerifier verifier, int iterations) {
		String q1 = "How to deploy container with docker";
		String q2 = "How to deploy container";

		// Warmup
		for (int i = 0; i < 50; i++) {
			verifier.verify(q1, q2);
		}

		List<Long> latenciesNanos = new ArrayList<>(iterations);
		for (int i = 0; i < iterations; i++) {
			long start = System.nanoTime();
			verifier.verify(q1, q2);
			long duration = System.nanoTime() - start;
			latenciesNanos.add(duration);
		}

		Collections.sort(latenciesNanos);
		double p50Ms = latenciesNanos.get((int) (iterations * 0.50)) / 1_000_000.0;
		double p90Ms = latenciesNanos.get((int) (iterations * 0.90)) / 1_000_000.0;
		double p99Ms = latenciesNanos.get((int) (iterations * 0.99)) / 1_000_000.0;
		double meanMs = latenciesNanos.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;

		System.out.printf(
				"%n>>> BENCHMARK RESULTS [%s - 1 CPU Thread]: Mean=%.2f ms | P50=%.2f ms | P90=%.2f ms | P99=%.2f ms <<<%n%n",
				modelName, meanMs, p50Ms, p90Ms, p99Ms
		);
	}

	private CacheRelayCacheProperties createProperties(CpuAccelerationTier tier) {
		CacheRelayCacheProperties properties = new CacheRelayCacheProperties();
		OnnxVerifierProperties verifierProps = properties.getSemantic().getOnnxVerifier();
		verifierProps.setEnabled(true);
		verifierProps.setModelPath(MODELS_DIR.toString());
		verifierProps.setTokenizerPath(MODELS_DIR.toString());
		verifierProps.setThreshold(0.75);
		verifierProps.setMaxConcurrency(4);
		verifierProps.setTier(tier);
		return properties;
	}
}
