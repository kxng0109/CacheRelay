package io.github.kxng0109.cacherelay.proxy.failover;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.kxng0109.cacherelay.contracts.ProviderConfig;
import io.github.kxng0109.cacherelay.contracts.ProviderRef;
import io.github.kxng0109.cacherelay.ledger.ModelPriceCatalog;
import io.github.kxng0109.cacherelay.ledger.ModelQualityCatalog;
import io.github.kxng0109.cacherelay.ledger.ModelQualityTier;
import org.jspecify.annotations.Nullable;

/**
 * Cost-aware chain ordering for economy routing.
 *
 * <p>Applied strictly after residency filtering (compliance is a constraint,
 * never a preference) and key-allowlist narrowing, and only when the request
 * carries {@code eco} tradeoff mode: legs are floored by the requested quality
 * tier first, then ordered cheapest-first by input rate. Unpriced legs never
 * win on price (they sort last with an unknown-price flag); unrated legs fail
 * closed under any requested floor. Quality mode and RACE strategy pass
 * through untouched.</p>
 */
public final class CostAwareChainOrder {

	private CostAwareChainOrder() {
	}

	/**
	 * Result of cost ordering: the reordered chain plus whether any leg was
	 * excluded or deprioritized for unknown price or quality.
	 *
	 * @param chain        reordered chain, never {@code null}
	 * @param unknownPrice whether any leg lacked a price
	 * @param unrated      whether any leg lacked a quality rating
	 */
	public record OrderedChain(List<ProviderRef> chain, boolean unknownPrice, boolean unrated) {
	}

	/**
	 * Orders an already-compliant chain for economy routing.
	 *
	 * @param chain       residency-filtered, allowlist-narrowed chain, never {@code null}
	 * @param floor       requested quality floor, or {@code null} for no floor
	 * @param eco         whether economy ordering applies
	 * @param providers   configured providers for dialect resolution, never {@code null}
	 * @param prices      price catalog, or {@code null} (no ordering without rates)
	 * @param qualities   quality catalog, or {@code null} (unrated legs fail closed under a floor)
	 * @param servedModel model name used for tier lookups
	 * @return reordered chain with unknown flags
	 */
	public static OrderedChain order(
			List<ProviderRef> chain,
			@Nullable ModelQualityTier floor,
			boolean eco,
			Map<String, ProviderConfig> providers,
			@Nullable ModelPriceCatalog prices,
			@Nullable ModelQualityCatalog qualities,
			String servedModel
	) {
		if (!eco || prices == null) {
			return new OrderedChain(List.copyOf(chain), false, false);
		}
		List<ScoredLeg> scored = new ArrayList<>(chain.size());
		boolean unknownPrice = false;
		boolean unrated = false;
		for (ProviderRef ref : chain) {
			Optional<BigDecimal> rate = inputRateOf(providers, prices, ref, servedModel);
			boolean rated = !isUnrated(
					ref.modelOverride() != null ? ref.modelOverride() : servedModel, qualities);
			if (rate.isEmpty()) {
				unknownPrice = true;
				boolean excluded = !passesFloor(ref, servedModel, floor, qualities);
				scored.add(new ScoredLeg(ref, null, excluded));
				unrated = unrated || !rated;
				continue;
			}
			if (!passesFloor(ref, servedModel, floor, qualities)) {
				unrated = true;
				continue;
			}
			scored.add(new ScoredLeg(ref, rate.get(), false));
		}
		List<ScoredLeg> kept = new ArrayList<>();
		for (ScoredLeg leg : scored) {
			if (!leg.excluded()) {
				kept.add(leg);
			}
		}
		kept.sort(Comparator.comparing(ScoredLeg::rate,
				Comparator.nullsLast(Comparator.naturalOrder())));
		List<ProviderRef> ordered = new ArrayList<>(kept.size());
		for (ScoredLeg leg : kept) {
			ordered.add(leg.ref());
		}
		return new OrderedChain(ordered, unknownPrice, unrated);
	}

	private static Optional<BigDecimal> inputRateOf(
			Map<String, ProviderConfig> providers,
			ModelPriceCatalog prices,
			ProviderRef ref,
			String servedModel
	) {
		String effective = ref.modelOverride() != null ? ref.modelOverride() : servedModel;
		ProviderConfig config = providers.get(ref.providerName());
		if (config == null || config.type() == null) {
			return Optional.empty();
		}
		return prices.lookup(config.type(), effective).map(entry -> entry.inputCostPerToken());
	}

	private static boolean passesFloor(
			ProviderRef ref,
			String servedModel,
			@Nullable ModelQualityTier floor,
			@Nullable ModelQualityCatalog qualities
	) {
		if (floor == null) {
			return true;
		}
		if (qualities == null) {
			return false;
		}
		String effective = ref.modelOverride() != null ? ref.modelOverride() : servedModel;
		return qualities.qualityOf(effective)
				.map(entry -> entry.tier().ordinal() <= floor.ordinal())
				.orElse(false);
	}

	private static boolean isUnrated(
			String servedModel,
			@Nullable ModelQualityCatalog qualities
	) {
		return qualities == null || qualities.qualityOf(servedModel).isEmpty();
	}

	private record ScoredLeg(ProviderRef ref, @Nullable BigDecimal rate, boolean excluded) {
	}
}
