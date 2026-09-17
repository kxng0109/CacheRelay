package io.github.kxng0109.cacherelay.proxy.embeddings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OnnxLocalEmbeddingProperties")
class OnnxLocalEmbeddingPropertiesTest {

	@Test
	@DisplayName("the defaults constant carries the documented values")
	void defaults() {
		OnnxLocalEmbeddingProperties props = OnnxLocalEmbeddingProperties.DEFAULTS;

		assertFalse(props.enabled());
		assertNull(props.modelPath());
		assertNull(props.tokenizerPath());
		assertEquals(2, props.intraOpThreads());
		assertEquals(2_048, props.maxTokens());
	}

	@Test
	@DisplayName("properties bind from the gateway.embeddings.local-onnx prefix with relaxed naming")
	void bindsFromPrefix() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MockPropertySource("test")
				                                          .withProperty("gateway.embeddings.local-onnx.enabled", "true")
				                                          .withProperty("gateway.embeddings.local-onnx.model-path", "C:/models/nomic.onnx")
				                                          .withProperty("gateway.embeddings.local-onnx.tokenizer-path",
				                                                        "C:/models/tokenizer.json")
				                                          .withProperty("gateway.embeddings.local-onnx.intra-op-threads", "4")
				                                          .withProperty("gateway.embeddings.local-onnx.max-tokens", "512"));

		OnnxLocalEmbeddingProperties bound = Binder.get(environment)
		                                          .bind("gateway.embeddings.local-onnx", OnnxLocalEmbeddingProperties.class)
		                                          .get();

		assertTrue(bound.enabled());
		assertEquals("C:/models/nomic.onnx", bound.modelPath());
		assertEquals("C:/models/tokenizer.json", bound.tokenizerPath());
		assertEquals(4, bound.intraOpThreads());
		assertEquals(512, bound.maxTokens());
	}

	@Test
	@DisplayName("partial properties fall back to the defaults for the rest")
	void partialPropertiesUseDefaults() {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MockPropertySource("test")
				                                          .withProperty("gateway.embeddings.local-onnx.enabled", "true"));

		OnnxLocalEmbeddingProperties bound = Binder.get(environment)
		                                          .bind("gateway.embeddings.local-onnx", OnnxLocalEmbeddingProperties.class)
		                                          .get();

		assertTrue(bound.enabled());
		assertEquals(2, bound.intraOpThreads());
		assertEquals(2_048, bound.maxTokens());
	}
}
