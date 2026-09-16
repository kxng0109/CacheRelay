package io.github.kxng0109.aegisgate.proxy.embeddings;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Embedding batching, keep-warm, and fan-out ceilings, bound from {@code gateway.embeddings.*}.
 *
 * @param maxBatchItems            maximum input items accepted in one embedding request
 * @param maxConcurrentSubRequests maximum parallel provider sub-batch dispatches
 * @param keepWarmEnabled          whether the Ollama keep-warm heartbeat runs (disable on
 *                                 battery-powered hosts)
 */
@ConfigurationProperties("gateway.embeddings")
@Validated
public record EmbeddingProperties(
		@Min(1) @Max(100_000) @DefaultValue("2048") int maxBatchItems,
		@Min(1) @Max(64) @DefaultValue("4") int maxConcurrentSubRequests,
		@DefaultValue("true") boolean keepWarmEnabled
) {

	/**
	 * The documented defaults, mirroring the {@link DefaultValue} annotations.
	 */
	public static final EmbeddingProperties DEFAULTS = new EmbeddingProperties(2_048, 4, true);
}
