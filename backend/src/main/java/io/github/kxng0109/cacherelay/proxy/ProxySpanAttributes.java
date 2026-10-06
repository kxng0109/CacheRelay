package io.github.kxng0109.cacherelay.proxy;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;

/**
 * Custom span attributes for proxy request spans.
 *
 * <p>Controllers record routing facts as servlet request attributes at each
 * outcome point (cache hit, miss, deny, error) and tag the current server span
 * at controller time via {@link #tagCurrentSpan} (inside the server observation
 * scope). Attribute values carry only routing metadata (alias, tier, provider,
 * budget micros) — never prompts, completions, keys, or payloads.</p>
 */
public final class ProxySpanAttributes {

	/** Requested alias key (the catalog name, not the served model). */
	public static final String ALIAS = "cacherelay.alias";
	/** Served model name. */
	public static final String MODEL = "cacherelay.model";
	/** Cache routing: {@code hit} or {@code miss}. */
	public static final String CACHE_OUTCOME = "cacherelay.cache.outcome";
	/** Hit tier: {@code l0-memory}, {@code l1-exact}, {@code l2-semantic}, or {@code replay}. */
	public static final String CACHE_TIER = "cacherelay.cache.tier";
	/** L2 cosine similarity, formatted to 4 decimals. */
	public static final String CACHE_SIMILARITY = "cacherelay.cache.similarity";
	/** Winning provider name. */
	public static final String PROVIDER = "cacherelay.provider";
	/** Number of providers touched in walk order. */
	public static final String PROVIDERS_TRIED = "cacherelay.providers.tried";
	/**
	 * Budget hold in micro-units, as charged at admission. A pre-spend
	 * estimate, not the settled cost (stream-end true-up lands in the ledger).
	 */
	public static final String BUDGET_HOLD_MICROS = "cacherelay.budget.hold_micros";
	/** Request outcome: {@code ok} or {@code error}. */
	public static final String OUTCOME = "cacherelay.outcome";
	/** Machine-readable error reason (e.g. {@code unknown_model}). */
	public static final String ERROR_REASON = "cacherelay.error.reason";
	/** Upstream HTTP status on provider-side failures. */
	public static final String UPSTREAM_STATUS = "cacherelay.upstream.status";

	/** Outcome values. */
	public static final String OUTCOME_OK = "ok";
	/** Outcome values. */
	public static final String OUTCOME_ERROR = "error";

	private static final String ATTRIBUTE_PREFIX = "cacherelay.span.";

	private ProxySpanAttributes() {
	}

	/**
	 * Records the requested alias and served model for the request.
	 *
	 * @param request current request, never {@code null}
	 * @param alias   alias key, blank values are skipped
	 * @param model   served model, blank values are skipped
	 */
	public static void markRouting(HttpServletRequest request, @Nullable String alias,
			@Nullable String model) {
		put(request, ALIAS, alias);
		put(request, MODEL, model);
	}

	/**
	 * Records a cache hit with its tier and similarity score.
	 *
	 * @param request    current request, never {@code null}
	 * @param tier       hit tier label, blank values are skipped
	 * @param similarity cosine similarity, NaN values are skipped
	 */
	public static void markCacheHit(HttpServletRequest request, @Nullable String tier,
			double similarity) {
		put(request, CACHE_OUTCOME, "hit");
		put(request, CACHE_TIER, tier);
		if (!Double.isNaN(similarity)) {
			request.setAttribute(ATTRIBUTE_PREFIX + CACHE_SIMILARITY,
					String.format(Locale.ROOT, "%.4f", similarity));
		}
		put(request, OUTCOME, OUTCOME_OK);
	}

	/**
	 * Records a successful miss served by a provider.
	 *
	 * @param request    current request, never {@code null}
	 * @param provider   winning provider, blank values are skipped
	 * @param tried      providers touched, negative values are skipped
	 * @param holdMicros admission hold in micro-units, negative values are skipped
	 */
	public static void markMiss(HttpServletRequest request, @Nullable String provider,
			int tried, long holdMicros) {
		put(request, CACHE_OUTCOME, "miss");
		put(request, PROVIDER, provider);
		if (tried >= 0) {
			request.setAttribute(ATTRIBUTE_PREFIX + PROVIDERS_TRIED, Integer.toString(tried));
		}
		if (holdMicros >= 0) {
			request.setAttribute(ATTRIBUTE_PREFIX + BUDGET_HOLD_MICROS, Long.toString(holdMicros));
		}
		put(request, OUTCOME, OUTCOME_OK);
	}

	/**
	 * Records a failed request with a machine-readable reason.
	 *
	 * @param request current request, never {@code null}
	 * @param reason  error reason, blank values are skipped
	 */
	public static void markError(HttpServletRequest request, @Nullable String reason) {
		put(request, OUTCOME, OUTCOME_ERROR);
		put(request, ERROR_REASON, reason);
	}

	/**
	 * Records a provider-side failure with its status code.
	 *
	 * @param request  current request, never {@code null}
	 * @param provider failing provider, blank values are skipped
	 * @param status   upstream HTTP status
	 */
	public static void markProviderError(HttpServletRequest request, @Nullable String provider,
			int status) {
		put(request, PROVIDER, provider);
		request.setAttribute(ATTRIBUTE_PREFIX + UPSTREAM_STATUS,
				Integer.toString(status));
		markError(request, "upstream_error");
	}

	/**
	 * Tags the current server span with the request's recorded attributes.
	 * Must run at controller time (inside the server observation scope);
	 * post-chain filters are outside it. Null-safe: a {@code null} tracer
	 * (unit-constructed controllers) or no active span is a silent no-op.
	 *
	 * @param tracer  observation tracer, or {@code null} to skip
	 * @param request current request, never {@code null}
	 */
	public static void tagCurrentSpan(@Nullable Tracer tracer, HttpServletRequest request) {
		if (tracer == null) {
			return;
		}
		applyTo(tracer.currentSpan(), collect(request));
	}

	/**
	 * Collects the recorded attributes for the request.
	 *
	 * @param request current request, never {@code null}
	 * @return attribute key to string value, never {@code null}
	 */
	public static Map<String, String> collect(HttpServletRequest request) {
		Map<String, String> tags = new LinkedHashMap<>();
		collectInto(request, tags, ALIAS);
		collectInto(request, tags, MODEL);
		collectInto(request, tags, CACHE_OUTCOME);
		collectInto(request, tags, CACHE_TIER);
		collectInto(request, tags, CACHE_SIMILARITY);
		collectInto(request, tags, PROVIDER);
		collectInto(request, tags, PROVIDERS_TRIED);
		collectInto(request, tags, BUDGET_HOLD_MICROS);
		collectInto(request, tags, OUTCOME);
		collectInto(request, tags, ERROR_REASON);
		collectInto(request, tags, UPSTREAM_STATUS);
		return tags;
	}

	/**
	 * Applies collected attributes to a span. Null-safe: a {@code null} span
	 * (no active observation, e.g. unit-constructed filters) is a no-op.
	 *
	 * @param span  span to tag, or {@code null} for no-op
	 * @param tags  attributes from {@link #collect}, never {@code null}
	 */
	public static void applyTo(@Nullable Span span, Map<String, String> tags) {
		if (span == null) {
			return;
		}
		for (Map.Entry<String, String> tag : tags.entrySet()) {
			span.tag(tag.getKey(), tag.getValue());
		}
	}

	private static void put(HttpServletRequest request, String key, @Nullable String value) {
		if (value != null && !value.isBlank()) {
			request.setAttribute(ATTRIBUTE_PREFIX + key, value);
		}
	}

	private static void collectInto(HttpServletRequest request, Map<String, String> tags,
			String key) {
		Object value = request.getAttribute(ATTRIBUTE_PREFIX + key);
		if (value instanceof String text && !text.isBlank()) {
			tags.put(key, text);
		}
	}
}
