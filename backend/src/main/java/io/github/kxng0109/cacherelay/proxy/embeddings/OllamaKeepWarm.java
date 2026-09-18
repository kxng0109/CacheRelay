package io.github.kxng0109.cacherelay.proxy.embeddings;

import io.github.kxng0109.cacherelay.cache.config.CacheRelayCacheProperties;
import io.github.kxng0109.cacherelay.contracts.ProviderType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ollama keep-warm heartbeat.
 *
 * <p>Measured on the local stack (2026-09-16, Radeon 780M iGPU, Vulkan path): a steady embedding
 * runs in 18&ndash;33&nbsp;ms, but the iGPU enters a deep-sleep state between requests and the first
 * call after an idle gap pays a ~2.2&nbsp;s wake penalty (cold model load is ~3.9&nbsp;s, one-time).
 * A heartbeat ping on a fixed delay keeps the GPU awake so bursty traffic never pays the wake tax.</p>
 *
 * <p>The ping targets the same endpoint and effective model that real semantic-cache embeddings
 * resolve to (via {@link EmbeddingService#resolveWarmTarget}), so the right model stays resident.
 * Failures are swallowed by design &mdash; a dead Ollama must never crash the scheduler; consecutive
 * failures log a WARN on the first occurrence and every 50th thereafter to avoid log spam. The
 * request timeout is bounded to 5&nbsp;s so a hung Ollama cannot starve the shared scheduler.</p>
 */
@Slf4j
@Component
public class OllamaKeepWarm {

	private static final Duration PING_TIMEOUT = Duration.ofSeconds(5);
	private static final int WARN_FAILURE_INTERVAL = 50;
	private static final String KEEP_ALIVE_PROMPT = "keep-alive";
	private static final String KEEP_ALIVE_TTL = "30m";

	private final EmbeddingService embeddingService;
	private final CacheRelayCacheProperties cacheProperties;
	private final HttpClient httpClient;
	private final EmbeddingProperties properties;
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final AtomicInteger consecutiveFailures = new AtomicInteger();

	/**
	 * Creates the heartbeat (test-visible).
	 */
	public OllamaKeepWarm(
			EmbeddingService embeddingService,
			CacheRelayCacheProperties cacheProperties,
			HttpClient httpClient,
			EmbeddingProperties properties
	) {
		this.embeddingService = embeddingService;
		this.cacheProperties = cacheProperties;
		this.httpClient = httpClient;
		this.properties = properties;
	}

	/**
	 * Pings the Ollama embeddings endpoint on the configured fixed delay. No-op when the heartbeat is
	 * disabled, the semantic embedding model is unresolvable, or it resolves to a non-Ollama target
	 * (the wake penalty is Ollama-server-specific; remote providers have no local GPU to keep awake).
	 *
	 * <p>A target counts as Ollama when its provider type is {@code OLLAMA} or its endpoint URL
	 * looks like Ollama's (default port {@code 11434} or an {@code /api/embed} path), so a
	 * mislabeled or generic-typed Ollama server still gets its heartbeat.</p>
	 */
	@Scheduled(fixedDelayString = "${gateway.embeddings.keep-warm-interval:5s}")
	public void warm() {
		if (!properties.keepWarmEnabled()) {
			return;
		}
		String embeddingModel = cacheProperties.getSemantic().getEmbeddingModel();
		EmbeddingService.WarmTarget target = embeddingService.resolveWarmTarget(embeddingModel);
		if (target == null || !isOllamaTarget(target)) {
			return;
		}
		ping(target);
	}

	/**
	 * Decides whether a resolved warm target is an Ollama server worth keeping warm.
	 *
	 * @param target resolved endpoint
	 * @return true for OLLAMA-typed targets or Ollama-shaped URLs
	 */
	static boolean isOllamaTarget(EmbeddingService.WarmTarget target) {
		if (target.config().type() == ProviderType.OLLAMA) {
			return true;
		}
		URI uri = target.targetUri();
		if (uri.getPort() == 11434) {
			return true;
		}
		String path = uri.getPath();
		return path != null && path.contains("/api/embed");
	}

	private void ping(EmbeddingService.WarmTarget target) {
		try {
			HttpRequest request = buildPingRequest(target);
			httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
			if (consecutiveFailures.getAndSet(0) > 0) {
				log.info("Ollama keep-warm ping recovered after {} consecutive failures", consecutiveFailures.get());
			}
			log.debug("Ollama keep-warm ping sent to {}", target.targetUri());
		} catch (IOException | InterruptedException ex) {
			if (ex instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			int failures = consecutiveFailures.incrementAndGet();
			if (failures == 1 || failures % WARN_FAILURE_INTERVAL == 0) {
				log.warn("Ollama keep-warm ping failed ({} consecutive): {}", failures, ex.getMessage());
			} else {
				log.debug("Ollama keep-warm ping failed ({} consecutive): {}", failures, ex.getMessage());
			}
		}
	}

	private HttpRequest buildPingRequest(EmbeddingService.WarmTarget target) {
		ObjectNode root = objectMapper.createObjectNode();
		root.put("model", target.effectiveModel());
		root.putArray("input").add(KEEP_ALIVE_PROMPT);
		// Keep the model resident well beyond the heartbeat interval.
		root.put("keep_alive", KEEP_ALIVE_TTL);
		byte[] bodyBytes = objectMapper.writeValueAsBytes(root);
		// resolveWarmTarget already mapped the OLLAMA provider to its /api/embed endpoint.
		return HttpRequest.newBuilder(target.targetUri())
				       .timeout(PING_TIMEOUT)
				       .header("Content-Type", "application/json")
				       .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
				       .build();
	}
}
