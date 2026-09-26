package io.github.kxng0109.cacherelay.proxy;

import io.github.kxng0109.cacherelay.contracts.ModelAlias;
import io.github.kxng0109.cacherelay.contracts.ProviderRef;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Shared provider allowlist enforcement for chat and embeddings routing.
 *
 * <p>An empty allowlist means every provider is allowed. Otherwise only chain
 * steps naming an allowed provider survive; when none survive the caller must
 * reject with 403 rather than steering to an unlisted provider.</p>
 *
 * @since 1.8.0
 */
public final class ProviderAccess {

	private ProviderAccess() {
	}

	/**
	 * Checks whether a single provider is reachable under the allowlist.
	 *
	 * @param allowedProviders key allowlist, empty means all allowed, possibly {@code null}
	 * @param providerName     resolved provider name, possibly {@code null}
	 * @return {@code true} when the provider may serve the request
	 */
	public static boolean isProviderAllowed(
			@Nullable Set<String> allowedProviders,
			@Nullable String providerName
	) {
		if (allowedProviders == null || allowedProviders.isEmpty()) {
			return true;
		}
		return providerName != null && allowedProviders.contains(providerName);
	}

	/**
	 * Filters an alias chain to the allowed providers, preserving order and strategy.
	 *
	 * @param alias            routing plan, must not be {@code null}
	 * @param allowedProviders key allowlist, empty means all allowed, possibly {@code null}
	 * @return the filtered alias, or empty when no chain step survives
	 */
	public static Optional<ModelAlias> filterAlias(
			ModelAlias alias,
			@Nullable Set<String> allowedProviders
	) {
		if (allowedProviders == null || allowedProviders.isEmpty()) {
			return Optional.of(alias);
		}
		List<ProviderRef> survivors = alias.chain().stream()
				.filter(ref -> allowedProviders.contains(ref.providerName()))
				.toList();
		if (survivors.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new ModelAlias(survivors, alias.strategy()));
	}
}
