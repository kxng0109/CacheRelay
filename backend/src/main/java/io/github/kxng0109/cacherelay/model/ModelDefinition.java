package io.github.kxng0109.cacherelay.model;

import java.util.List;

import io.github.kxng0109.cacherelay.contracts.FailoverStrategy;
import io.github.kxng0109.cacherelay.contracts.ProviderRef;

/**
 * One effective model alias: its routing plan plus where it originates.
 *
 * @param name     client facing model name
 * @param chain    ordered provider steps to try
 * @param strategy how the chain is walked (sequential or race)
 * @param source   whether the alias is file-bound or database-managed
 */
public record ModelDefinition(
		String name,
		List<ProviderRef> chain,
		FailoverStrategy strategy,
		AliasSource source
) {
	/**
	 * Stores immutable copies so callers cannot mutate the definition after
	 * it is published.
	 */
	public ModelDefinition {
		chain = chain == null ? List.of() : List.copyOf(chain);
	}
}
