package io.github.kxng0109.cacherelay.admin;

import java.util.Map;

import io.github.kxng0109.cacherelay.admin.dto.ProviderValidationStatus;

/**
 * Static validation evidence per provider name.
 *
 * <p>Evidence as of 2026-09-22: docs-derived contract fixtures for all configured providers,
 * plus one-shot dummy-key liveness probes that reached 23 provider auth layers with well-formed
 * provider errors. The four self-hosted servers were not running at probe time, so they stay
 * contract-checked. No provider has performed live inference through this gateway yet, so
 * nothing is marked live-verified. Unknown names read as unverified rather than trusted.</p>
 */
public final class ProviderValidationRegistry {

	private static final Map<String, ProviderValidationStatus> STATUSES = Map.ofEntries(
			Map.entry("openai", ProviderValidationStatus.AUTH_REACHABLE),
			Map.entry("openrouter", ProviderValidationStatus.AUTH_REACHABLE),
			Map.entry("anthropic", ProviderValidationStatus.AUTH_REACHABLE),
			Map.entry("together", ProviderValidationStatus.AUTH_REACHABLE),
			Map.entry("groq", ProviderValidationStatus.AUTH_REACHABLE),
			Map.entry("mistral", ProviderValidationStatus.AUTH_REACHABLE),
			Map.entry("xai", ProviderValidationStatus.AUTH_REACHABLE),
			Map.entry("deepinfra", ProviderValidationStatus.AUTH_REACHABLE),
			Map.entry("fireworks", ProviderValidationStatus.AUTH_REACHABLE),
			Map.entry("cerebras", ProviderValidationStatus.AUTH_REACHABLE),
			Map.entry("sambanova", ProviderValidationStatus.AUTH_REACHABLE),
			Map.entry("nebius", ProviderValidationStatus.AUTH_REACHABLE),
			Map.entry("novita", ProviderValidationStatus.AUTH_REACHABLE),
			Map.entry("moonshot", ProviderValidationStatus.AUTH_REACHABLE),
			Map.entry("zhipu", ProviderValidationStatus.AUTH_REACHABLE),
			Map.entry("minimax", ProviderValidationStatus.AUTH_REACHABLE),
			Map.entry("qwen", ProviderValidationStatus.AUTH_REACHABLE),
			Map.entry("stepfun", ProviderValidationStatus.AUTH_REACHABLE),
			Map.entry("cloudflare", ProviderValidationStatus.AUTH_REACHABLE),
			Map.entry("hyperbolic", ProviderValidationStatus.AUTH_REACHABLE),
			Map.entry("ionet", ProviderValidationStatus.AUTH_REACHABLE),
			Map.entry("friendli", ProviderValidationStatus.AUTH_REACHABLE),
			Map.entry("bedrock", ProviderValidationStatus.AUTH_REACHABLE),
			Map.entry("ollama", ProviderValidationStatus.CONTRACT_CHECKED),
			Map.entry("vllm", ProviderValidationStatus.CONTRACT_CHECKED),
			Map.entry("llamacpp", ProviderValidationStatus.CONTRACT_CHECKED),
			Map.entry("lmstudio", ProviderValidationStatus.CONTRACT_CHECKED));

	private ProviderValidationRegistry() {
	}

	/**
	 * Returns the recorded validation status for a provider name.
	 *
	 * @param name provider identifier from {@code gateway.providers}; may be {@code null}
	 * @return the recorded status, or {@link ProviderValidationStatus#UNVERIFIED} when no
	 *         evidence exists for the name
	 */
	public static ProviderValidationStatus statusOf(String name) {
		if (name == null) {
			return ProviderValidationStatus.UNVERIFIED;
		}
		ProviderValidationStatus status = STATUSES.get(name);
		return status != null ? status : ProviderValidationStatus.UNVERIFIED;
	}
}
