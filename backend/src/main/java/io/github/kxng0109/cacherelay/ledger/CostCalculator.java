package io.github.kxng0109.cacherelay.ledger;

import io.github.kxng0109.cacherelay.contracts.ProviderType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Computes the cost of one completed request from its token counts.
 *
 * <p>All arithmetic is {@link BigDecimal}; money never touches floating
 * point. The result is expressed as micro dollars (one millionth of a US dollar) in a {@code long}, which is how the
 * ledger stores it, and follows the standard formula of input tokens times the input price plus output tokens times the
 * output price. Unknown models cost nothing and log a warning, because an untracked model must never fail a
 * request.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CostCalculator {

	/**
	 * Micro dollars per dollar.
	 */
	static final BigDecimal MICRO_DOLLARS_PER_DOLLAR = new BigDecimal("1000000");

	private final ModelPriceCatalog catalog;

	/**
	 * Clamps a token count fail-safe (FIN-B09): negative upstream counts never credit.
	 *
	 * @param tokens raw count
	 * @return zero or the count
	 */
	static long clampNonNegative(long tokens) {
		return Math.max(0L, tokens);
	}

	/**
	 * Converts dollars to whole micro dollars, saturating on overflow (FIN-B26).
	 *
	 * @param dollarAmount dollar amount
	 * @return micro dollars, or {@code Long.MAX_VALUE} on overflow
	 */
	static long toMicrosSaturating(BigDecimal dollarAmount) {
		try {
			return dollarAmount.multiply(MICRO_DOLLARS_PER_DOLLAR)
			                   .setScale(0, RoundingMode.HALF_UP)
			                   .longValueExact();
		} catch (ArithmeticException overflow) {
			log.warn("Cost overflow; saturating to Long.MAX_VALUE");
			return Long.MAX_VALUE;
		}
	}

	/**
	 * Shared cache write-rate resolution (FIN-B29 single source): explicit catalog
	 * rate wins, else the canonical vendor multiplier.
	 *
	 * @param type      provider dialect
	 * @param entry     pricing entry
	 * @param baseRate  base input rate
	 * @return write rate per token
	 */
	static BigDecimal writeRateFor(ProviderType type, ModelPricingEntry entry, BigDecimal baseRate) {
		if (entry.cacheCreationInputTokenCost() != null) {
			return entry.cacheCreationInputTokenCost();
		}
		if (type == ProviderType.ANTHROPIC) {
			return baseRate.multiply(ANTHROPIC_WRITE_MULTIPLIER);
		}
		if (type == ProviderType.DEEPSEEK) {
			return BigDecimal.ZERO;
		}
		return baseRate;
	}

	/**
	 * Shared cache read-rate resolution (FIN-B29 single source): explicit catalog
	 * rate wins, else the canonical vendor multiplier.
	 *
	 * @param type      provider dialect
	 * @param entry     pricing entry
	 * @param baseRate  base input rate
	 * @return read rate per token
	 */
	static BigDecimal readRateFor(ProviderType type, ModelPricingEntry entry, BigDecimal baseRate) {
		if (entry.cacheReadInputTokenCost() != null) {
			return entry.cacheReadInputTokenCost();
		}
		if (type == ProviderType.ANTHROPIC) {
			return baseRate.multiply(ANTHROPIC_READ_MULTIPLIER);
		}
		if (type == ProviderType.OPENAI) {
			return baseRate.multiply(OPENAI_READ_MULTIPLIER);
		}
		if (type == ProviderType.DEEPSEEK) {
			return baseRate.multiply(DEEPSEEK_READ_MULTIPLIER);
		}
		return baseRate;
	}

	/**
	 * @param type             provider dialect that served the request
	 * @param model            model id reported by the provider
	 * @param promptTokens     input tokens
	 * @param completionTokens output tokens
	 * @return the cost in micro dollars, rounded half up
	 */
	public long calculate(ProviderType type, String model, long promptTokens, long completionTokens) {
		return calculate(type, model, promptTokens, completionTokens, promptTokens, 0L, 0L);
	}

	/**
	 * Computes request cost with prompt caching discounts and write surcharges.
	 *
	 * @param type                 provider dialect
	 * @param model                model id reported by the provider
	 * @param totalPromptTokens    total prompt tokens
	 * @param completionTokens     output tokens
	 * @param uncachedPromptTokens uncached prompt tokens
	 * @param cacheReadTokens      prompt tokens read from cache
	 * @param cacheWriteTokens     prompt tokens written to cache
	 * @return the billed cost in micro dollars, rounded half up
	 */
	public long calculate(
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
			log.warn(
					"No pricing entry for provider {} model {}; recording zero cost",
					type, model
			);
			return 0;
		}

		// FIN-B09: token counts arrive from upstream usage payloads and must never go
		// negative — a negative count would record a cost credit. Clamp fail-safe.
		long safePrompt = clampNonNegative(totalPromptTokens);
		long safeCompletion = clampNonNegative(completionTokens);
		long safeUncached = clampNonNegative(uncachedPromptTokens);
		long safeRead = clampNonNegative(cacheReadTokens);
		long safeWrite = clampNonNegative(cacheWriteTokens);
		if (safePrompt != totalPromptTokens || safeCompletion != completionTokens
				|| safeUncached != uncachedPromptTokens || safeRead != cacheReadTokens
				|| safeWrite != cacheWriteTokens) {
			log.warn(
					"Negative token counts from provider {} model {} clamped to zero",
					type, model
			);
		}

		BigDecimal baseInputRate = entry.inputCostPerToken();
		BigDecimal baseOutputRate = entry.outputCostPerToken();

		BigDecimal uncachedCost = BigDecimal.valueOf(safeUncached).multiply(baseInputRate);
		BigDecimal writeCost = BigDecimal.valueOf(safeWrite)
		                                 .multiply(writeRateFor(type, entry, baseInputRate));
		BigDecimal readCost = BigDecimal.valueOf(safeRead)
		                                .multiply(readRateFor(type, entry, baseInputRate));
		BigDecimal outputCost = BigDecimal.valueOf(safeCompletion).multiply(baseOutputRate);

		return toMicrosSaturating(
				uncachedCost.add(writeCost).add(readCost).add(outputCost));
	}

	/**
	 * Precomputed cache multipliers (PERF-09): the string constructor parses on every
	 * call, so these are hoisted. Billing math itself stays {@link BigDecimal} —
	 * precision is not traded for speed.
	 */
	private static final BigDecimal ANTHROPIC_WRITE_MULTIPLIER = new BigDecimal("1.25");
	private static final BigDecimal ANTHROPIC_READ_MULTIPLIER = new BigDecimal("0.10");
	private static final BigDecimal OPENAI_READ_MULTIPLIER = new BigDecimal("0.50");
	private static final BigDecimal DEEPSEEK_READ_MULTIPLIER = new BigDecimal("0.10");
}