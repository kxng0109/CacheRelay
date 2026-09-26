package io.github.kxng0109.cacherelay.contracts;

import jakarta.validation.Valid;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gateway configuration bound from the {@code gateway} prefix.
 *
 * <p>Owns four things:</p>
 * <ul>
 *   <li>{@code providers} — the pool of configured upstream providers, keyed
 *       by name.</li>
 *   <li>{@code aliases} — the routing plans behind each client facing model
 *       name.</li>
 *   <li>{@code bootstrapKeys} — virtual API keys seeded into Redis at startup
 *       so requests can be authenticated before an admin API exists. Plaintext
 *       keys must only ever be injected via environment variables (never
 *       committed to the repository).</li>
 *   <li>{@code providerJurisdictions} — physical regulatory jurisdiction per
 *       provider name, seeding the sovereignty router (unregistered providers
 *       fail closed under strict residency policies).</li>
 * </ul>
 */
@Validated
@ConfigurationProperties("gateway")
public class GatewayProperties {

	private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

	private Map<String, ModelAlias> aliases = new LinkedHashMap<>();

	private List<@Valid BootstrapKey> bootstrapKeys = new ArrayList<>();

	private Map<String, String> providerJurisdictions = new LinkedHashMap<>();

	/**
	 * @return the configured providers, keyed by {@link ProviderConfig#name()}
	 */
	public Map<String, ProviderConfig> getProviders() {
		return providers;
	}

	/**
	 * @param providers providers to serve
	 */
	public void setProviders(Map<String, ProviderConfig> providers) {
		this.providers = providers == null
				? Map.of()
				: Collections.unmodifiableMap(new LinkedHashMap<>(providers));
	}

	/**
	 * @return the configured aliases, keyed by client facing model name
	 */
	public Map<String, ModelAlias> getAliases() {
		return aliases;
	}

	/**
	 * @param aliases aliases to serve
	 */
	public void setAliases(Map<String, ModelAlias> aliases) {
		this.aliases = aliases == null
				? Map.of()
				: Collections.unmodifiableMap(new LinkedHashMap<>(aliases));
	}

	/**
	 * @return the configured bootstrap keys (empty when none are configured)
	 */
	public List<BootstrapKey> getBootstrapKeys() {
		return bootstrapKeys;
	}

	/**
	 * @param bootstrapKeys bootstrap keys to seed at startup
	 */
	public void setBootstrapKeys(List<BootstrapKey> bootstrapKeys) {
		this.bootstrapKeys = bootstrapKeys == null
				? List.of()
				: Collections.unmodifiableList(new ArrayList<>(bootstrapKeys));
	}

	/**
	 * @return provider name to jurisdiction code (e.g. {@code EU}), bound from
	 * {@code gateway.provider-jurisdictions}; empty when unconfigured
	 */
	public Map<String, String> getProviderJurisdictions() {
		return providerJurisdictions;
	}

	/**
	 * @param providerJurisdictions provider name to jurisdiction code seed
	 */
	public void setProviderJurisdictions(Map<String, String> providerJurisdictions) {
		this.providerJurisdictions = providerJurisdictions == null
				? Map.of()
				: Collections.unmodifiableMap(new LinkedHashMap<>(providerJurisdictions));
	}
}