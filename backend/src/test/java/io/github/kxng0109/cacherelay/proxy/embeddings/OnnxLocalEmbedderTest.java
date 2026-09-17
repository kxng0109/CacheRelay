package io.github.kxng0109.cacherelay.proxy.embeddings;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OnnxLocalEmbedder")
class OnnxLocalEmbedderTest {

	@TempDir
	Path tempDir;

	@Test
	@DisplayName("masked mean pooling excludes padding and L2-normalizes the result")
	void poolAndNormalizeMasksAndNormalizes() {
		float[][] hidden = {{2.0f, 0.0f}, {0.0f, 4.0f}};
		long[] mask = {1, 1};

		float[] pooled = OnnxLocalEmbedder.poolAndNormalize(hidden, mask);

		// mean = [1, 2]; norm = sqrt(5)
		assertThat(pooled).hasSize(2);
		assertThat(pooled[0]).isCloseTo((float) (1.0 / Math.sqrt(5.0)), offset(1e-6f));
		assertThat(pooled[1]).isCloseTo((float) (2.0 / Math.sqrt(5.0)), offset(1e-6f));
		assertThat(dot(pooled, pooled)).isCloseTo(1.0, offset(1e-5));
	}

	@Test
	@DisplayName("fully masked positions are excluded from the pooled mean")
	void poolAndNormalizeExcludesMaskedRows() {
		float[][] hidden = {{1.0f, 3.0f}, {100.0f, 100.0f}};
		long[] mask = {1, 0};

		float[] pooled = OnnxLocalEmbedder.poolAndNormalize(hidden, mask);

		// only the first row counts: normalize([1, 3]) = [1, 3] / sqrt(10)
		assertThat(pooled[0]).isCloseTo((float) (1.0 / Math.sqrt(10.0)), offset(1e-6f));
		assertThat(pooled[1]).isCloseTo((float) (3.0 / Math.sqrt(10.0)), offset(1e-6f));
	}

	@Test
	@DisplayName("an all-masked input yields the zero vector rather than NaN")
	void poolAndNormalizeAllMaskedYieldsZeroVector() {
		float[][] hidden = {{1.0f, 2.0f}};
		long[] mask = {0};

		float[] pooled = OnnxLocalEmbedder.poolAndNormalize(hidden, mask);

		assertThat(pooled).containsExactly(0.0f, 0.0f);
	}

	@Test
	@DisplayName("empty hidden states yield an empty vector")
	void poolAndNormalizeEmptyYieldsEmpty() {
		assertThat(OnnxLocalEmbedder.poolAndNormalize(new float[0][], new long[0])).isEmpty();
		assertThat(OnnxLocalEmbedder.poolAndNormalize(new float[][]{{}}, new long[]{1})).isEmpty();
	}

	@Test
	@DisplayName("construction fails fast when the model path is missing")
	void constructionFailsFastOnMissingModelPath() {
		OnnxLocalEmbeddingProperties blankModel = new OnnxLocalEmbeddingProperties(
				true, null, "irrelevant", 2, 2_048);

		assertThatThrownBy(() -> new OnnxLocalEmbedder(blankModel))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("model-path");
	}

	@Test
	@DisplayName("construction fails fast when the model file does not exist on disk")
	void constructionFailsFastOnNonexistentModelFile() {
		OnnxLocalEmbeddingProperties missing = new OnnxLocalEmbeddingProperties(
				true, tempDir.resolve("missing.onnx").toString(), "irrelevant", 2, 2_048);

		assertThatThrownBy(() -> new OnnxLocalEmbedder(missing))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("does not exist");
	}

	@Test
	@DisplayName("construction fails fast when the tokenizer file does not exist on disk")
	void constructionFailsFastOnMissingTokenizerFile() throws IOException {
		Path model = Files.createFile(tempDir.resolve("model.onnx"));

		OnnxLocalEmbeddingProperties missing = new OnnxLocalEmbeddingProperties(
				true, model.toString(), tempDir.resolve("missing-tokenizer.json").toString(), 2, 2_048);

		assertThatThrownBy(() -> new OnnxLocalEmbedder(missing))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("does not exist");
	}

	@Test
	@DisplayName("happy construction wires session, input names, and tokenizer; destroy closes them")
	void happyConstructionAndDestroy() throws Exception {
		Path model = Files.createFile(tempDir.resolve("model.onnx"));
		Path tokenizerFile = Files.createFile(tempDir.resolve("tokenizer.json"));
		OnnxLocalEmbeddingProperties properties = new OnnxLocalEmbeddingProperties(
				true, model.toString(), tokenizerFile.toString(), 2, 2_048);

		OrtEnvironment environment = mock(OrtEnvironment.class);
		OrtSession session = mock(OrtSession.class);
		HuggingFaceTokenizer tokenizer = mock(HuggingFaceTokenizer.class);
		when(session.getInputNames()).thenReturn(Set.of("input_ids", "attention_mask"));

		try (MockedStatic<HuggingFaceTokenizer> factory = mockStatic(HuggingFaceTokenizer.class)) {
			factory.when(() -> HuggingFaceTokenizer.newInstance(any(Path.class), anyMap()))
			       .thenReturn(tokenizer);
			when(environment.createSession(anyString(), any(OrtSession.SessionOptions.class)))
					.thenReturn(session);

			OnnxLocalEmbedder embedder = new OnnxLocalEmbedder(properties, environment);

			assertThat(embedder).isNotNull();

			embedder.destroy();

			verify(session).close();
		}
		verify(tokenizer).close();
	}

	@Test
	@DisplayName("embed tokenizes, runs the session, and returns the pooled unit vector")
	void embedTokenizesRunsAndPools() throws Exception {
		float[] vector = embedThroughHarness(Set.of("input_ids", "attention_mask"));

		assertThat(vector).hasSize(2);
		assertThat(vector[0]).isCloseTo((float) (1.0 / Math.sqrt(5.0)), offset(1e-6f));
		assertThat(vector[1]).isCloseTo((float) (2.0 / Math.sqrt(5.0)), offset(1e-6f));
	}

	@Test
	@DisplayName("models requiring token_type_ids get a zero segment tensor fed alongside ids and mask")
	void embedFeedsTokenTypeIdsWhenModelRequiresIt() throws Exception {
		float[] vector = embedThroughHarness(
				Set.of("input_ids", "attention_mask", "token_type_ids"));

		assertThat(vector).hasSize(2);
		assertThat(vector[0]).isCloseTo((float) (1.0 / Math.sqrt(5.0)), offset(1e-6f));
	}

	@Test
	@DisplayName("a model output that is not a rank-3 float tensor fails closed")
	void extractHiddenStateRejectsWrongShape() throws Exception {
		OrtSession.Result result = mock(OrtSession.Result.class);
		OnnxValue value = mock(OnnxValue.class);
		when(result.get(0)).thenReturn(value);
		when(value.getValue()).thenReturn(new float[]{1.0f});

		assertThatThrownBy(() -> OnnxLocalEmbedder.extractHiddenState(result))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("shape");
	}

	@Test
	@DisplayName("construction fails fast when the tokenizer factory throws after the session was created")
	void constructionFailsWhenTokenizerFactoryThrows() throws Exception {
		Path model = Files.createFile(tempDir.resolve("model.onnx"));
		Path tokenizerFile = Files.createFile(tempDir.resolve("tokenizer.json"));
		OnnxLocalEmbeddingProperties properties = new OnnxLocalEmbeddingProperties(
				true, model.toString(), tokenizerFile.toString(), 2, 2_048);

		OrtEnvironment environment = mock(OrtEnvironment.class);
		OrtSession session = mock(OrtSession.class);

		try (MockedStatic<HuggingFaceTokenizer> factory = mockStatic(HuggingFaceTokenizer.class)) {
			factory.when(() -> HuggingFaceTokenizer.newInstance(any(Path.class), anyMap()))
			       .thenThrow(new IOException("corrupt tokenizer"));
			when(environment.createSession(anyString(), any(OrtSession.SessionOptions.class)))
					.thenReturn(session);

			assertThatThrownBy(() -> new OnnxLocalEmbedder(properties, environment))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("Failed to initialize");
		}

		verify(session).close();
	}

	@Test
	@DisplayName("a missing first output tensor fails closed")
	void extractHiddenStateRejectsNullOutput() {
		OrtSession.Result result = mock(OrtSession.Result.class);
		when(result.get(0)).thenReturn(null);

		assertThatThrownBy(() -> OnnxLocalEmbedder.extractHiddenState(result))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("no output tensor");
	}

	@Test
	@DisplayName("an attended but all-zero hidden state yields the zero vector rather than NaN")
	void poolAndNormalizeZeroNormYieldsZeroVector() {
		float[][] hidden = {{0.0f, 0.0f}};
		long[] mask = {1};

		float[] pooled = OnnxLocalEmbedder.poolAndNormalize(hidden, mask);

		assertThat(pooled).containsExactly(0.0f, 0.0f);
	}

	private float[] embedThroughHarness(Set<String> inputNames) throws Exception {
		Path model = Files.createFile(tempDir.resolve("model.onnx"));
		Path tokenizerFile = Files.createFile(tempDir.resolve("tokenizer.json"));
		OnnxLocalEmbeddingProperties properties = new OnnxLocalEmbeddingProperties(
				true, model.toString(), tokenizerFile.toString(), 2, 2_048);

		OrtEnvironment environment = mock(OrtEnvironment.class);
		OrtSession session = mock(OrtSession.class);
		HuggingFaceTokenizer tokenizer = mock(HuggingFaceTokenizer.class);
		Encoding encoding = mock(Encoding.class);
		when(encoding.getIds()).thenReturn(new long[]{2, 4, 5, 3});
		when(encoding.getAttentionMask()).thenReturn(new long[]{1, 1, 1, 1});
		when(tokenizer.encode("keep alive")).thenReturn(encoding);
		when(session.getInputNames()).thenReturn(inputNames);
		when(environment.createSession(anyString(), any(OrtSession.SessionOptions.class)))
				.thenReturn(session);

		OrtSession.Result result = mock(OrtSession.Result.class);
		OnnxValue output = mock(OnnxValue.class);
		when(result.get(0)).thenReturn(output);
		when(output.getValue()).thenReturn(new float[][][]{{{2.0f, 0.0f}, {0.0f, 4.0f}}});
		when(session.run(anyMap())).thenReturn(result);

		try (MockedStatic<HuggingFaceTokenizer> factory = mockStatic(HuggingFaceTokenizer.class)) {
			factory.when(() -> HuggingFaceTokenizer.newInstance(any(Path.class), anyMap()))
			       .thenReturn(tokenizer);
			OnnxLocalEmbedder embedder = new OnnxLocalEmbedder(properties, environment);
			return embedder.embed("keep alive");
		}
	}

	private static double dot(float[] a, float[] b) {
		double sum = 0.0;
		for (int i = 0; i < a.length; i++) {
			sum += a[i] * b[i];
		}
		return sum;
	}
}
