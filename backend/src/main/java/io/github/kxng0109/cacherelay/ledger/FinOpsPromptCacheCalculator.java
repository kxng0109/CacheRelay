package io.github.kxng0109.cacherelay.ledger;

import io.github.kxng0109.cacherelay.contracts.ProviderType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Enterprise FinOps engine computing granular prompt caching cost breakdowns and savings in micro-dollars.
 *
 * <p>Supports explicit provider pricing contracts as well as canonical vendor cache discount multipliers
 * (Anthropic 1.25x write / 0.10x read, OpenAI 0.50x read, DeepSeek 0.10x read) without floating point drift.</p>
 *
 * <p>Rate resolution, micros conversion, and token clamping are single-sourced from
 * {@link CostCalculator} (FIN-B29): this class owns only the list/billed/effective/savings
 * decomposition, never the rates themselves.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinOpsPromptCacheCalculator {

	/**
	 * Micro-dollars per US dollar constant, single-sourced from {@link CostCalculator}
	 * (FIN-B29). Retained as an alias for existing callers.
	 */
	public static final BigDecimal MICRO_DOLLARS_PER_DOLLAR = CostCalculator.MICRO_DOLLARS_PER_DOLLAR;

	private final ModelPriceCatalog catalog;

	/**
	 * Detailed FinOps calculation result in micro-dollars (FOCUS 1.4 cost taxonomy).
	 *
	 * @param listCostMicros      standard list cost without caching discounts (FOCUS 1.4 List Cost)
	 * @param effectiveCostMicros effective cost for the charge period; equals billed cost here because no
	 *                            covering charges are amortized (FOCUS 1.4 Effective Cost)
	 * @param billedCostMicros    final invoiced cost after cache read discounts and write surcharges
	 *                            (FOCUS 1.4 Billed Cost)
	 * @param cacheSavingsMicros  signed delta (list minus billed); negative when caching costs more than
	 *                            it saves (e.g. Anthropic 1.25x write surcharge on a cold-cache write)
	 */
	public record FinOpsCostBreakdown(
			long listCostMicros,
			long effectiveCostMicros,
			long billedCostMicros,
			long cacheSavingsMicros
	) {
		/**
		 * Empty zero-cost breakdown sentinel.
		 */
		public static final FinOpsCostBreakdown ZERO = new FinOpsCostBreakdown(0L, 0L, 0L, 0L);
	}

	/**
	 * Computes the granular FinOps cost breakdown for a request.
	 *
	 * @param type                 provider dialect
	 * @param model                model identifier
	 * @param totalPromptTokens    total prompt tokens reported
	 * @param completionTokens     output tokens reported
	 * @param uncachedPromptTokens uncached prompt tokens
	 * @param cacheReadTokens      prompt tokens read from cache
	 * @param cacheWriteTokens     prompt tokens written to cache
	 * @return calculated breakdown in micro-dollars
	 */
	public FinOpsCostBreakdown calculateBreakdown(
			ProviderType type,
			String model,
			long totalPromptTokens,
			long completionTokens,
			long uncachedPromptTokens,
			long cacheReadTokens,
			long cacheWriteTokens
	) {
		ModelPricingEntry entry = catalog.lookup(type, model).orElse(null);
		if (entry == null) {
			log.warn("No pricing entry for provider {} model {}; recording zero cost", type, model);
			return FinOpsCostBreakdown.ZERO;
		}

		BigDecimal baseInputRate = entry.inputCostPerToken();
		BigDecimal baseOutputRate = entry.outputCostPerToken();

		long safeTotal = CostCalculator.clampNonNegative(totalPromptTokens);
		long safeCompletion = CostCalculator.clampNonNegative(completionTokens);
		long safeUncached = CostCalculator.clampNonNegative(uncachedPromptTokens);
		long safeRead = CostCalculator.clampNonNegative(cacheReadTokens);
		long safeWrite = CostCalculator.clampNonNegative(cacheWriteTokens);

		// 1. Standard list cost without cache optimization
		BigDecimal listInputCost = BigDecimal.valueOf(safeTotal).multiply(baseInputRate);
		BigDecimal listOutputCost = BigDecimal.valueOf(safeCompletion).multiply(baseOutputRate);
		long listCostMicros = CostCalculator.toMicrosSaturating(listInputCost.add(listOutputCost));

		// 2. Resolve cache write and read rates (single-sourced)
		BigDecimal writeRate = CostCalculator.writeRateFor(type, entry, baseInputRate);
		BigDecimal readRate = CostCalculator.readRateFor(type, entry, baseInputRate);

		// 3. Compute granular cached input cost
		BigDecimal uncachedCost = BigDecimal.valueOf(safeUncached).multiply(baseInputRate);
		BigDecimal writeCost = BigDecimal.valueOf(safeWrite).multiply(writeRate);
		BigDecimal readCost = BigDecimal.valueOf(safeRead).multiply(readRate);
		BigDecimal billedInputCost = uncachedCost.add(writeCost).add(readCost);

		long billedCostMicros = CostCalculator.toMicrosSaturating(billedInputCost.add(listOutputCost));

		// Effective cost (FOCUS 1.4): equals Billed Cost here because this per-request cache ledger
		// has no covering/covered charge pairs. Computed explicitly (not aliased) so the invariant
		// is self-documenting and cannot be silently broken by a positional constructor argument.
		long effectiveCostMicros = computeEffectiveCostMicros(billedCostMicros);

		// Cache savings: SIGNED delta. A write-surcharge (e.g. Anthropic 1.25x on a cold-cache write)
		// can make billed exceed list; clamping to zero would hide a real cost increase.
		long cacheSavingsMicros = listCostMicros - billedCostMicros;

		return new FinOpsCostBreakdown(listCostMicros, effectiveCostMicros, billedCostMicros, cacheSavingsMicros);
	}

	/**
	 * Effective Cost for a per-request cache charge (FOCUS 1.4).
	 *
	 * <p>Effective Cost equals Billed Cost when the charge is a usage charge that is not covered by other
	 * eligible charges. It differs from Billed Cost only when covering charges (e.g. prepaid commitment purchases) are
	 * amortized onto the covered usage. This calculator models a single request with no covering/covered charge
	 * relationship, so Effective Cost collapses to Billed Cost. The method exists so that invariant is named, tested,
	 * and cannot be silently aliased.</p>
	 */
	private static long computeEffectiveCostMicros(long billedCostMicros) {
		return billedCostMicros;
	}
}
