package io.github.kxng0109.cacherelay.admin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.kxng0109.cacherelay.admin.dto.ProviderListResponse;
import io.github.kxng0109.cacherelay.admin.dto.ProviderStatusResponse;
import io.github.kxng0109.cacherelay.config.OpenApiConfig;
import io.github.kxng0109.cacherelay.config.SensitiveString;
import io.github.kxng0109.cacherelay.contracts.GatewayProperties;
import io.github.kxng0109.cacherelay.contracts.ModelAlias;
import io.github.kxng0109.cacherelay.contracts.ProviderConfig;
import io.github.kxng0109.cacherelay.contracts.ProviderRef;
import io.github.kxng0109.cacherelay.proxy.failover.CircuitBreakerFactory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller listing the configured upstream providers under
 * {@code /v1/admin/providers}. Each row joins the file-bound provider
 * configuration with live routing health: whether a credential is configured
 * (never the credential itself), the circuit breaker state observed by this
 * instance, and how many alias chain steps reference the provider. This is the
 * listing an admin UI reads to build the provider dropdown when composing a
 * model alias.
 */
@RestController
@RequestMapping("/v1/admin/providers")
@RequiredArgsConstructor
@Tag(name = "Admin - Provider Management", description = "Configured upstream providers with credential and live routing health")
public class AdminProviderController {

	private final GatewayProperties gatewayProperties;

	private final CircuitBreakerFactory circuitBreakerFactory;

	/**
	 * Lists every configured provider with credential and routing health.
	 *
	 * @return HTTP 200 OK with providers ordered by name
	 */
	@Operation(
			summary = "List configured providers",
			description = "Lists every provider configured under `gateway.providers` with its dialect, base URL, whether a credential is configured (the key value is never exposed), its timeouts, the live circuit breaker state observed by this instance, how many alias chain steps reference it, and its integration validation status.",
			security = {
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_KEY_HEADER),
					@SecurityRequirement(name = OpenApiConfig.SCHEME_ADMIN_BEARER)
			}
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Providers listed successfully",
					content = @Content(mediaType = "application/json",
							schema = @Schema(implementation = ProviderListResponse.class))),
			@ApiResponse(responseCode = "401", description = "Unauthorized: Master Admin key missing or incorrect")
	})
	@GetMapping
	public ResponseEntity<ProviderListResponse> listProviders() {
		Map<String, Integer> aliasReferences = aliasReferenceCounts();
		List<ProviderStatusResponse> providers = gatewayProperties.getProviders().entrySet().stream()
				.sorted(Map.Entry.comparingByKey())
				.map(entry -> toStatus(entry.getKey(), entry.getValue(),
						aliasReferences.getOrDefault(entry.getKey(), 0)))
				.toList();
		return ResponseEntity.ok(new ProviderListResponse(providers));
	}

	private Map<String, Integer> aliasReferenceCounts() {
		Map<String, Integer> counts = new HashMap<>();
		for (ModelAlias alias : gatewayProperties.getAliases().values()) {
			for (ProviderRef step : alias.chain()) {
				counts.merge(step.providerName(), 1, Integer::sum);
			}
		}
		return counts;
	}

	private ProviderStatusResponse toStatus(String name, ProviderConfig config, int aliasReferences) {
		return new ProviderStatusResponse(
				name,
				config.type() == null ? "UNKNOWN" : config.type().name(),
				config.baseUrl() == null ? null : config.baseUrl().toString(),
				isKeyConfigured(config),
				config.connectTimeout() == null ? 0L : config.connectTimeout().toSeconds(),
				config.requestTimeout() == null ? 0L : config.requestTimeout().toSeconds(),
				config.isEmbeddingSingleAsString(),
				circuitBreakerFactory.get(name).getState().name(),
				aliasReferences,
				ProviderValidationRegistry.statusOf(name).name());
	}

	private static boolean isKeyConfigured(ProviderConfig config) {
		SensitiveString key = config.apiKey();
		return key != null && key.value() != null && !key.value().isBlank();
	}
}
