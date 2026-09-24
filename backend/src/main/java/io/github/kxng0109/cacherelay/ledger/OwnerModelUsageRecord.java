package io.github.kxng0109.cacherelay.ledger;

/**
 * One exact detail grain: mergeable sums for a single owner, provider, and
 * model triple inside a queried window.
 *
 * <p>Every measure is a sum, so dashboard rollups merge grains with plain
 * addition and recompute averages from the merged duration sum — never an
 * average of averages. Buckets persist these grains per settled day; live
 * windows query them straight from the ledger.</p>
 *
 * @param ownerId          owner of the virtual API keys that authenticated the requests
 * @param provider         upstream provider name
 * @param model            upstream model identifier
 * @param requests         request count in the grain
 * @param promptTokens     prompt token sum in the grain
 * @param completionTokens completion token sum in the grain
 * @param totalTokens      total token sum in the grain
 * @param costMicros       list-cost sum in micro dollars
 * @param billedMicros     billed-cost sum in micro dollars
 * @param effectiveMicros  effective-cost sum in micro dollars
 * @param durationSumMs    duration sum in milliseconds (averages recompute from this)
 * @param cacheReadTokens  cache-read token sum in the grain
 * @param cacheWriteTokens cache-write token sum in the grain
 * @param uncachedTokens   uncached token sum in the grain
 * @param reasoningTokens  reasoning token sum in the grain
 */
public record OwnerModelUsageRecord(
		String ownerId,
		String provider,
		String model,
		long requests,
		long promptTokens,
		long completionTokens,
		long totalTokens,
		long costMicros,
		long billedMicros,
		long effectiveMicros,
		long durationSumMs,
		long cacheReadTokens,
		long cacheWriteTokens,
		long uncachedTokens,
		long reasoningTokens
) {
}
