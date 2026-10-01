package io.github.kxng0109.cacherelay.proxy.failover;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.kxng0109.cacherelay.contracts.FailoverStrategy;
import io.github.kxng0109.cacherelay.contracts.ModelAlias;
import io.github.kxng0109.cacherelay.contracts.ProviderConfig;
import io.github.kxng0109.cacherelay.contracts.ProviderRef;
import io.github.kxng0109.cacherelay.contracts.ProviderRef;
import io.github.kxng0109.cacherelay.ledger.ModelPriceCatalog;
import io.github.kxng0109.cacherelay.ledger.ModelPricingEntry;
import io.github.kxng0109.cacherelay.ledger.ModelPricingEntry;
import io.github.kxng0109.cacherelay.ledger.ModelPricingRepository;
import io.github.kxng0109.cacherelay.ledger.ModelQualityCatalog;
import io.github.kxng0109.cacherelay.ledger.ModelQualityEntry;
import io.github.kxng0109.cacherelay.ledger.ModelQualityRepository;
import io.github.kxng0109.cacherelay.ledger.ModelQualityTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cost-aware chain ordering: floor filtering, cheapest-first ordering, and
 * fail-closed guards on unknown price and quality.
 */
@DisplayName("CostAwareChainOrder")
class CostAwareChainOrderTest {

	private final ModelPriceCatalog prices = catalog(Map.of(
			"cheap", new BigDecimal("0.000001"),
			"pricey", new BigDecimal("0.000010"),
			"m", new BigDecimal("0.000005")));

	private final ModelQualityCatalog qualities = qualities(Map.of(
			"cheap", ModelQualityTier.STANDARD,
			"pricey", ModelQualityTier.FRONTIER,
			"budget", ModelQualityTier.BUDGET,
			"m", ModelQualityTier.STANDARD));

	@Test
	@DisplayName("quality mode passes through untouched")
	void qualityModePassesThrough() {
		List<ProviderRef> chain = List.of(ref("pricey"), ref("cheap"));

		CostAwareChainOrder.OrderedChain ordered = CostAwareChainOrder.order(
				chain, null, false, providers(), prices, qualities, "m");

		assertThat(ordered.chain()).containsExactlyElementsOf(chain);
		assertThat(ordered.unknownPrice()).isFalse();
		assertThat(ordered.unrated()).isFalse();
	}

	@Test
	@DisplayName("eco orders cheapest-first by input rate")
	void ecoOrdersCheapestFirst() {
		List<ProviderRef> chain = List.of(new ProviderRef("pricey", "pricey"),
				new ProviderRef("cheap", "cheap"));

		CostAwareChainOrder.OrderedChain ordered = CostAwareChainOrder.order(
				chain, null, true, providers(), prices, qualities, "m");

		assertThat(ordered.chain()).extracting(ProviderRef::providerName)
				.containsExactly("cheap", "pricey");
	}

	@Test
	@DisplayName("floors drop below-tier legs without crossing")
	void floorDropsBelowTier() {
		List<ProviderRef> chain = List.of(new ProviderRef("budget", "budget"),
				new ProviderRef("cheap", "cheap"), new ProviderRef("pricey", "pricey"));

		CostAwareChainOrder.OrderedChain ordered = CostAwareChainOrder.order(
				chain, ModelQualityTier.STANDARD, true, providers(), prices, qualities, "m");

		assertThat(ordered.chain()).extracting(ProviderRef::providerName)
				.containsExactly("cheap", "pricey");
	}

	@Test
	@DisplayName("unrated legs fail closed under any requested floor")
	void unratedFailsClosedUnderFloor() {
		List<ProviderRef> chain = List.of(new ProviderRef("mystery", "mystery"), ref("cheap"));

		CostAwareChainOrder.OrderedChain ordered = CostAwareChainOrder.order(
				chain, ModelQualityTier.BUDGET, true, providers(), prices, qualities, "m");

		assertThat(ordered.chain()).extracting(ProviderRef::providerName)
				.containsExactly("cheap");
		assertThat(ordered.unrated()).isTrue();
	}

	@Test
	@DisplayName("unpriced legs sort last and flag unknown price, never win on zero")
	void unpricedSortsLast() {
		List<ProviderRef> chain = List.of(new ProviderRef("mystery", "mystery"), ref("cheap"));

		CostAwareChainOrder.OrderedChain ordered = CostAwareChainOrder.order(
				chain, null, true, providers(), prices, qualities, "m");

		assertThat(ordered.chain()).extracting(ProviderRef::providerName)
				.containsExactly("cheap", "mystery");
		assertThat(ordered.unknownPrice()).isTrue();
	}

	@Test
	@DisplayName("unconfigured providers never win on price")
	void unconfiguredProviderNeverWins() {
		Map<String, ProviderConfig> thin = Map.of("cheap", config("cheap", "openai"));
		List<ProviderRef> chain = List.of(new ProviderRef("ghost", "ghost"), ref("cheap"));

		CostAwareChainOrder.OrderedChain ordered = CostAwareChainOrder.order(
				chain, null, true, thin, prices, qualities, "m");

		assertThat(ordered.chain()).extracting(ProviderRef::providerName)
				.containsExactly("cheap", "ghost");
		assertThat(ordered.unknownPrice()).isTrue();
	}

	@Test
	@DisplayName("empty after flooring answers 503 in the orchestrator path")
	void emptyAfterFlooring() {
		List<ProviderRef> chain = List.of(new ProviderRef("budget", "budget"));

		CostAwareChainOrder.OrderedChain ordered = CostAwareChainOrder.order(
				chain, ModelQualityTier.STANDARD, true, providers(), prices, qualities, "m");

		assertThat(ordered.chain()).isEmpty();
	}

	@Test
	@DisplayName("missing catalogs degrade to passthrough, never to failure")
	void missingCatalogsPassthrough() {
		List<ProviderRef> chain = List.of(ref("pricey"), ref("cheap"));

		assertThat(CostAwareChainOrder.order(chain, null, true, providers(), null, qualities, "m")
				.chain()).containsExactlyElementsOf(chain);
		assertThat(CostAwareChainOrder
				.order(chain, ModelQualityTier.STANDARD, true, providers(), prices, null, "m")
				.chain()).isEmpty();
	}

	private static ProviderRef ref(String name) {
		return new ProviderRef(name, null);
	}

	private static Map<String, ProviderConfig> providers() {
		return Map.of(
				"cheap", config("cheap", "openai"),
				"pricey", config("pricey", "openai"),
				"budget", config("budget", "openai"),
				"mystery", config("mystery", "openai"));
	}

	private static ProviderConfig config(String name, String type) {
		return new ProviderConfig(name,
				io.github.kxng0109.cacherelay.contracts.ProviderType.valueOf(type.toUpperCase()),
				java.net.URI.create("https://example.com"), null,
				java.time.Duration.ofSeconds(3), java.time.Duration.ofSeconds(30), false);
	}

	private static ModelPriceCatalog catalog(Map<String, BigDecimal> rates) {
		ModelPriceCatalog catalog = mock(ModelPriceCatalog.class);
		lenient().when(catalog.lookup(any(), any())).thenAnswer(inv -> {
			String model = inv.getArgument(1);
			if (rates.containsKey(model)) {
				BigDecimal rate = rates.get(model);
				return Optional.of(new ModelPricingEntry(model, "openai", "chat", rate, rate));
			}
			return Optional.empty();
		});
		return catalog;
	}

	private static ModelQualityCatalog qualities(Map<String, ModelQualityTier> tiers) {
		ModelQualityCatalog catalog = mock(ModelQualityCatalog.class);
		lenient().when(catalog.qualityOf(any())).thenAnswer(inv -> {
			String model = inv.getArgument(0);
			ModelQualityTier tier = tiers.get(model);
			if (tier == null) {
				return Optional.empty();
			}
			return Optional.of(new ModelQualityEntry(model, tier, null, java.time.Instant.now()));
		});
		return catalog;
	}
}
