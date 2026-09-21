package io.github.kxng0109.cacherelay.a2a.protocol;

import io.github.kxng0109.cacherelay.a2a.config.A2aGatewayProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public A2A discovery surface: the gateway's own agent card at the A2A v0.3
 * well-known path.
 *
 * <p>The public card deliberately exposes no agent inventory ({@code skills} is empty):
 * per-agent cards are key-gated and RBAC-filtered by {@link A2aProxyController}. The card
 * advertises JSON-RPC over HTTP with Bearer authentication and honestly reports that
 * streaming is not yet served (slice 1 is non-streaming only).</p>
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "A2A - Discovery", description = "Public A2A gateway discovery card")
public class A2aDiscoveryController {

	private final A2aGatewayProperties properties;

	private final ObjectMapper objectMapper;

	/**
	 * Serves the gateway's A2A agent card.
	 *
	 * @return HTTP 200 with the card, or HTTP 404 when the A2A subsystem is disabled
	 */
	@Operation(
			summary = "Gateway A2A agent card",
			description = "A2A v0.3 discovery card for the gateway itself. Agent inventory is never disclosed here; per-agent cards are key-gated."
	)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "200", description = "Gateway agent card",
					content = @Content(mediaType = "application/json")),
			@ApiResponse(responseCode = "404", description = "A2A proxy disabled")
	})
	@GetMapping(value = "/.well-known/agent-card.json", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> gatewayCard() {
		if (!properties.isEnabled()) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
		}

		String base = properties.getPublicBaseUrl();
		String trimmed = base == null
				? ""
				: (base.endsWith("/") ? base.substring(0, base.length() - 1) : base);

		ObjectNode card = objectMapper.createObjectNode();
		card.put("protocolVersion", properties.getDefaultProtocolVersion());
		card.put("name", "CacheRelay A2A Gateway");
		card.put("description",
				"Governed A2A proxy with virtual-key authentication, per-key agent RBAC, and circuit breaking.");
		card.put("url", trimmed + "/v1/a2a");
		card.put("version", properties.getGatewayVersion());

		ObjectNode capabilities = card.putObject("capabilities");
		capabilities.put("streaming", false);
		capabilities.put("pushNotifications", false);

		card.putArray("defaultInputModes").add("text/plain").add("application/json");
		card.putArray("defaultOutputModes").add("text/plain").add("application/json");
		card.putArray("skills");

		ObjectNode securityScheme = card.putObject("securitySchemes").putObject("virtualKeyBearer");
		securityScheme.put("type", "http");
		securityScheme.put("scheme", "bearer");

		card.putArray("security").addObject().putArray("virtualKeyBearer");

		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_JSON)
				.body(card.toString());
	}
}
