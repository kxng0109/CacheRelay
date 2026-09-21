package io.github.kxng0109.cacherelay.proxy.embeddings;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EmbeddingMetrics")
class EmbeddingMetricsTest {

	@Test
	@DisplayName("null registry falls back to an isolated in-memory registry")
	void nullRegistryIsolated() {
		EmbeddingMetrics metrics = new EmbeddingMetrics(null);

		metrics.request("openai", "gpt-4o", "success");

		assertThat(metrics).isNotNull();
	}

	@Test
	@DisplayName("requests and latency record with provider tags")
	void requestsAndLatencyRecord() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		EmbeddingMetrics metrics = new EmbeddingMetrics(registry);

		metrics.request("openai", "gpt-4o", "success");
		metrics.upstreamLatency("openai", 12);

		assertThat(registry.get("embedding_requests_total")
				.tag("provider", "openai").tag("model", "gpt-4o")
				.tag("outcome", "success").counter().count()).isEqualTo(1.0);
		assertThat(registry.get("embedding_upstream_latency")
				.tag("provider", "openai").timer().count()).isEqualTo(1L);
	}
}
