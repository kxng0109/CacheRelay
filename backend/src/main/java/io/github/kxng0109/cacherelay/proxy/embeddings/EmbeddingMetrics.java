package io.github.kxng0109.cacherelay.proxy.embeddings;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Micrometer telemetry for the embeddings path (FS-06): per-provider request outcomes
 * and upstream latency, so cost and regression visibility do not depend on the provider
 * header alone.
 *
 * @since 1.7.0
 */
@Component
public class EmbeddingMetrics {

	private final MeterRegistry meterRegistry;

	/**
	 * Creates the metrics with an explicit registry (tests use {@link SimpleMeterRegistry}).
	 *
	 * @param meterRegistry registry, or {@code null} for an isolated in-memory one
	 */
	public EmbeddingMetrics(@Nullable MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry != null ? meterRegistry : new SimpleMeterRegistry();
	}

	/**
	 * Records a completed embedding request outcome.
	 *
	 * @param provider upstream provider name
	 * @param model    effective upstream model
	 * @param outcome  {@code success}, {@code error}, or {@code denied}
	 */
	public void request(String provider, String model, String outcome) {
		meterRegistry.counter("embedding_requests_total",
				"provider", provider, "model", model, "outcome", outcome).increment();
	}

	/**
	 * Records upstream embedding latency.
	 *
	 * @param provider   upstream provider name
	 * @param durationMs elapsed upstream milliseconds
	 */
	public void upstreamLatency(String provider, long durationMs) {
		meterRegistry.timer("embedding_upstream_latency",
				"provider", provider).record(Duration.ofMillis(Math.max(0, durationMs)));
	}
}
