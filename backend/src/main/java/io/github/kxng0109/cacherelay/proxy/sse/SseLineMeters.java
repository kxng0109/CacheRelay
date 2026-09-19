package io.github.kxng0109.cacherelay.proxy.sse;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Shared Micrometer meters for SSE line guards (PERF-06): one set per
 * (provider, action) built once and handed to every guard, instead of seven
 * builder+register calls per stream. Values are identical to per-guard
 * registration (the registry dedupes by id); only the construction churn is gone.
 *
 * @param lineRejectedTooLong  lines rejected for exceeding the byte limit
 * @param lineRejectedLineRate lines rejected by the per-line rate limiter
 * @param lineRejectedByteRate lines rejected by the per-byte rate limiter
 * @param upstreamCancelled    upstream streams cancelled by the guard
 * @param streamDurationOk     completed stream lifetimes
 * @param streamDurationAborted aborted stream lifetimes
 * @param lineLengthBytes      observed line lengths
 * @since 1.7.0
 */
public record SseLineMeters(
		Counter lineRejectedTooLong,
		Counter lineRejectedLineRate,
		Counter lineRejectedByteRate,
		Counter upstreamCancelled,
		Timer streamDurationOk,
		Timer streamDurationAborted,
		DistributionSummary lineLengthBytes
) {

	/**
	 * Builds one meter set for the given provider and action.
	 *
	 * @param registry     meter registry
	 * @param providerName provider tag value
	 * @param action       action tag value
	 * @return the built set
	 */
	public static SseLineMeters create(
			MeterRegistry registry, String providerName, SseLineGuard.Action action) {
		String actionName = action == null
				? SseLineGuard.Action.REJECT_LINE_AND_CLOSE.name()
				: action.name();
		return new SseLineMeters(
				Counter.builder("sse.line.rejected.count")
				       .description(
						       "Number of SSE lines rejected because they exceeded the maximum byte length")
				       .tag("provider", providerName)
				       .tag("reason", "LINE_TOO_LONG")
				       .tag("action", actionName)
				       .register(registry),
				Counter.builder("sse.line.rejected.count")
				       .description(
					       "Number of SSE lines rejected because they exceeded the per-line rate limit")
				       .tag("provider", providerName)
				       .tag("reason", "LINE_RATE_LIMIT")
				       .tag("action", actionName)
				       .register(registry),
				Counter.builder("sse.line.rejected.count")
				       .description(
					       "Number of SSE lines rejected because they exceeded the per-byte rate limit")
				       .tag("provider", providerName)
				       .tag("reason", "BYTE_RATE_LIMIT")
				       .tag("action", actionName)
				       .register(registry),
				Counter.builder("sse.upstream.cancelled.count")
				       .description("Number of upstream streams cancelled by the line guard")
				       .tag("provider", providerName)
				       .register(registry),
				Timer.builder("sse.stream.duration.seconds")
				     .description("Total SSE relay stream lifetime for streams that completed normally")
				     .publishPercentileHistogram()
				     .tag("provider", providerName)
				     .tag("status", "ok")
				     .register(registry),
				Timer.builder("sse.stream.duration.seconds")
				     .description("Total SSE relay stream lifetime for streams that were aborted")
				     .publishPercentileHistogram()
				     .tag("provider", providerName)
				     .tag("status", "aborted")
				     .register(registry),
				DistributionSummary.builder("sse.line.length.bytes")
				                   .description("Distribution of accepted SSE line byte lengths")
				                   .baseUnit("bytes")
				                   .tag("provider", providerName)
				                   .register(registry)
		);
	}
}
