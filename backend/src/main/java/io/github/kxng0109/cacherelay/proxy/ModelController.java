package io.github.kxng0109.cacherelay.proxy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.kxng0109.cacherelay.contracts.GatewayProperties;
import io.github.kxng0109.cacherelay.contracts.SHA256Hash;
import io.github.kxng0109.cacherelay.contracts.VirtualApiKey;
import io.github.kxng0109.cacherelay.security.filter.KeyAuthFilter;
import io.github.kxng0109.cacherelay.security.ratelimit.KeyManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * OpenAI-compatible model catalog: lists the configured model aliases and their primary
 * providers. Key-authenticated like the relay paths (no budget or rate-limit charge —
 * metadata is cheap and unthrottled, mirroring the MCP catalog lists).
 */
@RestController
@RequestMapping("/v1/models")
@RequiredArgsConstructor
@Tag(name = "Models", description = "Configured model alias catalog")
public class ModelController {

	private final GatewayProperties gatewayProperties;
	private final KeyManagementService keyManagementService;

	/**
	 * Lists configured models in OpenAI list shape.
	 *
	 * @param request current request (bearer key)
	 * @return model list, or 401 for missing/invalid/disabled keys
	 */
	@Operation(summary = "List models",
			description = "OpenAI-compatible model catalog from the configured aliases")
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Model list"),
			@ApiResponse(responseCode = "401", description = "Missing, invalid, or disabled API key")
	})
	@GetMapping
	public ResponseEntity<Map<String, Object>> listModels(HttpServletRequest request) {
		VirtualApiKey apiKey = resolveApiKey(request);
		if (!keyManagementService.isUsable(apiKey)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
					.body(Map.of("error", Map.of("message", "Invalid API key", "code", "KEY_NOT_FOUND")));
		}
		long now = Instant.now().getEpochSecond();
		List<Map<String, Object>> data = gatewayProperties.getAliases().entrySet().stream()
				.sorted(Map.Entry.comparingByKey())
				.map(entry -> Map.<String, Object>of(
						"id", entry.getKey(),
						"object", "model",
						"created", now,
						"owned_by", entry.getValue().chain().isEmpty()
								? "cacherelay"
								: entry.getValue().chain().getFirst().providerName()))
				.toList();
		return ResponseEntity.ok(Map.of("object", "list", "data", data));
	}

	private @Nullable VirtualApiKey resolveApiKey(HttpServletRequest request) {
		String authHeader = request.getHeader("Authorization");
		if (authHeader != null && authHeader.startsWith("Bearer ")) {
			String token = authHeader.substring("Bearer ".length()).trim();
			if (!token.isBlank()) {
				VirtualApiKey direct =
						keyManagementService.findByHash(SHA256Hash.fromRawKey(token)).orElse(null);
				if (direct != null) {
					return direct;
				}
				String actAsKey = request.getHeader(KeyAuthFilter.ACT_AS_KEY_HEADER);
				if (actAsKey != null && !actAsKey.isBlank()) {
					return keyManagementService.resolveActAsSelf(token, actAsKey.trim())
							.orElse(null);
				}
			}
		}
		return null;
	}
}
