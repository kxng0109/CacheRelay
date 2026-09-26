package io.github.kxng0109.cacherelay.proxy;

import io.github.kxng0109.cacherelay.contracts.FailoverStrategy;
import io.github.kxng0109.cacherelay.contracts.ModelAlias;
import io.github.kxng0109.cacherelay.contracts.ProviderRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProviderAccess")
class ProviderAccessTest {

	private static ModelAlias alias() {
		return new ModelAlias(
				List.of(
						new ProviderRef("openai-main", null),
						new ProviderRef("ollama-local", null)
				),
				FailoverStrategy.SEQUENTIAL
		);
	}

	@Test
	@DisplayName("empty allowlist permits every provider and keeps the chain intact")
	void emptyAllowlistPermitsAll() {
		assertThat(ProviderAccess.isProviderAllowed(Set.of(), "openai-main")).isTrue();
		assertThat(ProviderAccess.isProviderAllowed(null, "anything")).isTrue();

		Optional<ModelAlias> filtered = ProviderAccess.filterAlias(alias(), Set.of());
		assertThat(filtered).isPresent();
		assertThat(filtered.get().chain()).hasSize(2);
	}

	@Test
	@DisplayName("non-empty allowlist filters the chain, preserving order and strategy")
	void allowlistFiltersChain() {
		Optional<ModelAlias> filtered = ProviderAccess.filterAlias(alias(), Set.of("ollama-local"));

		assertThat(filtered).isPresent();
		assertThat(filtered.get().chain())
				.extracting(ProviderRef::providerName)
				.containsExactly("ollama-local");
		assertThat(filtered.get().strategy()).isEqualTo(FailoverStrategy.SEQUENTIAL);
	}

	@Test
	@DisplayName("allowlist with no surviving step yields empty (caller must 403)")
	void noSurvivorYieldsEmpty() {
		assertThat(ProviderAccess.isProviderAllowed(Set.of("ollama-local"), "openai-main")).isFalse();
		assertThat(ProviderAccess.isProviderAllowed(Set.of("ollama-local"), null)).isFalse();

		assertThat(ProviderAccess.filterAlias(alias(), Set.of("no-such-provider"))).isEmpty();
	}

	@Test
	@DisplayName("FS-B12: null allowlists behave like empty ones (all allowed)")
	void nullAllowlistPermitsAll() {
		assertThat(ProviderAccess.isProviderAllowed(null, "openai-main")).isTrue();

		assertThat(ProviderAccess.filterAlias(alias(), null)).isPresent();
	}
}
