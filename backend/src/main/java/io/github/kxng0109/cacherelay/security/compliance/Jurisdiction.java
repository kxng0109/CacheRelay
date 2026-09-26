package io.github.kxng0109.cacherelay.security.compliance;

import java.util.Set;

/**
 * Regulatory jurisdictions and adequacy decision compatibility DAG.
 */
public enum Jurisdiction {
	EU,
	US,
	NG,
	UK,
	CH,
	CA,
	ZA,
	GH,
	GLOBAL;

	private static final Set<Jurisdiction> EU_ADEQUATE = Set.of(EU, CH, UK, CA);
	private static final Set<Jurisdiction> NG_ADEQUATE = Set.of(NG, EU, UK, ZA, GH);
	private static final Set<Jurisdiction> UK_ADEQUATE = Set.of(UK, EU, CH);
	private static final Set<Jurisdiction> US_ADEQUATE = Set.of(US);
	private static final Set<Jurisdiction> CH_ADEQUATE = Set.of(CH, EU);

	/**
	 * Determines if a target jurisdiction satisfies adequacy requirements for the given origin.
	 *
	 * <p>An unknown ({@code GLOBAL}-fallback) target never satisfies a regulated
	 * origin: providers that cannot be placed in a jurisdiction must not serve
	 * residency-constrained tenants. An unconstrained ({@code GLOBAL}) origin
	 * accepts every target.</p>
	 *
	 * @param origin source regulatory jurisdiction
	 * @param target destination provider jurisdiction
	 * @return {@code true} if legally compliant
	 */
	public static boolean isAdequate(Jurisdiction origin, Jurisdiction target) {
		if (origin == null || target == null || origin == GLOBAL) {
			return true;
		}
		if (target == GLOBAL) {
			return false;
		}
		if (origin == target) {
			return true;
		}

		return switch (origin) {
			case EU -> EU_ADEQUATE.contains(target);
			case NG -> NG_ADEQUATE.contains(target);
			case UK -> UK_ADEQUATE.contains(target);
			case US -> US_ADEQUATE.contains(target);
			case CH -> CH_ADEQUATE.contains(target);
			default -> false;
		};
	}
}
