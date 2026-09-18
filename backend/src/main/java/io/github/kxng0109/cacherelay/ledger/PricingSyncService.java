package io.github.kxng0109.cacherelay.ledger;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Refreshes the pricing catalog from the LiteLLM model pricing file.
 *
 * <p>The gateway records cost against whatever the catalog holds, so the
 * prices must stay current as providers change them. The sync fetches the LiteLLM catalog (configurable URL, so it can
 * be pinned to a tag or commit), keeps the chat oriented entries, and upserts them into the pricing table. It runs once
 * at startup and then on a daily cron schedule.</p>
 *
 * <p>The sync is strictly best effort. A failed fetch, an unparseable file,
 * or a database outage only logs a warning and leaves the previous rows in place; the hot path never depends on
 * it.</p>
 */
@Slf4j
@Component
public class PricingSyncService {

	/**
	 * Bound for one catalog fetch.
	 */
	private final HttpClient httpClient;
	private final ObjectMapper objectMapper;
	private final ModelPricingRepository repository;
	private final ModelPriceCatalog priceCatalog;
	private final String sourceUrl;
	private final Duration fetchTimeout;
	private final int maxAttempts;
	private final Duration backoffBase;

	/**
	 * @param httpClient   shared upstream client
	 * @param objectMapper Jackson mapper for the catalog file
	 * @param repository   pricing repository
	 * @param priceCatalog read side of the catalog, invalidated after a sync
	 * @param sourceUrl    where the catalog is fetched from
	 */
	public PricingSyncService(
			HttpClient httpClient,
			ObjectMapper objectMapper,
			ModelPricingRepository repository,
			ModelPriceCatalog priceCatalog,
			@Value("${gateway.pricing.source-url:https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json}") String sourceUrl,
			@Value("${gateway.pricing.fetch-timeout-seconds:30}") long fetchTimeoutSeconds,
			@Value("${gateway.pricing.max-attempts:5}") int maxAttempts,
			@Value("${gateway.pricing.backoff-base-seconds:5}") long backoffBaseSeconds
	) {
		this.httpClient = httpClient;
		this.objectMapper = objectMapper;
		this.repository = repository;
		this.priceCatalog = priceCatalog;
		this.sourceUrl = sourceUrl;
		this.fetchTimeout = Duration.ofSeconds(Math.max(1L, fetchTimeoutSeconds));
		this.maxAttempts = Math.max(1, maxAttempts);
		this.backoffBase = Duration.ofSeconds(Math.max(1L, backoffBaseSeconds));
	}

	/**
	 * Best effort sync shortly after the application is ready.
	 *
	 * <p>Runs on the bounded {@code pricingSyncExecutor}, never on the event thread: readiness
	 * flips while the first fetch is still in flight. Requires CGLIB async proxies (enabled
	 * application-wide) because this bean implements no interface.</p>
	 */
	@Async("pricingSyncExecutor")
	@EventListener(ApplicationReadyEvent.class)
	public void syncOnReady() {
		refresh();
	}

	/**
	 * Daily refresh of the pricing catalog.
	 */
	@Scheduled(cron = "${gateway.pricing.refresh-cron:0 0 3 * * *}")
	public void syncOnSchedule() {
		refresh();
	}

	/**
	 * Fetches and stores the catalog. Never throws; failures are logged.
	 */
	public void refresh() {
		try {
			JsonNode root = fetchCatalogWithRetry();
			int kept = upsert(root);
			priceCatalog.invalidate();
			log.info("Refreshed pricing catalog from {}: kept {} entries", sourceUrl, kept);
		} catch (Exception ex) {
			log.warn(
					"Could not refresh the pricing catalog from {}: {}",
					sourceUrl, ex.getMessage()
			);
		}
	}

	/**
	 * Fetches the catalog with exponential backoff on transport failures (connect/DNS/
	 * request timeouts). Parse errors fail fast: retrying a malformed document is pointless.
	 * Interrupts stop the schedule immediately with the flag restored.
	 *
	 * @return parsed catalog root
	 * @throws IOException          when the fetch fails terminally or is interrupted
	 * @throws InterruptedException when the backoff sleep is interrupted
	 */
	JsonNode fetchCatalogWithRetry() throws IOException, InterruptedException {
		int attempt = 0;
		while (true) {
			attempt++;
			try {
				return fetchCatalog();
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw interrupted;
			} catch (HttpTimeoutException | ConnectException | UnknownHostException ex) {
				if (attempt >= maxAttempts) {
					throw new IOException(
							"pricing fetch failed after " + attempt + " attempts", ex);
				}
				sleepBackoff(attempt, ex);
			}
		}
	}

	private void sleepBackoff(int attempt, IOException cause) throws InterruptedException {
		long baseMillis = backoffBase.toMillis() << Math.min(attempt - 1, 10);
		long jitter = ThreadLocalRandom.current().nextLong(0, baseMillis / 5 + 1);
		long delayMillis = Math.min(baseMillis + jitter, Duration.ofMinutes(5).toMillis());
		log.warn("Pricing fetch attempt {}/{} failed ({}); retrying in {}ms",
				attempt, maxAttempts, cause.getMessage(), delayMillis);
		Thread.sleep(delayMillis);
	}

	int upsert(JsonNode root) {
		int kept = 0;
		for (Map.Entry<String, JsonNode> field : root.properties()) {
			JsonNode entry = field.getValue();
			if (entry == null || !entry.isObject()) {
				continue;
			}
			String provider = entry.path("litellm_provider").asString("");
			String mode = entry.path("mode").asString("");
			if (!"chat".equals(mode) && !"ollama".equals(provider)) {
				continue;
			}
			BigDecimal inputCost = decimalOrDefault(entry, "input_cost_per_token");
			BigDecimal outputCost = decimalOrDefault(entry, "output_cost_per_token");
			repository.upsert(
					field.getKey(),
					provider.isBlank() ? "unknown" : provider,
					mode.isBlank() ? "chat" : mode,
					inputCost,
					outputCost,
					decimalOrDefault(entry, "cache_read_input_token_cost"),
					decimalOrDefault(entry, "cache_creation_input_token_cost"),
					longOrDefault(entry, "max_input_tokens"),
					longOrDefault(entry, "max_output_tokens"),
					sourceUrl
			);
			kept++;
		}
		return kept;
	}

	private JsonNode fetchCatalog() throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(sourceUrl))
		                                 .timeout(fetchTimeout)
		                                 .GET()
		                                 .build();
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IllegalStateException("catalog fetch returned HTTP " + response.statusCode());
		}
		return objectMapper.readTree(response.body());
	}

	/**
	 * The catalog values are nullable; the repository binds non null parameters and translates zero back to SQL NULL
	 * for the optional columns.
	 */
	private static BigDecimal decimalOrDefault(JsonNode entry, String field) {
		JsonNode node = entry.get(field);
		return node != null && node.isNumber() ? node.decimalValue() : BigDecimal.ZERO;
	}

	private static long longOrDefault(JsonNode entry, String field) {
		JsonNode node = entry.get(field);
		if (node == null || !node.isIntegralNumber()) {
			return 0;
		}
		return Math.max(0, node.asLong());
	}
}