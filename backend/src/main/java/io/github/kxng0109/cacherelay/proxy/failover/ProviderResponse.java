package io.github.kxng0109.cacherelay.proxy.failover;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.stream.Stream;

/**
 * The outcome of a successful provider attempt: which provider won and its streaming HTTP response ready to relay to
 * the client.
 *
 * <p>The response body is a lazy {@link Stream} of SSE lines produced by
 * {@code BodyHandlers.ofLines()}. The wiring layer starts consuming it only after the status code and content type have
 * been validated, so failover can never happen mid stream.</p>
 *
 * @param providerName   name of the provider that answered
 * @param response       the upstream response whose body streams SSE lines
 * @param triedProviders every provider touched for this request in walk order, including
 *                       the winner (entries carry reason suffixes for skipped/failed legs)
 */
public record ProviderResponse(
		String providerName,
		HttpResponse<Stream<String>> response,
		List<String> triedProviders
) {
}