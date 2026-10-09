package io.github.kxng0109.cacherelay.cache.config;

import io.github.kxng0109.cacherelay.cache.contracts.CacheScope;
import io.github.kxng0109.cacherelay.cache.engine.l2.verification.CpuAccelerationTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CacheRelayCacheProperties")
class CacheRelayCachePropertiesTest {

	@Test
	@DisplayName("getters and setters configure all properties correctly")
	void gettersAndSetters() {
		CacheRelayCacheProperties props = new CacheRelayCacheProperties();

		props.setEnabled(false);
		assertThat(props.isEnabled()).isFalse();

		props.setDefaultScope(CacheScope.USER);
		assertThat(props.getDefaultScope()).isEqualTo(CacheScope.USER);

		props.setTtl(Duration.ofHours(12));
		assertThat(props.getTtl()).isEqualTo(Duration.ofHours(12));

		CacheRelayCacheProperties.ExactCacheProperties exact = new CacheRelayCacheProperties.ExactCacheProperties();
		exact.setL0MaxBytes(1024);
		exact.setL0InMemoryTtl(Duration.ofSeconds(30));
		exact.setL1RedisEnabled(false);
		props.setExact(exact);

		assertThat(props.getExact().getL0MaxBytes()).isEqualTo(1024);
		assertThat(props.getExact().getL0InMemoryTtl()).isEqualTo(Duration.ofSeconds(30));
		assertThat(props.getExact().isL1RedisEnabled()).isFalse();

		CacheRelayCacheProperties.SemanticCacheProperties semantic = new CacheRelayCacheProperties.SemanticCacheProperties();
		semantic.setEnabled(false);
		semantic.setEmbeddingModel("bge-small");
		semantic.setSimilarityThreshold(0.85);
		semantic.setMaxTurnCountback(2);
		semantic.setPolarityGuardEnabled(false);
		semantic.setEntityGuardEnabled(false);
		semantic.setTemperatureFloor(0.2);

		CacheRelayCacheProperties.OnnxVerifierProperties onnxVerifier = new CacheRelayCacheProperties.OnnxVerifierProperties();
		onnxVerifier.setEnabled(true);
		onnxVerifier.setModelPath("/models/model.onnx");
		onnxVerifier.setTokenizerPath("/models/tokenizer.json");
		onnxVerifier.setThreshold(0.95);
		onnxVerifier.setMaxConcurrency(8);
		onnxVerifier.setTier(CpuAccelerationTier.ACCELERATED_INT8);
		semantic.setOnnxVerifier(onnxVerifier);

		props.setSemantic(semantic);

		assertThat(props.getSemantic().isEnabled()).isFalse();
		assertThat(props.getSemantic().getEmbeddingModel()).isEqualTo("bge-small");
		assertThat(props.getSemantic().getSimilarityThreshold()).isEqualTo(0.85);
		assertThat(props.getSemantic().getMaxTurnCountback()).isEqualTo(2);
		assertThat(props.getSemantic().isPolarityGuardEnabled()).isFalse();
		assertThat(props.getSemantic().isEntityGuardEnabled()).isFalse();
		assertThat(props.getSemantic().getTemperatureFloor()).isEqualTo(0.2);

		assertThat(props.getSemantic().getOnnxVerifier().isEnabled()).isTrue();
		assertThat(props.getSemantic().getOnnxVerifier().getModelPath()).isEqualTo("/models/model.onnx");
		assertThat(props.getSemantic().getOnnxVerifier().getTokenizerPath()).isEqualTo("/models/tokenizer.json");
		assertThat(props.getSemantic().getOnnxVerifier().getThreshold()).isEqualTo(0.95);
		assertThat(props.getSemantic().getOnnxVerifier().getMaxConcurrency()).isEqualTo(8);
		assertThat(props.getSemantic().getOnnxVerifier().getTier()).isEqualTo(CpuAccelerationTier.ACCELERATED_INT8);
	}

	@Test
	@DisplayName("semantic cache defaults carry the calibrated similarity threshold and onnx verifier defaults")
	void semanticDefaults() {
		CacheRelayCacheProperties props = new CacheRelayCacheProperties();

		assertThat(props.getSemantic().getSimilarityThreshold()).isEqualTo(0.80);
		assertThat(props.getSemantic().isPolarityGuardEnabled()).isTrue();
		assertThat(props.getSemantic().isEntityGuardEnabled()).isTrue();

		CacheRelayCacheProperties.OnnxVerifierProperties onnxVerifier = props.getSemantic().getOnnxVerifier();
		assertThat(onnxVerifier.isEnabled()).isFalse();
		assertThat(onnxVerifier.getModelPath()).isNull();
		assertThat(onnxVerifier.getTokenizerPath()).isNull();
		assertThat(onnxVerifier.getThreshold()).isEqualTo(0.90);
		assertThat(onnxVerifier.getMaxConcurrency()).isEqualTo(4);
		assertThat(onnxVerifier.getTier()).isEqualTo(CpuAccelerationTier.AUTO);
	}
}
