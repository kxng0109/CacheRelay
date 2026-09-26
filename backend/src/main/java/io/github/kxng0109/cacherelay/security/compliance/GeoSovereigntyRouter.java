package io.github.kxng0109.cacherelay.security.compliance;

import io.github.kxng0109.cacherelay.contracts.GatewayProperties;
import io.github.kxng0109.cacherelay.contracts.ProviderConfig;
import io.github.kxng0109.cacherelay.contracts.ProviderRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Geo-sovereignty and data residency routing filter.
 *
 * <p>Enforces national and international data boundary regulations (such as GDPR Art. 44-49,
 * HIPAA Domestic Boundary, and NDPA 2023 Sec. 41-43) by validating upstream provider jurisdictions before invoking
 * circuit breakers or initiating network requests.</p>
 *
 * <p>Jurisdiction sources in precedence order: the explicit registry (seeded from
 * {@code gateway.provider-jurisdictions} at startup, overridable at runtime),
 * then best-effort URI/name heuristics, then the {@code GLOBAL} fallback. The
 * fallback means "unknown": strict policies never treat it as compliant, so an
 * unregistered provider cannot silently serve a regulated tenant.</p>
 */
@Component
@Slf4j
public class GeoSovereigntyRouter {

	private final Map<String, Jurisdiction> providerJurisdictions = new ConcurrentHashMap<>();

	/**
	 * Creates the router, seeding the registry from gateway configuration.
	 * Invalid jurisdiction codes are skipped with a warning, never fatal.
	 *
	 * @param properties gateway configuration carrying the jurisdiction seed map
	 */
	public GeoSovereigntyRouter(GatewayProperties properties) {
		if (properties != null && properties.getProviderJurisdictions() != null) {
			for (Map.Entry<String, String> seed : properties.getProviderJurisdictions().entrySet()) {
				if (seed.getKey() == null || seed.getValue() == null) {
					continue;
				}
				try {
					registerJurisdiction(seed.getKey(),
							Jurisdiction.valueOf(seed.getValue().trim().toUpperCase(Locale.ROOT)));
				} catch (IllegalArgumentException unknown) {
					log.warn("Ignoring unknown jurisdiction '{}' for provider '{}'",
							seed.getValue(), seed.getKey());
				}
			}
		}
	}

	/**
	 * Explicitly registers a provider's physical regulatory jurisdiction.
	 */
	public void registerJurisdiction(String providerName, Jurisdiction jurisdiction) {
		if (providerName != null && jurisdiction != null) {
			providerJurisdictions.put(providerName.toLowerCase(Locale.ROOT), jurisdiction);
		}
	}

	/**
	 * Resolves the jurisdiction of a provider from registry or URI heuristics.
	 */
	public Jurisdiction resolveJurisdiction(String providerName, ProviderConfig config) {
		if (providerName != null) {
			Jurisdiction registered = providerJurisdictions.get(providerName.toLowerCase(Locale.ROOT));
			if (registered != null) {
				return registered;
			}
		}

		if (config != null && config.baseUrl() != null) {
			URI uri = config.baseUrl();
			String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.ROOT) : "";
			String name = config.name() != null ? config.name().toLowerCase(Locale.ROOT) : "";

			if (matchesJurisdiction(host, name, "eu")) {
				return Jurisdiction.EU;
			}
			if (matchesJurisdiction(host, name, "ng")) {
				return Jurisdiction.NG;
			}
			if (matchesJurisdiction(host, name, "uk")) {
				return Jurisdiction.UK;
			}
			if (matchesJurisdiction(host, name, "ch")) {
				return Jurisdiction.CH;
			}
			if (name.contains("-us") || name.contains("_us") || name.contains("us-")
					|| hostEqualsOrSubdomain(host, "openai.com")
					|| hostEqualsOrSubdomain(host, "anthropic.com")) {
				return Jurisdiction.US;
			}
		}

		return Jurisdiction.GLOBAL;
	}

	/**
	 * Matches a host against a base domain with registrable-domain discipline:
	 * exact equality or a dot-boundary suffix. Bare substring checks would accept
	 * look-alikes such as {@code openai.com.evil.example}.
	 *
	 * @param host   lower-cased request host, never {@code null}
	 * @param domain lower-cased base domain, never {@code null}
	 * @return {@code true} only on exact or dot-suffix match
	 */
	static boolean hostEqualsOrSubdomain(String host, String domain) {
		return host.equals(domain) || host.endsWith("." + domain);
	}

	private static boolean matchesJurisdiction(String host, String name, String code) {
		return host.endsWith("." + code)
				|| name.contains("-" + code)
				|| name.contains("_" + code)
				|| name.contains(code + "-");
	}

	/**
	 * Filters a provider failover chain according to the active data residency policy.
	 *
	 * @param chain              candidate chain of provider references
	 * @param providers          configured provider map
	 * @param policy             enforced residency policy
	 * @param originJurisdiction origin jurisdiction of the client/tenant
	 * @param model              requested model alias (for diagnostics)
	 * @return filtered list of compliant provider references
	 * @throws DataResidencyBreachException if no compliant provider exists under strict/cascade policy
	 */
	public List<ProviderRef> filterChain(
			List<ProviderRef> chain,
			Map<String, ProviderConfig> providers,
			ResidencyPolicy policy,
			Jurisdiction originJurisdiction,
			String model
	) {
		if (chain == null || chain.isEmpty()) {
			return List.of();
		}
		if (policy == null || originJurisdiction == null || originJurisdiction == Jurisdiction.GLOBAL) {
			return chain;
		}

		List<ProviderRef> filtered = new ArrayList<>();
		for (ProviderRef ref : chain) {
			ProviderConfig config = providers.get(ref.providerName());
			Jurisdiction target = resolveJurisdiction(ref.providerName(), config);

			boolean allowed = switch (policy) {
				case STRICT_SOVEREIGN -> target == originJurisdiction;
				case SOVEREIGN_CASCADE -> Jurisdiction.isAdequate(originJurisdiction, target);
				case PERMISSIVE_FAILOVER_WITH_AUDIT -> true;
			};

			if (allowed) {
				filtered.add(ref);
			}
		}

		if (filtered.isEmpty() && (policy == ResidencyPolicy.STRICT_SOVEREIGN
				|| policy == ResidencyPolicy.SOVEREIGN_CASCADE)) {
			throw new DataResidencyBreachException(originJurisdiction, model);
		}

		return filtered;
	}
}
