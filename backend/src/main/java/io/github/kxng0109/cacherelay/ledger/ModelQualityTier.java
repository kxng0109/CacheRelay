package io.github.kxng0109.cacherelay.ledger;

/**
 * Admin-curated quality tier of one model.
 *
 * <p>Tiers are quality-first by construction: routing policy may use cost as a
 * tie-breaker within a tier, never as a reason to cross one. Price is never a
 * quality signal, so tiers are curated by administrators with benchmark
 * references attached, never derived from cost.</p>
 */
public enum ModelQualityTier {

	/**
	 * Frontier-capability models: the quality ceiling for demanding work.
	 */
	FRONTIER,

	/**
	 * Capable general-purpose models: the default band for everyday work.
	 */
	STANDARD,

	/**
	 * Budget models: acceptable for constrained, low-stakes work only.
	 */
	BUDGET
}
