package io.github.kxng0109.cacherelay.proxy;

import java.util.Locale;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import io.github.kxng0109.cacherelay.ledger.ModelQualityTier;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Quality routing preferences carried on a chat request.
 *
 * <p>Phase 1 carries preferences for observation only: values are validated
 * fail-fast and logged by the decision writer, never enforced. Effective
 * policy stays quality-first regardless of what is requested.</p>
 *
 * @param minQualityTier requested quality floor tier name, may be {@code null}
 * @param tradeoffMode   requested tradeoff mode as received ({@code quality} or {@code eco})
 */
public record RoutingDecisionContext(
		@Nullable String minQualityTier,
		String tradeoffMode
) {

	/**
	 * Request header carrying the quality floor tier name.
	 */
	public static final String MIN_QUALITY_TIER_HEADER = "X-CacheRelay-Min-Quality-Tier";

	/**
	 * Request header carrying the tradeoff mode.
	 */
	public static final String TRADEOFF_MODE_HEADER = "X-CacheRelay-Tradeoff-Mode";

	/**
	 * Tradeoff mode used when the client sends none.
	 */
	public static final String DEFAULT_TRADEOFF_MODE = "quality";

	/**
	 * Parses and validates the routing headers of one request. Unknown tier or
	 * mode names fail fast: accepting unparsable policy input would poison the
	 * decision log.
	 *
	 * @param request the incoming chat request
	 * @return the parsed context, defaults filled in
	 * @throws ResponseStatusException HTTP 400 when a header value is unknown
	 */
	public static RoutingDecisionContext fromRequest(HttpServletRequest request) {
		String tier = request.getHeader(MIN_QUALITY_TIER_HEADER);
		String normalizedTier = null;
		if (tier != null && !tier.isBlank()) {
			try {
				normalizedTier = ModelQualityTier.valueOf(tier.trim().toUpperCase(Locale.ROOT)).name();
			} catch (IllegalArgumentException unknown) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown min quality tier");
			}
		}
		String mode = request.getHeader(TRADEOFF_MODE_HEADER);
		String normalizedMode = DEFAULT_TRADEOFF_MODE;
		if (mode != null && !mode.isBlank()) {
			String lowered = mode.trim().toLowerCase(Locale.ROOT);
			if (!DEFAULT_TRADEOFF_MODE.equals(lowered) && !"eco".equals(lowered)) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown tradeoff mode");
			}
			normalizedMode = lowered;
		}
		return new RoutingDecisionContext(normalizedTier, normalizedMode);
	}

	/**
	 * @return the default context: no floor, quality tradeoff
	 */
	public static RoutingDecisionContext defaults() {
		return new RoutingDecisionContext(null, DEFAULT_TRADEOFF_MODE);
	}
}
